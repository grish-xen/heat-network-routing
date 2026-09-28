"""Exercise a running backend with small real inputs; Python 3.9+, no dependencies."""
import argparse
from decimal import Decimal
import json
import math
from pathlib import Path
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]
MAX_BYTES = 32 * 1024 * 1024


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


class Client:
    def __init__(self, base, timeout):
        self.base = base.rstrip('/')
        self.timeout = timeout

    def request(self, path, expected=200, data=None, headers=None):
        request = urllib.request.Request(self.base + path, data=data, headers=headers or {})
        try:
            response = urllib.request.urlopen(request, timeout=self.timeout)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            raw = response.read(MAX_BYTES + 1)
            require(len(raw) <= MAX_BYTES, 'Response exceeds 32 MiB smoke-test limit')
            body = json.loads(raw, parse_float=Decimal)
            require(response.status == expected, f'{path}: HTTP {response.status}, expected {expected}: {body}')
            if path.startswith('/api/jobs/') and expected == 200:
                require(response.headers.get('Cache-Control') == 'no-store', f'{path}: missing no-store')
            return body

    def upload(self, content, mode='2d'):
        boundary = 'smoke-' + uuid.uuid4().hex
        data = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="input.geojson"\r\n'
                'Content-Type: application/geo+json\r\n\r\n').encode() + content + (
                f'\r\n--{boundary}\r\nContent-Disposition: form-data; name="mode"\r\n\r\n{mode}'
                f'\r\n--{boundary}--\r\n').encode()
        job = self.request('/api/jobs', 202, data, {'Content-Type': f'multipart/form-data; boundary={boundary}'})
        return '/api/jobs/' + urllib.parse.quote(job['jobId'], safe='')

    def wait(self, job, timeout):
        deadline = time.monotonic() + timeout
        while True:
            status = self.request(job)
            if status['status'] in ('SUCCEEDED', 'FAILED'):
                return status
            require(time.monotonic() < deadline, f'{job}: calculation exceeded {timeout}s; server task may still be running')
            time.sleep(min(0.5, max(0, deadline - time.monotonic())))

    def pages(self, job, layer, variant=None):
        parameters = {'layer': layer, 'bbox': '-180,-90,180,90', 'limit': 2}
        if variant is not None:
            parameters['variantId'] = variant
        features, seen = [], set()
        for _ in range(100):
            page = self.request(job + '/map?' + urllib.parse.urlencode(parameters))
            require(page['type'] == 'FeatureCollection', 'Invalid map collection')
            require(len(page['features']) <= 2, 'Map ignored requested page size')
            features.extend(page['features'])
            cursor = page['nextCursor']
            if cursor is None:
                return features
            require(cursor not in seen, 'Map pagination repeated a cursor')
            seen.add(cursor)
            parameters['cursor'] = cursor
        raise RuntimeError('Map exceeds 100-page smoke-test limit (use a smaller input)')


def extent(features):
    result = None

    def visit(coordinates):
        nonlocal result
        if coordinates and isinstance(coordinates[0], (int, Decimal)):
            x, y = coordinates[:2]
            if result is None:
                result = [x, y, x, y]
            else:
                result = [min(result[0], x), min(result[1], y), max(result[2], x), max(result[3], y)]
        else:
            for child in coordinates:
                visit(child)

    for feature in features:
        if feature.get('geometry') is not None:
            visit(feature['geometry']['coordinates'])
    return result


def run(args, report):
    require(args.input.stat().st_size <= 16 * 1024 * 1024, 'Input exceeds 16 MiB smoke-test limit')
    content = args.input.read_bytes()
    original = json.loads(content, parse_float=Decimal)
    api = Client(args.base_url, args.request_timeout)
    require(api.request('/api/health')['status'] == 'UP', 'Backend is not UP')
    mode = getattr(args, 'mode', '2d')
    job = api.upload(content, mode)
    report['job'] = job
    status = api.wait(job, args.timeout)
    report['diagnostics'] = status.get('diagnostics', [])
    require(status['status'] == 'SUCCEEDED' and status['stage'] == 'DONE', f'Calculation failed: {status}')
    require(status['mode'] == mode, 'Job changed requested calculation mode')
    report['mode'] = mode
    variants = api.request(job + '/variants')
    require(1 <= len(variants) <= 3, 'Expected one to three variants')
    download = api.request(job + '/result')
    require(download['type'] == 'FeatureCollection', 'Invalid download collection')
    for feature in download['features']:
        p = feature['properties']
        if p['object_type'] == 'heat_network':
            for key in ('depth_start', 'depth_end'):
                require((p[key] is None) if mode == '2d' else (
                    not isinstance(p[key], bool) and isinstance(p[key], (int, Decimal))
                    and Decimal(p[key]).is_finite() and p[key] >= Decimal('0.7')), f'Invalid {mode} {key}')
    summaries = [f['properties'] for f in download['features'] if f['properties']['object_type'] == 'variant_summary']
    require(summaries == variants, 'Downloaded summaries differ from /variants')
    require([v['rank'] for v in variants] == list(range(1, len(variants) + 1)), 'Invalid variant ranks')
    require([v['score'] for v in variants] == sorted(v['score'] for v in variants), 'Variants are not ranked by score')
    inputs = api.pages(job, 'input')
    require(len(inputs) == len(original['features']), 'Input map lost or duplicated objects')
    report['input_features'] = len(inputs)
    report['variants'] = []
    for variant in variants:
        identifier = variant['variant_id']
        result = api.pages(job, 'result', identifier)
        expected = [f for f in download['features'] if f.get('geometry') is not None and f['properties']['variant_id'] == identifier]
        require(result == expected, f'{identifier}: map differs from exported geometries')
        bounds = api.request(job + '/map/bounds?' + urllib.parse.urlencode({'variantId': identifier}))['bbox']
        expected_bounds = extent(inputs + result)
        require((bounds is None) == (expected_bounds is None), 'Empty bounds mismatch')
        if bounds is not None:
            require(len(bounds) == 4 and all(abs(a - b) <= Decimal('0.000000001') for a, b in zip(bounds, expected_bounds)), 'Bounds differ from input + result extent')
        report['variants'].append({'id': identifier, 'result_features': len(result),
                                   'unconnected_ids': variant['unconnected_oks_ids'], 'score': str(variant['score'])})
    invalid = api.upload(b'{ broken json', mode)
    report['invalid_job'] = invalid
    rejected = api.wait(invalid, args.timeout)
    require(rejected['status'] == 'FAILED' and any(d['code'] == 'INVALID_INPUT' for d in rejected['diagnostics']), 'Malformed JSON was not rejected')
    for endpoint in ('/result', '/variants', '/map/bounds?variantId=variant-1', '/map?layer=input&bbox=0,0,1,1'):
        require(api.request(invalid + endpoint, 409)['code'] == 'RESULT_NOT_READY', 'Failed job exposed results')
    report['full_connection'] = not variants[0]['unconnected_oks_ids']
    if args.require_full_connection:
        require(report['full_connection'], 'Best variant leaves connection points unconnected; see report')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', default='http://127.0.0.1:8080')
    parser.add_argument('--mode', choices=('2d', 'depth'), default='2d')
    parser.add_argument('--input', type=Path, default=ROOT / 'test-data/synthetic/two-consumers/input.geojson')
    parser.add_argument('--timeout', type=float, default=180, help='Per-job polling deadline in seconds')
    parser.add_argument('--request-timeout', type=float, default=15)
    parser.add_argument('--require-full-connection', action='store_true')
    parser.add_argument('--report', type=Path, help='Optional JSON report file; parent directory must exist')
    args = parser.parse_args()
    if any(not math.isfinite(value) or value <= 0 for value in (args.timeout, args.request_timeout)):
        parser.error('Timeouts must be finite and positive')
    report = {'input': str(args.input), 'base_url': args.base_url, 'passed': False}
    try:
        run(args, report)
        report['passed'] = True
    except (OSError, ValueError, RuntimeError, KeyError, TypeError) as error:
        report['error'] = str(error)
    text = json.dumps(report, ensure_ascii=True, indent=2, default=str)
    print(text)
    if args.report:
        args.report.write_text(text + '\n', encoding='utf-8')
    return 0 if report['passed'] else 1


if __name__ == '__main__':
    sys.exit(main())
