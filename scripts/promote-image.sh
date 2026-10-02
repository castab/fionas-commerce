#!/usr/bin/env bash
# Adds a release version tag to the image that was already built and published for a commit:
#
#   scripts/promote-image.sh <image> <commit-sha> <version>
#
#   <image>:sha-<commit-sha>  (built from that commit and pushed by CI)
#        ->  <image>:<version>   (the same manifest: same digest, nothing rebuilt)
#
# The release rebuilds nothing. It waits (up to POLL_ATTEMPTS x POLL_INTERVAL_SECONDS, default
# 30 x 30s) for the source image, because the push to main that builds it can still be running
# when a tag is pushed straight after the merge, and fails if it never appears. A version tag
# that already exists must point at the very same digest (a rerun converges, a different image
# is refused); a published version is never re-pointed. The caller must be logged in to the
# registry. Needs `docker buildx`.
set -euo pipefail

if [ "$#" -ne 3 ]; then
  echo "usage: $0 <image> <commit-sha> <version>" >&2
  exit 2
fi
image="$1"
sha="$2"
version="$3"
source="$image:sha-$sha"
target="$image:$version"
attempts="${POLL_ATTEMPTS:-30}"
interval="${POLL_INTERVAL_SECONDS:-30}"

# Prints the manifest digest of $1. Exit 0: found. Exit 1: the tag does not exist. Exit 2: any
# other failure (authentication, network), which must never be mistaken for "absent".
digest_of() {
  local output
  if output="$(docker buildx imagetools inspect "$1" --format '{{.Manifest.Digest}}' 2>&1)"; then
    printf '%s' "$output"
    return 0
  fi
  if printf '%s' "$output" | grep -qi 'not found'; then
    return 1
  fi
  echo "Could not inspect $1: $output" >&2
  return 2
}

source_digest=""
for ((attempt = 1; attempt <= attempts; attempt++)); do
  status=0
  source_digest="$(digest_of "$source")" || status=$?
  case "$status" in
    0) break ;;
    1) ;;
    *) exit 1 ;;
  esac
  if [ "$attempt" -lt "$attempts" ]; then
    echo "No image $source yet (attempt $attempt of $attempts); waiting ${interval}s for the push to main that builds it"
    sleep "$interval"
  fi
done
if [ -z "$source_digest" ]; then
  echo "No image $source was published. Its commit must have been built by the push to main (or, for a fast-forward merge, by its pull request); check that workflow and rerun the release." >&2
  exit 1
fi
echo "Found $source at $source_digest"

status=0
existing="$(digest_of "$target")" || status=$?
case "$status" in
  0)
    if [ "$existing" != "$source_digest" ]; then
      echo "$target already exists at $existing, not the image of this commit ($source_digest). A published version is never re-pointed." >&2
      exit 1
    fi
    echo "$target already points at $source_digest"
    exit 0
    ;;
  1) ;;
  *) exit 1 ;;
esac

docker buildx imagetools create --tag "$target" "$source"

published="$(digest_of "$target")"
if [ "$published" != "$source_digest" ]; then
  echo "$target is at $published after tagging, expected $source_digest." >&2
  exit 1
fi
echo "Tagged $target as $source_digest"
