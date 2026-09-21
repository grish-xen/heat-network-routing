# Frontend

Интерфейс участника 4: React 19, TypeScript, Vite, TanStack Query и MapLibre GL. Сценарий включает загрузку GeoJSON, статус асинхронной задачи, карту, сравнение вариантов и потоковое скачивание результата.

## Требования и запуск

Нужен Node.js `>=22.12` и npm. Из корня репозитория:

```bash
npm --prefix frontend ci
npm --prefix frontend run dev
```

Vite откроет локальный адрес из терминала. В development без переменной окружения включается `fixture`: в шапке явно показан бейдж «Демонстрационные данные», а стадии задачи проходят автоматически. Пример конфигурации лежит в `.env.example`.

```dotenv
VITE_API_MODE=fixture
```

Доступны только два режима:

- `fixture` — автономная демонстрация на файлах из `../test-data`, без копий данных во frontend;
- `http` — реальные запросы `/api/*`; dev-сервер проксирует их на `http://localhost:8080`.

Production-сборка требует явно задать `VITE_API_MODE=fixture` или `VITE_API_MODE=http`, иначе приложение покажет понятную конфигурационную ошибку при запуске.

## Проверки

```bash
npm --prefix frontend test
npm --prefix frontend run lint
npm --prefix frontend run build -- --mode development
```

Тесты проверяют lossless-разбор больших числовых ID, HTTP multipart и ошибки, fixture-пагинацию, polling, варианты, bbox/cursor карты, lifecycle MapLibre и fallback без WebGL2.

## Интеграция с backend

Контракт находится в `../contracts/openapi.json`. Сейчас backend реализует `/api/health`, `POST /api/jobs` и `GET /api/jobs/{jobId}`. Валидная загрузка в HTTP-режиме ожидаемо заканчивается `FAILED / PROCESSING_UNAVAILABLE`: это показывается как состояние интеграции, а не заменяется демонстрационным результатом.

Чтобы HTTP-режим показал успешный результат, участнику 1 нужно подключить отмеченные в OpenAPI как planned операции:

- `GET /api/jobs/{jobId}/variants`;
- `GET /api/jobs/{jobId}/map` с EPSG:4326 bbox, limit и cursor;
- `GET /api/jobs/{jobId}/result` как потоковый GeoJSON attachment.

`HttpHeatNetworkApi` — единственная граница реальных запросов. ID типа JSON string/number сохраняются посимвольно и различаются по исходному типу; не преобразуйте их в JavaScript `number`. Download реализован обычной ссылкой с `download`, поэтому большой файл не буферизуется кодом интерфейса.

MapLibre использует отдельный worker asset. При отсутствии WebGL2 интерфейс сохраняет выбор вариантов, метрики и скачивание результата.
