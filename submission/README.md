# Результаты на конкурсном наборе

Выгрузка сервиса для `test-data/competition-corrected.geojson` (раздел 7.2 кейса, п. 4). Файлы получены через HTTP API без ручной правки: загрузка → расчёт → `GET /api/jobs/{id}/result`. Настройки по умолчанию: 8 стартов поиска, `alternative-timeout` 60 с.

| Файл | Режим | S вариантов | Подключено |
| --- | --- | --- | --- |
| [competition-2d.geojson](competition-2d.geojson) | 2D | 18,923 · 22,414 · 24,921 | 17 из 17 |
| [competition-depth.geojson](competition-depth.geojson) | С учётом глубины | 18,923 · 21,632 · 24,921 | 17 из 17 |

Независимая проверка обоих файлов — 0 нарушений:

```
python scripts/audit_result.py test-data/competition-corrected.geojson submission/competition-2d.geojson backend/src/main/resources/rules/catalog-v1.json backend/src/main/resources/rules/depth-v1.json 2d
python scripts/audit_result.py test-data/competition-corrected.geojson submission/competition-depth.geojson backend/src/main/resources/rules/catalog-v1.json backend/src/main/resources/rules/depth-v1.json depth
```

Как устроен расчёт и что означают поля — [SERVICE_OVERVIEW.md](../docs/SERVICE_OVERVIEW.md) и [OUTPUT_FORMAT.md](../docs/OUTPUT_FORMAT.md).
