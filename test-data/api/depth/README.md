# Ответы DEPTH для разработки интерфейса

**Backend пока отклоняет mode=depth.** Эти файлы — fixtures, а не реальные ответы сервера. Не подставлять их автоматически при ошибке HTTP.

Согласованы с [gas-below](../../synthetic/depth/gas-below/metric-case.json) и [контрактом](../../../docs/DEPTH_CONTRACT.md).

| Файл | Использование |
| --- | --- |
| job-queued.json | Будущий POST /api/jobs, HTTP 202; Location — /api/jobs/{jobId} |
| job-running.json | Опрос, HTTP 200, CALCULATING |
| job-succeeded.json | HTTP 200, SUCCEEDED/DONE |
| variants.json | GET /api/jobs/{jobId}/variants, HTTP 200 |
| map-input.json | GET map, layer=input; одна страница |
| map-result.json | GET map, layer=result, variantId=variant-1; одна страница |
| map-bounds.json | GET map/bounds?variantId=variant-1; объединённые границы |
| job-failed.json | Другая иллюстративная задача: нет принятого кандидата; HTTP 200 со статусом FAILED |
| unsupported-mode.json | Текущий POST mode=depth: HTTP 422; задача не создаётся |

Скачивание — [expected.geojson](../../synthetic/depth/gas-below/expected.geojson), Content-Type application/geo+json. map-result не содержит variant_summary. nextCursor=null означает конец; клиент продолжает поддерживать пагинацию и отмену устаревших запросов.

Размеры сцены — [visualization-rules.v1.json](../../../contracts/visualization-rules.v1.json), импортируемый ресурс сборки. Режим берётся из Job; наличие ресурса не включает режим backend.

№4 дополнительно тестирует неравные длины частей, многостраничность, большие числовые ID, ветвление, null-глубины 2D и ошибки сети.
