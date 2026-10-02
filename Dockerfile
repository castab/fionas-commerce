# syntax=docker/dockerfile:1

# fionas-commerce container image. Built for Railway, but nothing here is Railway-specific:
# any container platform that supplies the DATABASE_* variables and PORT can run it.
#
# Build (locally):
#   docker build --build-arg GITHUB_ACTOR=<user> --build-arg GITHUB_TOKEN=<read:packages token> -t fionas-commerce .
# On Railway, set GITHUB_ACTOR and GITHUB_TOKEN as service variables; Railway passes a
# variable to a Dockerfile build only because it is declared with ARG below.

# ---------------------------------------------------------------------------
# Build stage: Java 25 is a hard requirement (build.gradle.kts pins the toolchain and
# gradle.properties disables toolchain auto-download), so the JDK image must be 25.
# ---------------------------------------------------------------------------
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace

# Gradle reads these from the environment (settings.gradle.kts) to resolve
# io.github.castab:commerce-runtime from GitHub Packages, which needs a token with
# read:packages even for public packages. They are scoped to this stage: the final image
# is built from the runtime stage below, so the token is never part of it.
ARG GITHUB_ACTOR
ARG GITHUB_TOKEN

# The wrapper first, so the Gradle distribution download is its own cached layer.
COPY gradlew ./
COPY gradle/wrapper gradle/wrapper
RUN sh gradlew --version --no-daemon

COPY gradle.properties settings.gradle.kts build.gradle.kts ./
COPY gradle/libs.versions.toml gradle/libs.versions.toml
COPY src src

# Only the executable jar: not `build`, which also runs the ktlint check and the tests
# (those need Docker and belong in CI). --no-daemon because the container exits after this.
RUN sh gradlew shadowJar --no-daemon --no-build-cache --console=plain

# ---------------------------------------------------------------------------
# Runtime stage: a JRE on a glibc (Debian/Ubuntu) base. Not Alpine: argon2-jvm loads a
# native Argon2 library through JNA, and its bundled Linux libraries are glibc builds.
# ---------------------------------------------------------------------------
FROM eclipse-temurin:25-jre AS runtime

RUN groupadd --system --gid 10001 fionas \
    && useradd --system --uid 10001 --gid fionas --no-create-home --shell /usr/sbin/nologin fionas

WORKDIR /app
COPY --from=build --chown=fionas:fionas /workspace/build/libs/fionas-commerce-all.jar fionas-commerce.jar

USER fionas

# Configuration is entirely environmental (see application.conf and .env.example):
# DATABASE_JDBC_URL, DATABASE_USERNAME and DATABASE_PASSWORD are required; PORT is read
# from the platform (Railway sets it) and defaults to 8080.
EXPOSE 8080

# Size the heap from the container's memory limit, and exit on OutOfMemoryError so the
# platform restarts the process rather than leaving a half-alive JVM serving. Exec form,
# so the JVM is PID 1 and receives SIGTERM, which triggers the application's shutdown hook.
# --enable-native-access is required by the JNA-loaded Argon2 library; Java 25 warns without
# it and a future release will block it.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "--enable-native-access=ALL-UNNAMED", "-jar", "/app/fionas-commerce.jar"]
