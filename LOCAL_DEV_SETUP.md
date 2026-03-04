# Локальный запуск без PostgreSQL

Проект в `dev` использует H2 in-memory, поэтому PostgreSQL не нужен.

## Что уже настроено

- Профиль по умолчанию: `dev`
- БД: `jdbc:h2:mem:zemli_bot;DB_CLOSE_DELAY=-1;MODE=PostgreSQL`
- H2 console: `/h2-console`

## Запуск

```bash
mvn spring-boot:run
```     
или

```bash
./mvnw spring-boot:run
```

При старте выводится:

```text
====================================
✅ ЗАПУСК В DEV РЕЖИМЕ
✅ Используется H2 база в памяти
✅ Данные НЕ сохранятся после перезапуска
====================================
```
