# syntax=docker/dockerfile:1.7

FROM --platform=$BUILDPLATFORM maven:3.9.9-eclipse-temurin-17 AS build

WORKDIR /workspace

COPY pom.xml ./
COPY .mvn .mvn
RUN mvn -q -DskipTests dependency:go-offline

COPY src src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:17-jre

WORKDIR /app

ENV SERVER_PORT=8080
ENV APP_CONFIG_PATH=/app/data/fluss-manager-web.json
ENV JAVA_OPTS="--add-opens=java.base/java.nio=ALL-UNNAMED --add-opens=java.base/sun.nio.ch=ALL-UNNAMED"

COPY --from=build /workspace/target/fluss-manager-web-*.jar /app/fluss-manager-web.jar

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/fluss-manager-web.jar"]
