FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /build

COPY . .

RUN ./gradlew :apps:server:fatJar --no-daemon -q

FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

COPY --from=builder /build/apps/server/build/libs/server-fat.jar app.jar
COPY config/ config/

RUN mkdir -p data/db data/library data/thumbnails

EXPOSE 8080

ENV JAVA_OPTS=""
ENV CHAPTERVAULT_DATA_DIR=/app/data

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
