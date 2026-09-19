# Heat Network Routing

Стартовый репозиторий команды ЛЦТ 2026: сервис подключения точек ОКС к тепловой сети. Контракты команды — версия 1.0.

**Сейчас работают запуск backend, `/api/health`, Swagger и [чтение GeoJSON в Dataset](docs/INPUT_MODULE.md).** Импорт проверяет объекты, преобразует координаты в метры и создаёт временное дисковое хранилище для алгоритмов. Есть числовой справочник, эталон с двумя потребителями, спецификация HTTP API и CI. Поиск трасс, инженерный расчёт, HTTP-загрузка файлов, хранилище задач и интерфейс карты ещё предстоит реализовать.

## Начало работы

1. Прочитайте [инструкцию для четырёх участников](docs/TEAM_WORKFLOW.md) и выберите свою задачу.
2. Прочитайте [правила](docs/RULES.md), [модель](docs/DATA_MODEL.md) и [контракты модулей](docs/MODULE_CONTRACTS.md).
3. Запустите проверки из корня проекта: `python scripts/check_repository.py` (Python 3.9+) и `mvn -f backend/pom.xml verify` (JDK 11, Maven 3.9.9).
4. Запустите backend: `mvn -f backend/pom.xml spring-boot:run`. Откройте `http://localhost:8080/api/health` и `http://localhost:8080/swagger-ui.html`.

Вместо локальной Java можно использовать Docker: `docker-compose up --build` (целевая версия по ТЗ — 1.29.2). При установленном Compose v2 эквивалентная команда — `docker compose up --build`. Compose запускает backend и PostgreSQL 16. Backend пока не использует БД; переменные подключения подготовлены для участника 1. Пароль по умолчанию предназначен для локальной разработки; свой задаётся через `POSTGRES_PASSWORD` в игнорируемом `.env`.

## Где что находится

| Путь | Назначение |
| --- | --- |
| `backend/src/main/java/ru/hackathon/heatnetwork/` | Одна программа Spring Boot; модули — Java-пакеты |
| `backend/src/main/resources/rules/catalog-v1.json` | Общие числа из актуального приложения, без тарифов реконструкции |
| `frontend/` | Зона работы автора интерфейса; фреймворк пока не навязан |
| `contracts/openapi.json` | Точные будущие HTTP-операции и форматы; помечено, что ещё не реализовано |
| `test-data/synthetic/two-consumers/` | Согласованные вход, метрический граф и ожидаемый результат |
| `test-data/api/` | Ответы для разработки frontend на заглушках |
| `test-data/competition-corrected.geojson` | Актуальный конкурсный набор |
| `docs/reference/` | Оригиналы документов и текст новых DOCX для чтения человеком и ИИ |
| `.github/workflows/verify.yml` | Проверки после push и в Pull Request |

Первичны [актуальное приложение](docs/reference/current-technical-appendix.md) и [разъяснения](docs/reference/clarifications.md); соответствующие DOCX — оригиналы. Старый PDF сохранён для общего ТЗ. При противоречии правил расчёта применяется новое приложение.

Общий репозиторий: https://github.com/grish-xen/heat-network-routing, основная ветка — `main`. Подготовительные `.build-tools` в родительской папке не относятся к репозиторию.
