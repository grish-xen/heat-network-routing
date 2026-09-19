# Выходной GeoJSON — краткий контракт

Полная спецификация — раздел 7 `reference/current-technical-appendix.docx`. На один режим создаётся один `FeatureCollection` в WGS 84. Все варианты находятся в `features` и различаются `variant_id`. Не добавлять объекты старой модели (`tie_in`, реконструкция сети или камер).

| `object_type` | Геометрия | Основные обязательные поля сверх `id`, `object_type`, `variant_id` |
| --- | --- | --- |
| `heat_network` | LineString | `start_node_id`, `end_node_id`, `flow_tph`, `diameter`, `length`, `laying_method`, `depth_start`, `depth_end`, `cost` |
| `heat_chamber` | Point | `diameter`, `cost` |
| `technical_node` | Point | нет |
| `variant_summary` | null | `rank`, `construction_cost`, `chamber_construction_cost`, `existing_chamber_tie_in_count`, `existing_chamber_tie_in_cost`, `unconnected_penalty`, `calculated_cost`, `new_network_length`, `score`, `unconnected_oks_ids` |

`start_node_id` и `end_node_id` ссылаются на точки подключения из входа, существующие камеры из входа, новые камеры или технические узлы результата. Они совпадают с концами геометрии линии. Направление записи координат не означает направление потока. В базовом 2D-режиме оба поля глубины равны `null`.

Контрольные равенства для каждой сводки:

- `construction_cost = Σ(cost новых heat_network) + chamber_construction_cost + existing_chamber_tie_in_cost`.
- `calculated_cost = construction_cost + unconnected_penalty`.
- `new_network_length = Σ(length новых heat_network)`.
- `score = 0.7 × calculated_cost / 25_000_000 + 0.3 × new_network_length / 100`.

ID входных точек и существующих камер в ссылках сохраняют свой исходный JSON-тип. `unconnected_oks_ids` содержит ID входных `oks_connection_point`, сохраняя тип числа/строки.
