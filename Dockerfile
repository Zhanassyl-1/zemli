FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /app

COPY pom.xml .
COPY .mvn ./.mvn
COPY src ./src

RUN mvn -v
RUN /usr/share/maven/bin/mvn -DskipTests clean package

FROM eclipse-temurin:21-jre
WORKDIR /app

COPY --from=build /app/target/zemli-bot-1.0.0.jar app.jar

ENTRYPOINT ["java", "-jar", "app.jar"]
