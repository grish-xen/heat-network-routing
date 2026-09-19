# Backend

Одно Spring Boot-приложение. Общие контракты — [модель](../docs/DATA_MODEL.md) и [интерфейсы](../docs/MODULE_CONTRACTS.md). Исходники общих типов лежат в `model`, прикладные интерфейсы — в пакетах их владельцев.

JDK 11, Maven 3.9.9. Из корня репозитория:

```sh
mvn -f backend/pom.xml verify
mvn -f backend/pom.xml spring-boot:run
```

Доступны `/api/health`, `/v3/api-docs`, `/swagger-ui.html`, `POST /api/jobs` и `GET /api/jobs/{jobId}`. Сервер пока сообщает `implementation: skeleton`: расчётные модули ещё не подключены. [Загрузка и очередь](../docs/JOB_API.md) вызывают настоящий InputParser; корректный файл заканчивается `FAILED / PROCESSING_UNAVAILABLE`, ошибочный — диагностикой содержимого. Swagger показывает реализованные операции, `contracts/openapi.json` также содержит planned-операции карты и результатов.

JTS 1.19.0 задаёт единую модель геометрии для модулей. Каталог правил читается из classpath `/rules/catalog-v1.json`. [Модуль input](../docs/INPUT_MODULE.md) реализует чтение, валидацию и проекцию Proj4J 1.4.1 с временным дисковым хранилищем. Пространственный индекс для ускорения выборок, расчёт и экспорт — следующие задачи команды.

Dockerfile собирает и проверяет приложение на Java 11. Compose поднимает PostgreSQL 16 как заготовку инфраструктуры. `DB_URL`, `DB_USER`, `DB_PASSWORD` пока не читаются приложением; участник 1 подключит их вместе с JDBC и миграциями.
