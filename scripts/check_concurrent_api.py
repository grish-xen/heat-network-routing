"""Check isolation of 1..50 concurrent clients against a test backend, using tiny inputs."""
import argparse
from concurrent.futures import ThreadPoolExecutor
from decimal import Decimal
import json
import math
from pathlib import Path
import tempfile
import threading
import time
from types import SimpleNamespace
import urllib.parse

from smoke_api import Client, ROOT, require, run


def shifted_input(client):
    # Change test coordinates only. IDs stay identical across jobs to expose shared-state leaks.
    data = json.loads((ROOT / 'test-data/synthetic/two-consumers/input.geojson').read_text(encoding='utf-8'))

    def shift(coordinates):
        if isinstance(coordinates[0], (int, float)):
            coordinates[0] += client * 0.005
        else:
            for child in coordinates:
                shift(child)

    for feature in data['features']:
        shift(feature['geometry']['coordinates'])
    return json.dumps(data, ensure_ascii=True).encode('utf-8')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', default='http://127.0.0.1:8080')
    parser.add_argument('--clients', type=int, default=4)
    parser.add_argument('--timeout', type=float, default=180, help='Polling deadline per job, seconds')
    parser.add_argument('--request-timeout', type=float, default=15)
    parser.add_argument('--report', type=Path, help='Optional JSON report; parent directory must exist')
    args = parser.parse_args()
    if not 1 <= args.clients <= 50:
        parser.error('--clients must be between 1 and 50')
    if any(not math.isfinite(value) or value <= 0 for value in (args.timeout, args.request_timeout)):
        parser.error('Timeouts must be finite and positive')
    api = Client(args.base_url, args.request_timeout)
    report = {'base_url': args.base_url, 'clients': args.clients, 'passed': False, 'results': []}
    began = time.monotonic()
    try:
        require(api.request('/api/health')['status'] == 'UP', 'Backend is not UP')
        with tempfile.TemporaryDirectory(prefix='heat-concurrent-') as directory:
            paths = []
            for client in range(args.clients):
                path = Path(directory) / f'client-{client}.geojson'
                path.write_bytes(shifted_input(client))
                paths.append(path)
            start = threading.Barrier(args.clients)

            def check(client):
                item = {'client': client, 'passed': False}
                options = SimpleNamespace(base_url=args.base_url, input=paths[client], timeout=args.timeout,
                                          request_timeout=args.request_timeout, require_full_connection=True)
                started = time.monotonic()
                try:
                    start.wait(timeout=30)
                    started = time.monotonic()
                    run(options, item)
                    original = json.loads(paths[client].read_bytes(), parse_float=Decimal)
                    actual = Client(args.base_url, args.request_timeout).pages(item['job'], 'input')
                    require(actual == original['features'], f'Client {client}: input data belongs to another job or was changed')
                    item['passed'] = True
                except Exception as error:
                    item['error'] = f'{type(error).__name__}: {error}'
                item['elapsed_seconds'] = round(time.monotonic() - started, 3)
                return item

            with ThreadPoolExecutor(max_workers=args.clients) as pool:
                report['results'] = list(pool.map(check, range(args.clients)))
        require(all(item['passed'] for item in report['results']), 'One or more client scenarios failed; see results')
        jobs = [item[key] for item in report['results'] for key in ('job', 'invalid_job')]
        require(len(jobs) == len(set(jobs)), 'Server reused a job ID')
        if args.clients >= 2:
            first, second = report['results'][:2]
            query = {'layer': 'input', 'bbox': '-180,-90,180,90', 'limit': 1}
            page = api.request(first['job'] + '/map?' + urllib.parse.urlencode(query))
            require(page['nextCursor'] is not None, 'Expected multiple pages for the isolation probe')
            query['cursor'] = page['nextCursor']
            error = api.request(second['job'] + '/map?' + urllib.parse.urlencode(query), 400)
            require(error['code'] == 'INVALID_CURSOR', 'A map cursor was accepted for a different job')
            report['foreign_cursor_rejected'] = True
        report['passed'] = True
    except Exception as error:
        report['error'] = f'{type(error).__name__}: {error}'
    report['elapsed_seconds'] = round(time.monotonic() - began, 3)
    durations = sorted(item['elapsed_seconds'] for item in report['results'])
    if durations:
        report['client_seconds_p50'] = durations[math.ceil(len(durations) * 0.50) - 1]
        report['client_seconds_p95'] = durations[math.ceil(len(durations) * 0.95) - 1]
    summary = {key: value for key, value in report.items() if key != 'results'}
    summary['passed_clients'] = sum(item['passed'] for item in report['results'])
    summary['failures'] = [item for item in report['results'] if not item['passed']]
    if args.report:
        args.report.write_text(json.dumps(report, indent=2, ensure_ascii=True, default=str) + '\n', encoding='utf-8')
    print(json.dumps(summary, indent=2, ensure_ascii=True, default=str))
    return 0 if report['passed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
