# syntax=docker/dockerfile:1.7
# ---------------------------------------------------------------------------
# Stage 1 — build the fat JAR with Gradle on a full JDK 17.
# ---------------------------------------------------------------------------
FROM eclipse-temurin:17-jdk-jammy AS build

WORKDIR /src

# Copy the wrapper and build scripts first. Docker caches this layer, so the
# (slow) dependency download is only repeated when the build files change,
# not on every source edit.
COPY gradlew ./
COPY gradle ./gradle
COPY build.gradle.kts settings.gradle.kts ./
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies --quiet || true

COPY src ./src

# buildFatJar comes from the io.ktor.plugin and produces a single
# self-contained JAR at build/libs/beauty-backend-all.jar
RUN ./gradlew --no-daemon buildFatJar

# ---------------------------------------------------------------------------
# Stage 2 — runtime. JRE only: no Gradle, no compiler, no source code.
# Roughly 200 MB instead of ~800 MB, and a much smaller attack surface.
# ---------------------------------------------------------------------------
FROM eclipse-temurin:17-jre-jammy AS runtime

# curl is used by the container healthcheck.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Never run application code as root inside a container.
RUN useradd --system --create-home --uid 10001 appuser

WORKDIR /app
COPY --from=build /src/build/libs/*-all.jar app.jar
RUN mkdir -p /app/uploads && chown -R appuser:appuser /app

USER appuser
EXPOSE 8080

# MaxRAMPercentage makes the JVM respect the container memory limit instead of
# sizing its heap from the host's total RAM.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseContainerSupport"

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -fsS http://127.0.0.1:8080/health || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
