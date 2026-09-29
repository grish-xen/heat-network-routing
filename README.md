# Heat Network Routing

Стартовый репозиторий команды ЛЦТ 2026: сервис подключения точек ОКС к тепловой сети. Контракты команды — версия 1.0.

**Работает серверная цепочка загрузка → проверка → поиск → расчёт → экспорт.** [API задач](docs/JOB_API.md) выдаёт статусы, до трёх сводок вариантов и скачивание GeoJSON. Результаты сохраняются на диск и переживают перезапуск. [Frontend](frontend/README.md) имеет демонстрационный и HTTP-режим; [API карты](docs/MAP_API.md) выдаёт исходные и рассчитанные объекты по bbox со страницами.

Реализован [инженерный расчёт](docs/CALCULATION_MODULE.md): расходы, ДУ, специальные проходы, камеры, врезки, штрафы и score. Настоящая HTTP-цепочка проверяется на синтетическом примере. Это не подтверждение готовности к предельной нагрузке 3 ГиБ / 50 пользователей.

[Экспорт](docs/OUTPUT_MODULE.md) пишет WGS 84, согласованные ссылки и сводки с ограничением 500 МиБ. Скачивание передаётся потоком, сводки API берутся из того же результата. Следующие этапы — проверка реальной карты в браузере и нагрузочные испытания.

## Начало работы

Следующий этап команды: [план режима глубины и 3D](docs/DEPTH_DEVELOPMENT_PLAN.md) — распределение задач, стыки модулей и критерии готовности. Серверный DEPTH интегрирован; проверенные сценарии и ограничения — [отчёт](docs/DEPTH_INTEGRATION.md).

Для параллельной работы подготовлены [контракт глубины](docs/DEPTH_CONTRACT.md), [расчётные примеры](test-data/synthetic/depth/README.md) и [ответы API для интерфейса](test-data/api/depth/README.md).

1. Прочитайте [инструкцию для четырёх участников](docs/TEAM_WORKFLOW.md) и выберите свою задачу.
2. Прочитайте [правила](docs/RULES.md), [модель](docs/DATA_MODEL.md) и [контракты модулей](docs/MODULE_CONTRACTS.md).
3. Запустите проверки из корня проекта: `python scripts/check_repository.py` (Python 3.9+) и `mvn -f backend/pom.xml verify` (JDK 11, Maven 3.9.9).
4. Запустите backend: `mvn -f backend/pom.xml spring-boot:run`. Откройте `http://localhost:8080/api/health` и `http://localhost:8080/swagger-ui.html`.
5. Для интерфейса установите Node.js 22.12+, затем выполните `npm --prefix frontend ci` и `npm --prefix frontend run dev`. По умолчанию development использует честно помеченные fixture-данные; для backend задайте `VITE_API_MODE=http`.

Вместо локальной Java можно использовать Docker: `docker-compose up --build` (целевая версия по ТЗ — 1.29.2). При установленном Compose v2 эквивалентная команда — `docker compose up --build`. Compose запускает backend и PostgreSQL 16. Backend пока не использует БД; переменные подключения подготовлены для участника 1. Пароль по умолчанию предназначен для локальной разработки; свой задаётся через `POSTGRES_PASSWORD` в игнорируемом `.env`.

Проверка реального Docker-образа и сохранности 2D/DEPTH после пересоздания контейнеров — [DEPLOYMENT_CHECK.md](docs/DEPLOYMENT_CHECK.md). Workflow запускает Compose на Ubuntu 22.04; локальный HTTP-сценарий можно повторить также с обычным JAR.

## Где что находится

| Путь | Назначение |
| --- | --- |
| `backend/src/main/java/ru/hackathon/heatnetwork/` | Одна программа Spring Boot; модули — Java-пакеты |
| `backend/src/main/resources/rules/catalog-v1.json` | Общие числа из актуального приложения, без тарифов реконструкции |
| `frontend/` | React/Vite-интерфейс, тесты и [инструкция запуска](frontend/README.md) |
| `contracts/openapi.json` | Точные будущие HTTP-операции и форматы; помечено, что ещё не реализовано |
| `test-data/synthetic/two-consumers/` | Согласованные вход, метрический граф и ожидаемый результат |
| `test-data/api/` | Ответы для разработки frontend на заглушках |
| `test-data/competition-corrected.geojson` | Актуальный конкурсный набор |
| `docs/reference/` | Оригиналы документов и текст новых DOCX для чтения человеком и ИИ |
| `.github/workflows/verify.yml` | Проверки после push и в Pull Request |

Первичны [актуальное приложение](docs/reference/current-technical-appendix.md) и [разъяснения](docs/reference/clarifications.md); соответствующие DOCX — оригиналы. Старый PDF сохранён для общего ТЗ. При противоречии правил расчёта применяется новое приложение.

Общий репозиторий: https://github.com/grish-xen/heat-network-routing, основная ветка — `main`. Подготовительные `.build-tools` в родительской папке не относятся к репозиторию.
