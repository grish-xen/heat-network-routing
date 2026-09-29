# Backend

Одно Spring Boot-приложение. Общие контракты — [модель](../docs/DATA_MODEL.md) и [интерфейсы](../docs/MODULE_CONTRACTS.md). Исходники общих типов лежат в `model`, прикладные интерфейсы — в пакетах их владельцев.

JDK 11, Maven 3.9.9. Из корня репозитория:

```sh
mvn -f backend/pom.xml verify
mvn -f backend/pom.xml spring-boot:run
```

Доступны `/api/health`, `/v3/api-docs`, `/swagger-ui.html`, `POST /api/jobs` и `GET /api/jobs/{jobId}`. Сервер пока сообщает `implementation: skeleton`: расчётные модули ещё не подключены. [Загрузка и очередь](../docs/JOB_API.md) вызывают настоящий InputParser; корректный файл заканчивается `FAILED / PROCESSING_UNAVAILABLE`, ошибочный — диагностикой содержимого. Swagger показывает реализованные операции, `contracts/openapi.json` также содержит planned-операции карты и результатов.

JTS 1.19.0 задаёт единую модель геометрии для модулей. Каталог правил читается из classpath `/rules/catalog-v1.json`. [Модуль input](../docs/INPUT_MODULE.md) реализует чтение, валидацию и проекцию Proj4J 1.4.1 с временным дисковым хранилищем. Пространственный индекс для ускорения выборок и расчёт — следующие задачи команды.

[Модуль output](../docs/OUTPUT_MODULE.md) уже предоставляет Spring bean `ResultExporter` для потокового 2D GeoJSON. Он принимает рассчитанные варианты; подключение координатора и HTTP-выдача результата остаются следующим этапом.

Dockerfile собирает и проверяет приложение на Java 11. Compose поднимает PostgreSQL 16 и передаёт `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`. С ними статусы задач и сводки вариантов хранятся в PostgreSQL; схема `src/main/resources/db/schema.sql` создаётся при запуске. Без них backend работает с JSON-файлами — для локальной разработки и тестов.
