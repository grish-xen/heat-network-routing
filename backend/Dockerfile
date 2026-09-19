FROM maven:3.9.9-eclipse-temurin-11 AS build
WORKDIR /build
COPY backend/pom.xml backend/pom.xml
COPY backend/src backend/src
COPY test-data test-data
COPY contracts contracts
RUN mvn --batch-mode --no-transfer-progress -f backend/pom.xml verify

FROM eclipse-temurin:11-jre-jammy
WORKDIR /app
COPY --from=build /build/backend/target/heat-network-routing-0.1.0-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
