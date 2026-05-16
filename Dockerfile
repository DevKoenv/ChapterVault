FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

COPY apps/server/build/libs/server.jar app.jar
COPY config/ config/

RUN mkdir -p data downloads

EXPOSE 8080

ENV JAVA_OPTS=""

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
