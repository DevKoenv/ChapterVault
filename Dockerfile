FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /build

COPY . .

RUN ./gradlew :apps:server:fatJar --no-daemon -q

FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

COPY --from=builder /build/apps/server/build/libs/server-fat.jar app.jar
COPY config/ config/

RUN mkdir -p data downloads

EXPOSE 8080

ENV JAVA_OPTS=""

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
