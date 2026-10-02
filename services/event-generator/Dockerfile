FROM maven:3.9.11-eclipse-temurin-21 AS build

WORKDIR /workspace
COPY pom.xml mvnw ./
COPY .mvn .mvn
COPY services/streaming-support/pom.xml services/streaming-support/pom.xml
COPY services/event-generator/pom.xml services/event-generator/pom.xml
COPY services/processor/pom.xml services/processor/pom.xml
COPY services/streaming-support/src services/streaming-support/src
COPY services/event-generator/src services/event-generator/src
COPY contracts contracts
RUN --mount=type=cache,target=/root/.m2 ./mvnw -q -B -pl services/event-generator -am package -Dmaven.test.skip=true

FROM eclipse-temurin:21-jre-jammy
RUN apt-get update \
    && apt-get install --no-install-recommends --yes curl \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /workspace/services/event-generator/target/event-generator-0.3.0-SNAPSHOT.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
