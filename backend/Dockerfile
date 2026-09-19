FROM maven:3.9.9-eclipse-temurin-11 AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN mvn --batch-mode --no-transfer-progress verify

FROM eclipse-temurin:11-jre-jammy
WORKDIR /app
COPY --from=build /build/target/heat-network-routing-0.1.0-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
