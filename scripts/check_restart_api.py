"""Capture and verify small HTTP results across an externally managed backend restart."""
import argparse
import hashlib
import json
import math
from pathlib import Path
from types import SimpleNamespace
import urllib.parse
import urllib.request

from smoke_api import Client, MAX_BYTES, ROOT, require, run


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':'),
                                    ensure_ascii=True, default=str).encode('utf-8')).hexdigest()


def download_digest(api, path):
    with urllib.request.urlopen(api.base + path, timeout=api.timeout) as response:
        require(response.status == 200, 'Download failed')
        require(response.headers.get('Cache-Control') == 'no-store', 'Download lacks no-store')
        value = response.read(MAX_BYTES + 1)
        require(len(value) <= MAX_BYTES, 'Download exceeds 32 MiB restart-test limit')
        return hashlib.sha256(value).hexdigest()


def capture(api, args):
    require(not args.snapshot.exists(), 'Snapshot already exists; choose a new path')
    snapshot = {'version': 1, 'jobs': [], 'checks': [], 'downloads': []}

    def remember(path, expected=200):
        value = api.request(path, expected)
        snapshot['checks'].append({'path': path, 'http_status': expected, 'sha256': digest(value)})
        return value

    def pages(job, layer, variant=None):
        query = {'layer': layer, 'bbox': '-180,-90,180,90', 'limit': 2}
        if variant is not None:
            query['variantId'] = variant
        seen = set()
        for _ in range(100):
            page = remember(job + '/map?' + urllib.parse.urlencode(query))
            cursor = page['nextCursor']
            if cursor is None:
                return
            require(cursor not in seen, 'Repeated map cursor')
            seen.add(cursor)
            query['cursor'] = cursor
        raise RuntimeError('Map exceeds 100-page restart-test limit')

    for mode, source in [('2d', 'synthetic/two-consumers/input.geojson'),
                         ('depth', 'synthetic/depth/gas-below/input.geojson')]:
        item = {}
        run(SimpleNamespace(base_url=args.base_url, input=ROOT / 'test-data' / source, mode=mode,
                            timeout=args.timeout, request_timeout=args.request_timeout,
                            require_full_connection=True), item)
        job, invalid = item['job'], item['invalid_job']
        snapshot['jobs'].append({'mode': mode, 'job': job, 'invalid_job': invalid})
        remember(job)
        remember(invalid)
        variants = remember(job + '/variants')
        snapshot['downloads'].append({'path': job + '/result', 'sha256': download_digest(api, job + '/result')})
        pages(job, 'input')
        for variant in variants:
            identifier = variant['variant_id']
            remember(job + '/map/bounds?' + urllib.parse.urlencode({'variantId': identifier}))
            pages(job, 'result', identifier)
        for endpoint in ('/result', '/variants', '/map/bounds?variantId=variant-1', '/map?layer=input&bbox=0,0,1,1'):
            remember(invalid + endpoint, 409)
    args.snapshot.write_text(json.dumps(snapshot, indent=2) + '\n', encoding='utf-8')
    return snapshot


def verify(api, args):
    snapshot = json.loads(args.snapshot.read_text(encoding='utf-8'))
    require(snapshot['version'] == 1 and len(snapshot['jobs']) == 2, 'Unsupported or incomplete snapshot')
    require({job['mode'] for job in snapshot['jobs']} == {'2d', 'depth'}, 'Snapshot must contain both modes')
    require(snapshot['checks'] and len(snapshot['downloads']) == 2, 'Snapshot has no saved results')
    for check in snapshot['checks']:
        actual = api.request(check['path'], check['http_status'])
        require(digest(actual) == check['sha256'], 'Changed response after restart: ' + check['path'])
    for check in snapshot['downloads']:
        require(download_digest(api, check['path']) == check['sha256'],
                'Downloaded bytes changed after restart: ' + check['path'])
    return snapshot


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('phase', choices=('capture', 'verify'))
    parser.add_argument('--base-url', default='http://127.0.0.1:8080')
    parser.add_argument('--snapshot', type=Path, required=True)
    parser.add_argument('--report', type=Path)
    parser.add_argument('--timeout', type=float, default=180)
    parser.add_argument('--request-timeout', type=float, default=15)
    args = parser.parse_args()
    if any(not math.isfinite(v) or v <= 0 for v in (args.timeout, args.request_timeout)):
        parser.error('Timeouts must be finite and positive')
    report = {'phase': args.phase, 'passed': False}
    try:
        api = Client(args.base_url, args.request_timeout)
        require(api.request('/api/health')['status'] == 'UP', 'Backend is not UP')
        snapshot = capture(api, args) if args.phase == 'capture' else verify(api, args)
        report.update(passed=True, jobs=snapshot['jobs'], response_checks=len(snapshot['checks']))
        report['byte_identical_downloads' if args.phase == 'verify' else 'download_hashes'] = len(snapshot['downloads'])
    except (OSError, ValueError, RuntimeError, KeyError, TypeError) as error:
        report['error'] = f'{type(error).__name__}: {error}'
    text = json.dumps(report, indent=2, ensure_ascii=True)
    if args.report:
        args.report.write_text(text + '\n', encoding='utf-8')
    print(text)
    return 0 if report['passed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
