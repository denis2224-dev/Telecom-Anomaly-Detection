FROM maven:3.9.11-eclipse-temurin-21 AS build

WORKDIR /workspace
COPY services/incident-service/.mvn services/incident-service/.mvn
COPY services/incident-service/mvnw services/incident-service/pom.xml services/incident-service/
COPY services/incident-service/src services/incident-service/src
COPY contracts contracts
RUN --mount=type=cache,target=/root/.m2 \
    cd services/incident-service && ./mvnw -q -B package -Dmaven.test.skip=true

FROM eclipse-temurin:21-jre-jammy
RUN apt-get update \
    && apt-get install --no-install-recommends --yes curl \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /workspace/services/incident-service/target/incident-service-*.jar app.jar
COPY services/incident-service/entrypoint.sh entrypoint.sh
EXPOSE 8082
ENTRYPOINT ["sh", "/app/entrypoint.sh"]
