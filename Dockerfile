FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /build

COPY . .

# .git is not in the build context, so the revision and (for a release) the version come in as build arguments:
#   docker build --build-arg REVISION=$(git rev-parse --short HEAD) --build-arg VERSION=1.0.0-rc1 .
ARG REVISION=unknown
ARG VERSION=
RUN ./gradlew :apps:server:shadowJar --no-daemon -q -Prevision="$REVISION" ${VERSION:+-PreleaseVersion="$VERSION"}

FROM eclipse-temurin:21-jre-alpine

# The server runs as an unprivileged user. Its data lives in /app/data, which must be writable by uid 10001:
# a named volume handles that by itself, a bind mount needs `chown 10001:10001 <dir>` on the host.
RUN addgroup -S -g 10001 chaptervault \
    && adduser -S -u 10001 -G chaptervault -h /app chaptervault \
    && mkdir -p /app/data \
    && chown -R chaptervault:chaptervault /app

WORKDIR /app

# server-*.jar is the fat jar; the plain server.jar next to it has no dash and is not matched
COPY --from=builder --chown=chaptervault:chaptervault /build/apps/server/build/libs/server-*.jar app.jar

USER chaptervault

EXPOSE 8080

ENV CHAPTERVAULT_DATA_DIR=/app/data
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"

VOLUME ["/app/data"]

HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
    CMD wget -q -O /dev/null "http://127.0.0.1:${CHAPTERVAULT_PORT:-8080}/health" || exit 1

# exec: java becomes PID 1 and receives SIGTERM directly, so `docker stop` shuts the server down cleanly
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
