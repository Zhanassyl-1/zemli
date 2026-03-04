# Stage 1: сборка приложения
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
# Копируем файлы для сборки
COPY pom.xml .
COPY src ./src
COPY src/main/resources /app/src/main/resources

# Собираем jar-файл (без тестов)
RUN mvn -DskipTests clean package

# Stage 2: финальный образ (только JRE)
FROM eclipse-temurin:21-jre
WORKDIR /app

# Копируем собранный jar из первого этапа
COPY --from=build /app/target/zemli-bot-1.0.0.jar app.jar

# Запускаем приложение
ENTRYPOINT ["java", "-jar", "app.jar"]
