# Build and run a layered Spring Boot image (linux/amd64 digests pinned).
FROM maven:3.10.0-eclipse-temurin-21-noble@sha256:c146cec15b48eabce469674b6677d89cab582cbd9de48194d0800464b528644a AS build
WORKDIR /build

# Resolve dependencies before copying source so ordinary code edits reuse this layer.
COPY pom.xml ./
RUN mvn -B -DskipTests dependency:go-offline

COPY src ./src
RUN mvn -B -DskipTests package
RUN set -eu; \
    set -- target/*.jar; \
    test -f "$1"; \
    cp "$1" application.jar; \
    java -Djarmode=tools -jar application.jar extract --layers --destination extracted

FROM eclipse-temurin:21-jre-noble@sha256:db724162e4ff5f71fa0a2815026314d9e5e0e9a139f49be1d4717e6930e93341 AS runtime

# curl is used only by the container health check.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

RUN groupadd --system --gid 10001 nexachat \
    && useradd --system --uid 10001 --gid nexachat --no-create-home \
       --shell /usr/sbin/nologin nexachat

WORKDIR /app

# Copy stable layers first so application-only changes do not invalidate dependencies.
COPY --from=build --chown=nexachat:nexachat /build/extracted/dependencies/ ./
COPY --from=build --chown=nexachat:nexachat /build/extracted/spring-boot-loader/ ./
COPY --from=build --chown=nexachat:nexachat /build/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=nexachat:nexachat /build/extracted/application/ ./

RUN mkdir -p /app/data/uploads && chown -R nexachat:nexachat /app

USER nexachat

EXPOSE 8080

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom"

HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
    CMD curl -fsS http://localhost:8080/actuator/health/liveness || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/application.jar"]
