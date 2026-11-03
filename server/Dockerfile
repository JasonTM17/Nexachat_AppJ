# NexaChat server — multi-stage image.
#   Stage 1: Maven build (JDK 17)
#   Stage 2: slim JRE 17 runtime, non-root user
#
# Build:   docker compose build app
# Health:  GET /actuator/health is permitAll (SecurityConfig) and exposed via
#          management.endpoints.web.exposure.include=health in application.yml.

# ---------------------------------------------------------------- build -----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# Cache dependencies in their own layer: pom.xml changes far less often than src/.
COPY pom.xml .
RUN mvn -q -B -DskipTests dependency:go-offline

COPY src ./src
RUN mvn -q -B -DskipTests package

# -------------------------------------------------------------- runtime -----
FROM eclipse-temurin:17-jre

# curl for the container HEALTHCHECK (JRE image ships without it).
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Non-root runtime user.
RUN groupadd --system nexachat && useradd --system --gid nexachat nexachat

WORKDIR /app

# The Spring Boot repackaged jar is the only target/*.jar (*.original excluded).
COPY --from=build /build/target/nexachat-server-*.jar /app/app.jar

# Upload storage lives on a named volume (compose: app-uploads -> /app/data).
RUN mkdir -p /app/data && chown -R nexachat:nexachat /app

USER nexachat

EXPOSE 8080

# Extra JVM flags (heap, GC, …) without rebuilding the image.
ENV JAVA_OPTS=""

HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
  CMD curl -fsS http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
