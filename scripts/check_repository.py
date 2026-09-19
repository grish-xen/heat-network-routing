"""Check shared artifacts with Python 3.9+ standard library; no application solver implied."""
import hashlib
import json
import math
import re
import sys
import xml.etree.ElementTree as ET
import zipfile
from collections import Counter
from decimal import Decimal
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def read(path):
    return json.loads((ROOT / path).read_text(encoding='utf-8-sig'), parse_float=Decimal)

def require(condition, message):
    if not condition:
        raise ValueError(message)

def id_key(value):
    require(isinstance(value, (str, int, Decimal)) and not isinstance(value, bool), 'Invalid ID type')
    return ('s', value) if isinstance(value, str) else ('n', Decimal(value))

def input_check(path):
    data = read(path)
    require(data.get('type') == 'FeatureCollection', 'Invalid collection')
    types = {'source': ('Point', []), 'heat_chamber': ('Point', []),
             'heat_network': ('LineString', ['diameter']),
             'oks_connection_point': ('Point', ['flow_tph']),
             'restriction': (['LineString', 'MultiLineString', 'Polygon', 'MultiPolygon'], ['restriction_type'])}
    seen = set()
    for f in data['features']:
        p, g = f['properties'], f['geometry']
        require(f['type'] == 'Feature', 'Invalid feature')
        key = id_key(p['id'])
        require(key not in seen, 'Duplicate ID')
        seen.add(key)
        require(p['object_type'] in types, 'Unexpected object type')
        geoms, fields = types[p['object_type']]
        require(g['type'] in ([geoms] if isinstance(geoms, str) else geoms), 'Geometry type mismatch')
        require(all(k in p for k in fields), 'Missing required input property')
        if 'flow_tph' in p:
            require(isinstance(p['flow_tph'], (int, Decimal)) and not isinstance(p['flow_tph'], bool) and p['flow_tph'] > 0, 'Invalid flow')
        if 'diameter' in p:
            require(p['diameter'] in {d['diameterMm'] for d in catalog['diameters']}, 'Invalid diameter')
        if p['object_type'] == 'restriction':
            require(isinstance(p['restriction_type'], str), 'Invalid restriction type')
    require(sum(f['properties']['object_type'] == 'source' for f in data['features']) == 1, 'Expected one source')
    return data

try:
    catalog = read('backend/src/main/resources/rules/catalog-v1.json')
    source = ROOT / 'docs/reference/current-technical-appendix.docx'
    require(hashlib.sha256(source.read_bytes()).hexdigest() == catalog['sourceSha256'], 'Catalog source changed')
    with zipfile.ZipFile(source) as doc:
        tree = ET.fromstring(doc.read('word/document.xml'))
    ns = {'w': 'http://schemas.openxmlformats.org/wordprocessingml/2006/main'}
    tables = tree.findall('.//w:body/w:tbl', ns)
    def rows(table):
        return [
            [''.join(t.text or '' for t in c.findall('.//w:t', ns)) for c in r.findall('w:tc', ns)]
            for r in table.findall('w:tr', ns)]
    def number(s):
        return Decimal(s.replace(' ', '').replace('\xa0', '').replace(',', '.'))
    expected_rows = rows(tables[1])[1:]
    require(len(catalog['diameters']) == len(expected_rows), 'Diameter row count mismatch')
    for stored, raw in zip(catalog['diameters'], expected_rows):
        require([Decimal(str(stored[k])) for k in ['diameterMm','capacityTph','maxLengthM','newCostRubPerM','widthM','heightM']] == [number(s) for s in raw], 'Diameter table mismatch')
    require([r['specialCoefficient'] for r in catalog['restrictions'] if r['crossing']=='special'] == [number(row[-1]) for row in rows(tables[3])[1:] if row[-1].strip()], 'Special coefficients differ from DOCX')
    require([c['costRub'] for c in catalog['chambers']] == [number(row[1]) for row in rows(tables[2])[1:]], 'Chamber costs differ from DOCX')

    competition = input_check('test-data/competition-corrected.geojson')
    toy = input_check('test-data/synthetic/two-consumers/input.geojson')
    case = read('test-data/synthetic/two-consumers/metric-case.json')
    expected = read('test-data/synthetic/two-consumers/expected.geojson')
    nodes = {n['id']: n for n in case['nodes']}
    external = {id_key(f['properties']['id']): f for f in toy['features']}
    outgoing = {n: [] for n in nodes}
    parents = Counter()
    for edge in case['edges']:
        a, b = edge['fromNodeId'], edge['toNodeId']
        require(edge['xy'][0] == nodes[a]['xy'] and edge['xy'][-1] == nodes[b]['xy'], 'Disconnected edge')
        require(abs(math.dist(*edge['xy']) - edge['lengthM']) < 1e-8, 'Incorrect fixture length')
        outgoing[a].append(edge)
        parents[b] += 1
        row = next(d for d in catalog['diameters'] if d['capacityTph'] >= edge['flowTph'] and d['maxLengthM'] >= edge['lengthM'])
        require(edge['diameterMm'] == row['diameterMm'], 'Incorrect fixture diameter')
        require(edge['costRub'] == edge['lengthM'] * row['newCostRubPerM'], 'Incorrect fixture cost')
    require(parents['root'] == 0 and all(parents[n] == 1 for n in nodes if n != 'root'), 'Invalid parent counts')
    def flow(node, ancestors):
        require(node not in ancestors, 'Cycle in fixture')
        n = nodes[node]
        if n['kind'] == 'CONNECTION_POINT':
            return external[id_key(n['inputObjectId'])]['properties']['flow_tph']
        total = 0
        for e in outgoing[node]:
            child_flow = flow(e['toNodeId'], ancestors | {node})
            require(child_flow == e['flowTph'], 'Flow conservation violated')
            total += child_flow
        return total
    require(flow('root', set()) == 25, 'Fixture total flow')
    features = expected['features']
    all_nodes = {**external, **{id_key(f['properties']['id']): f for f in features if f['geometry'] and f['geometry']['type']=='Point'}}
    required_network = ['start_node_id','end_node_id','flow_tph','diameter','length','laying_method','depth_start','depth_end','cost']
    for f in features:
        p = f['properties']
        if p['object_type'] == 'heat_network':
            require(all(k in p for k in required_network), 'Output field missing')
            for field, index in [('start_node_id',0),('end_node_id',-1)]:
                require(all_nodes[id_key(p[field])]['geometry']['coordinates'] == f['geometry']['coordinates'][index], 'Output node mismatch')
    summary = next(f['properties'] for f in features if f['properties']['object_type']=='variant_summary')
    require(summary['construction_cost'] == sum(e['costRub'] for e in case['edges']) + 3000000 + 5000000, 'Fixture summary mismatch')
    score = Decimal('0.7') * summary['calculated_cost'] / 25000000 + Decimal('0.3') * summary['new_network_length'] / 100
    require(score == summary['score'], 'Fixture score mismatch')
    api = read('contracts/openapi.json')
    require(api['openapi'] == '3.0.3', 'OpenAPI version')
    require(read('test-data/api/variants.json') == [summary], 'API fixture drift')
    def check_refs(obj):
        if isinstance(obj, dict):
            if '$ref' in obj:
                require(obj['$ref'].startswith('#/'), 'External OpenAPI reference')
                target = api
                for part in obj['$ref'][2:].split('/'):
                    target = target[part]
            for v in obj.values(): check_refs(v)
        elif isinstance(obj,list):
            for v in obj: check_refs(v)
    check_refs(api)
    ET.parse(ROOT/'backend/pom.xml')
    for p in ROOT.rglob('*.md'):
        if 'target' in p.parts: continue
        for href in re.findall(r'\]\(([^)]+)\)',p.read_text(encoding='utf-8')):
            if href.startswith(('http:', 'https:', '#')): continue
            require((p.parent/href.split('#')[0]).exists(), f'Broken link: {p}: {href}')
    print('OK: catalogs match DOCX; input fields; fixture topology, flows, costs, IDs; API refs; docs links.')
    print(f'Competition input: {len(competition["features"])} objects. Full spatial validation belongs to the solver.')
except (ValueError, KeyError, OSError, ET.ParseError) as e:
    print('FAILED:', e, file=sys.stderr)
    sys.exit(1)
