#!/usr/bin/env bash
# Produces the release form of the OpenAPI document for a version, and nothing else:
#
#   scripts/package-openapi.sh <version> <output-directory>
#
# 1. generates the document with the repository's one generator, `./gradlew generateOpenApi`,
#    with APP_VERSION=<version>, the environment variable the application reads for its
#    info.version (the build itself embeds no version);
# 2. requires its info.version to be exactly <version>;
# 3. writes <output-directory>/fionas-commerce-openapi-<version>.json and its
#    .sha256 (sha256sum format, verifiable with `sha256sum --check`).
#
# It publishes nothing. The release workflow runs it with the version of the tag and uploads
# the two files; pull request CI runs it with a throwaway version as a dry run, so a failure
# to generate or to carry the version surfaces before a tag is ever pushed. Needs jq and
# sha256sum (both on GitHub's ubuntu runners).
set -euo pipefail

if [ "$#" -ne 2 ]; then
  echo "usage: $0 <version> <output-directory>" >&2
  exit 2
fi
version="$1"
output="$2"

cd "$(dirname "$0")/.."

APP_VERSION="$version" ./gradlew generateOpenApi

document="build/openapi/fionas-commerce-openapi.json"
actual="$(jq -r '.info.version' "$document")"
if [ "$actual" != "$version" ]; then
  echo "OpenAPI info.version is '$actual', expected '$version'." >&2
  exit 1
fi
echo "OpenAPI info.version is $version"

name="fionas-commerce-openapi-$version.json"
mkdir -p "$output"
cp "$document" "$output/$name"
(cd "$output" && sha256sum "$name" > "$name.sha256" && sha256sum --check "$name.sha256")
