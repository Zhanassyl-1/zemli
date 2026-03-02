# Stage 1: сборка приложения
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

# Копируем файлы для сборки
COPY pom.xml .
COPY src ./src

# Собираем jar-файл (без тестов)
RUN mvn -DskipTests package

# Stage 2: финальный образ (только JRE, без JDK)
FROM eclipse-temurin:21-jre
WORKDIR /app

# Копируем собранный jar из первого этапа
COPY --from=build /app/target/zemli-bot-1.0.0.jar app.jar

# Запускаем приложение
ENTRYPOINT ["java", "-jar", "app.jar"]