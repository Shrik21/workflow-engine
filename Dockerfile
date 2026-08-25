# syntax=docker/dockerfile:1

# ---------------------------------------------------------------------------
# Build stage
#
# The whole reactor is built here, not just the engine: the SDK has to be installed
# into the local repository before the core and the sample plugins can compile
# against it. The plugin JARs are produced too, so the image ships with samples an
# operator can upload immediately to verify a deployment.
# ---------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /build

# Copy the POMs first and resolve dependencies. Docker caches this layer, so an
# ordinary source change does not re-download the world.
COPY pom.xml ./
COPY workflow-plugin-sdk/pom.xml workflow-plugin-sdk/
COPY workflow-engine-core/pom.xml workflow-engine-core/
# The registry is a module of the same reactor, so its POM and sources must be present even though this
# image does not ship it. Maven constructs the reactor from every module the parent declares and fails on a
# missing directory; omitting it here made this image unbuildable the moment plugin-server was added.
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

# Unit tests run in the image build; integration tests are tagged and excluded
# because they need their own Docker daemon.
RUN mvn -B clean install

# ---------------------------------------------------------------------------
# Runtime stage
# ---------------------------------------------------------------------------
FROM eclipse-temurin:17-jre-alpine AS runtime

# A non-root user, because plugin code runs with this process's privileges. Class
# loader isolation does not constrain filesystem access, so the operating system
# has to.
RUN addgroup -S workflow && adduser -S workflow -G workflow

WORKDIR /app

COPY --from=build /build/workflow-engine-core/target/workflow-engine.jar app.jar

# Sample plugins, for verifying a deployment by uploading one.
COPY --from=build /build/plugins/sendgrid-plugin/target/sendgrid-plugin-1.0.0.jar sample-plugins/
COPY --from=build /build/plugins/restapi-plugin/target/restapi-plugin-1.0.0.jar sample-plugins/
COPY --from=build /build/plugins/slack-plugin/target/slack-plugin-1.0.0.jar sample-plugins/

# Plugin JARs are staged here from GridFS before being loaded. Mounting a volume
# avoids re-downloading them on every restart.
RUN mkdir -p /var/lib/workflow-engine/plugins && \
    chown -R workflow:workflow /app /var/lib/workflow-engine

USER workflow

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseContainerSupport -Djava.security.egd=file:/dev/./urandom" \
    PLUGIN_WORKSPACE_DIR=/var/lib/workflow-engine/plugins \
    SERVER_PORT=8080

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=45s --retries=3 \
    CMD wget -qO- http://127.0.0.1:8080/actuator/health | grep -q '"status":"UP"' || exit 1

# exec form via sh so JAVA_OPTS is expanded, with exec so the JVM is PID 1 and
# receives SIGTERM directly. Graceful shutdown depends on that signal arriving.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
