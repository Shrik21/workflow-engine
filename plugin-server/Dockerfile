# syntax=docker/dockerfile:1

# ---------------------------------------------------------------------------
# The plugin registry.
#
# Built from the repository root, not from this directory: the registry compiles
# against workflow-plugin-sdk, which is a sibling module. Compose therefore sets
# `context: .` and points at this file.
#
# The whole reactor is built, as in the engine's image, because Maven constructs
# its reactor from every module the parent declares and a partial copy fails on
# the first missing one.
# ---------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /build

# POMs first, so an ordinary source change does not re-resolve every dependency.
COPY pom.xml ./
COPY workflow-plugin-sdk/pom.xml workflow-plugin-sdk/
COPY workflow-engine-core/pom.xml workflow-engine-core/
COPY plugin-server/pom.xml plugin-server/
COPY plugins/sendgrid-plugin/pom.xml plugins/sendgrid-plugin/
COPY plugins/restapi-plugin/pom.xml plugins/restapi-plugin/
COPY plugins/slack-plugin/pom.xml plugins/slack-plugin/
RUN mvn -B -q dependency:go-offline -DskipTests || true

COPY workflow-plugin-sdk/src workflow-plugin-sdk/src
COPY workflow-engine-core/src workflow-engine-core/src
COPY plugin-server/src plugin-server/src
COPY plugins/sendgrid-plugin/src plugins/sendgrid-plugin/src
COPY plugins/restapi-plugin/src plugins/restapi-plugin/src
COPY plugins/slack-plugin/src plugins/slack-plugin/src

# Unit tests run here; integration tests need their own Docker daemon and are excluded.
RUN mvn -B clean install -DskipITs

# ---------------------------------------------------------------------------
# Runtime
# ---------------------------------------------------------------------------
FROM eclipse-temurin:17-jre-alpine AS runtime

# A non-root user. This service never loads or executes a plugin archive — it only
# stores and serves them — but it holds every archive in the estate, so the blast
# radius of a compromise is the whole plugin supply chain.
RUN addgroup -S registry && adduser -S registry -G registry

WORKDIR /app

COPY --from=build /build/plugin-server/target/plugin-server.jar app.jar

# The sample plugins, so a fresh registry can be seeded to verify a deployment.
COPY --from=build /build/plugins/sendgrid-plugin/target/sendgrid-plugin-1.0.0.jar sample-plugins/
COPY --from=build /build/plugins/restapi-plugin/target/restapi-plugin-1.0.0.jar sample-plugins/
COPY --from=build /build/plugins/slack-plugin/target/slack-plugin-1.0.0.jar sample-plugins/

RUN chown -R registry:registry /app

USER registry

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseContainerSupport -Djava.security.egd=file:/dev/./urandom" \
    SERVER_PORT=8085

EXPOSE 8085

HEALTHCHECK --interval=30s --timeout=5s --start-period=45s --retries=3 \
    CMD wget -qO- http://127.0.0.1:8085/actuator/health | grep -q '"status":"UP"' || exit 1

# exec via sh so JAVA_OPTS expands, with exec so the JVM is PID 1 and receives
# SIGTERM directly.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
