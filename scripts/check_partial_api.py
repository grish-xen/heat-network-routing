"""Verify honest partial results in 2D and DEPTH against a real backend; Python 3.9+."""
import argparse
from decimal import Decimal
import json
import math
from pathlib import Path
import tempfile
from types import SimpleNamespace

from smoke_api import Client, ROOT, require, run


def blocked_input():
    data = json.loads((ROOT / 'test-data/synthetic/two-consumers/input.geojson').read_text(encoding='utf-8'))
    target = next(f for f in data['features'] if f['properties'].get('id') == '2')
    x, y = target['geometry']['coordinates']
    # A small forbidden area encloses one consumer, away from the other route.
    # Only generated test data uses these offsets; routing has no special case for them.
    dx, dy = 0.00004, 0.00003
    ring = [[x-dx, y-dy], [x+dx, y-dy], [x+dx, y+dy], [x-dx, y+dy], [x-dx, y-dy]]
    data['features'].append({'type': 'Feature', 'properties': {
        'id': 'blocked-consumer-area', 'object_type': 'restriction', 'restriction_type': 'prohibited_site'},
        'geometry': {'type': 'Polygon', 'coordinates': [ring]}})
    return data, target


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', default='http://127.0.0.1:8080')
    parser.add_argument('--timeout', type=float, default=180)
    parser.add_argument('--request-timeout', type=float, default=15)
    parser.add_argument('--report', type=Path)
    args = parser.parse_args()
    if any(not math.isfinite(v) or v <= 0 for v in (args.timeout, args.request_timeout)):
        parser.error('Timeouts must be finite and positive')
    report = {'base_url': args.base_url, 'passed': False, 'results': []}
    try:
        data, blocked = blocked_input()
        rules = json.loads((ROOT / 'backend/src/main/resources/rules/catalog-v1.json').read_text(encoding='utf-8'), parse_float=Decimal)
        expected_penalty = Decimal(rules['unconnectedFixedRub']) + Decimal(rules['unconnectedPerTphRub']) * blocked['properties']['flow_tph']
        with tempfile.TemporaryDirectory(prefix='heat-partial-') as directory:
            source = Path(directory) / 'blocked.geojson'
            source.write_text(json.dumps(data), encoding='utf-8')
            for mode in ('2d', 'depth'):
                item = {'mode': mode, 'passed': False}
                report['results'].append(item)
                options = SimpleNamespace(base_url=args.base_url, input=source, mode=mode,
                                          timeout=args.timeout, request_timeout=args.request_timeout,
                                          require_full_connection=False)
                run(options, item)
                require(not item['full_connection'], f'{mode}: forbidden area did not prevent connection')
                require(any(d['code'] == 'ROUTE_NOT_FOUND' for d in item['diagnostics']),
                        f'{mode}: partial SUCCEEDED has no ROUTE_NOT_FOUND diagnostic')
                api = Client(args.base_url, args.request_timeout)
                variants = api.request(item['job'] + '/variants')
                exported = api.request(item['job'] + '/result')['features']
                for variant in variants:
                    require(variant['unconnected_oks_ids'] == [blocked['properties']['id']],
                            f'{mode}: unexpected missing consumer or changed ID type')
                    require(variant['unconnected_penalty'] == expected_penalty, f'{mode}: wrong penalty')
                    require(variant['calculated_cost'] == variant['construction_cost'] + expected_penalty,
                            f'{mode}: penalty not included in calculated cost')
                    edges = [f['properties'] for f in exported if f['properties']['object_type'] == 'heat_network'
                             and f['properties']['variant_id'] == variant['variant_id']]
                    require(any(p['end_node_id'] == 1 for p in edges), f'{mode}: reachable consumer was lost')
                    require(not any(p['end_node_id'] == '2' for p in edges), f'{mode}: blocked consumer has a route')
                item['expected_penalty'] = str(expected_penalty)
                item['passed'] = True
        report['passed'] = True
    except (OSError, ValueError, RuntimeError, KeyError, TypeError) as error:
        report['error'] = f'{type(error).__name__}: {error}'
    text = json.dumps(report, indent=2, ensure_ascii=True, default=str)
    if args.report:
        args.report.write_text(text + '\n', encoding='utf-8')
    print(text)
    return 0 if report['passed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
