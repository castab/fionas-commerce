# syntax=docker/dockerfile:1

# fionas-commerce container image. Built for Railway, but nothing here is Railway-specific:
# any container platform that supplies the DATABASE_* variables and PORT can run it.
#
# Build (locally), a development image whose application version is 0.0.0-SNAPSHOT:
#   docker build --build-arg GITHUB_ACTOR=<user> --build-arg GITHUB_TOKEN=<read:packages token> -t fionas-commerce .
# Build the way a release does, with an explicit application version (the Gradle project
# version, which is also the OpenAPI document's info.version):
#   docker build --build-arg APP_VERSION=0.0.21 --build-arg GITHUB_ACTOR=<user> --build-arg GITHUB_TOKEN=<read:packages token> -t fionas-commerce:0.0.21 .
# On Railway, set GITHUB_ACTOR and GITHUB_TOKEN as service variables; Railway passes a
# variable to a Dockerfile build only because it is declared with ARG below. APP_VERSION
# is optional there and defaults to the development version.

# ---------------------------------------------------------------------------
# Build stage: Java 25 is a hard requirement (build.gradle.kts pins the toolchain and
# gradle.properties disables toolchain auto-download), so the JDK image must be 25.
# ---------------------------------------------------------------------------
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace

# The wrapper first, so the Gradle distribution download is its own cached layer.
COPY gradlew ./
COPY gradle/wrapper gradle/wrapper
RUN sh gradlew --version --no-daemon

COPY gradle.properties settings.gradle.kts build.gradle.kts ./
COPY gradle/libs.versions.toml gradle/libs.versions.toml
COPY src src

# Gradle reads these from the environment (settings.gradle.kts) to resolve
# io.github.castab:commerce-runtime from GitHub Packages, which needs a token with
# read:packages even for public packages. They are declared only here, in the build stage,
# and only after the layers that do not need them, so the token neither reaches a cached
# earlier layer nor the final image: that is built from the runtime stage below, which
# copies nothing but the jar.
ARG GITHUB_ACTOR
ARG GITHUB_TOKEN

# The application version the jar reports (fionas-commerce.properties, hence fionaVersion()
# and the OpenAPI document's info.version). It overrides the development default in
# gradle.properties exactly as a release's -Pversion does; a release supplies the version of
# its Git tag. Declared last so changing it rebuilds only the jar.
ARG APP_VERSION=0.0.0-SNAPSHOT

# Only the executable jar: not `build`, which also runs the ktlint check and the tests
# (those need Docker and belong in CI). --no-daemon because the container exits after this.
# The properties check fails the build, rather than ship an image that reports another
# version than the one it was built for.
RUN sh gradlew shadowJar --no-daemon --no-build-cache --console=plain "-Pversion=${APP_VERSION}"     && jar --extract --file build/libs/fionas-commerce-all.jar fionas-commerce.properties     && grep -qxF "version=${APP_VERSION}" fionas-commerce.properties

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

# Memory is billed by usage, so the JVM is tuned to hand memory back when idle rather than
# keep what a burst grew it to:
#   - G1PeriodicGCInterval: after 60s with no GC, G1 runs a collection that uncommits free
#     heap. ExplicitGCInvokesConcurrent keeps that collection concurrent instead of a full
#     stop-the-world GC. Min/MaxHeapFreeRatio let G1 shrink the heap more aggressively.
#   - TrimNativeHeapInterval: every 60s returns freed native memory to the OS. Each Argon2
#     hash allocates ~64 MB outside the heap, which glibc otherwise keeps.
#   - MALLOC_ARENA_MAX: stops glibc creating a malloc arena per thread, each holding memory.
#   - UseCompactObjectHeaders: smaller object headers, a permanent saving.
# G1 is selected explicitly: the JVM would otherwise pick Serial GC on a 1-CPU container,
# and Serial never shrinks the heap while idle. Size the heap from the container's memory
# limit, and exit on OutOfMemoryError so the platform restarts the process rather than
# leaving a half-alive JVM serving. Exec form, so the JVM is PID 1 and receives SIGTERM,
# which triggers the application's shutdown hook.
# --enable-native-access is required by the JNA-loaded Argon2 library; Java 25 warns without
# it and a future release will block it.
ENV MALLOC_ARENA_MAX=2
ENTRYPOINT ["java", "-XX:+UseG1GC", "-XX:MaxRAMPercentage=75", "-XX:G1PeriodicGCInterval=60000", "-XX:+ExplicitGCInvokesConcurrent", "-XX:MinHeapFreeRatio=10", "-XX:MaxHeapFreeRatio=30", "-XX:TrimNativeHeapInterval=60000", "-XX:+UseCompactObjectHeaders", "-XX:+ExitOnOutOfMemoryError", "--enable-native-access=ALL-UNNAMED", "-jar", "/app/fionas-commerce.jar"]
