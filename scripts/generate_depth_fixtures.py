"""Generate contract examples, not solver output. Python stdlib + JDK 11 + built backend JAR."""
import argparse
import copy
import json
import math
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile
from decimal import Decimal, ROUND_HALF_UP

ROOT = Path(__file__).resolve().parents[1]


def read(path):
    return json.loads((ROOT / path).read_text(encoding='utf-8'))


def write(path, value):
    path = ROOT / path
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + '\n', encoding='utf-8')


def money(value):
    return float(Decimal(str(value)).quantize(Decimal('.01'), rounding=ROUND_HALF_UP))


def visualization_rules():
    catalog = read('backend/src/main/resources/rules/catalog-v1.json')
    depth = read('backend/src/main/resources/rules/depth-v1.json')
    return dict(version='depth-contract-1', sourceSha256=depth['sourceSha256'],
                depthReference=depth['depthReference'], positiveDirection=depth['positiveDirection'],
                surface=depth['surface'], metricSrid=32637, geoJsonSrid=4326,
                ordinaryDepthM=depth['ordinaryDepthM'], minimumDepthM=depth['minimumDepthM'],
                maximumSlope=depth['maximumSlope'],
                diameters=[{k: row[k] for k in ('diameterMm', 'widthM', 'heightM')}
                           for row in catalog['diameters']], crossings=depth['crossings'])


def make_case(name, origin, profile=None, restriction=None, branch=False):
    ox, oy = origin
    def xy(x, y=0): return [ox + x, oy + y]
    def obj(identifier, kind, geom, **props):
        return dict(id=identifier, object_type=kind, geometry=geom, **props)
    def point(x, y=0): return dict(type='Point', coordinates=xy(x, y))
    def line(points): return dict(type='LineString', coordinates=[xy(*p) for p in points])
    inputs = [obj('source', 'source', point(0, -100)),
              obj('existing-line', 'heat_network', line([(0, -100), (0, 0)]), diameter=300),
              obj('existing-chamber', 'heat_chamber', point(0)),
              obj(1, 'oks_connection_point', point(100), flow_tph=10)]
    if branch:
        inputs.append(obj('1', 'oks_connection_point', point(50, 50), flow_tph=15))
    if restriction:
        if restriction == 'road':
            geometry = dict(type='Polygon', coordinates=[[xy(40, -20), xy(60, -20),
                             xy(60, 20), xy(40, 20), xy(40, -20)]])
        else:
            geometry = line([(50, -30), (50, 30)])
        inputs.append(obj('crossing', 'heat_network' if restriction == 'heat_network' else 'restriction',
                          geometry, **({'diameter': 300} if restriction == 'heat_network'
                                      else {'restriction_type': restriction})))
    def node(identifier, kind, position, external=None):
        return dict(id=identifier, kind=kind, inputObjectId=external, xy=position)
    root = node('root', 'EXISTING_CHAMBER', xy(0), 'existing-chamber')
    target = node('target', 'CONNECTION_POINT', xy(100), 1)
    nodes = [root, target]
    attachment = [dict(rootNodeId='root', existingObjectId='existing-chamber')]
    def edge(identifier, start, end, points):
        return dict(id=identifier, fromNodeId=start, toNodeId=end, xy=points)
    if branch:
        nodes += [node('branch', 'NEW_CHAMBER', xy(50)),
                  node('other', 'CONNECTION_POINT', xy(50, 50), '1')]
        candidate_edges = [edge('trunk', 'root', 'branch', [xy(0), xy(50)]),
                           edge('a', 'branch', 'target', [xy(50), xy(100)]),
                           edge('b', 'branch', 'other', [xy(50), xy(50, 50)])]
    else:
        candidate_edges = [edge('route', 'root', 'target', [xy(0), xy(25), xy(100)])]
    candidate = dict(candidateId=name, nodes=copy.deepcopy(nodes), edges=candidate_edges,
                     attachments=attachment, unconnectedPointIds=[], diagnostics=[])
    rows = {r['diameterMm']: r for r in read('backend/src/main/resources/rules/catalog-v1.json')['diameters']}
    edges = []
    def calculated(e, flow, diameter, h0, h1, special=1):
        length = sum(math.dist(a, b) for a, b in zip(e['xy'], e['xy'][1:]))
        k = (Decimal('1') + max(Decimal(0), Decimal(str(h0)) - 3) / 10
             + Decimal('1') + max(Decimal(0), Decimal(str(h1)) - 3) / 10) / 2
        return dict(**e, flowTph=flow, diameterMm=diameter, lengthM=length,
                    depthStartM=h0, depthEndM=h1, layingMethod='SPECIAL' if special != 1 else 'BASE',
                    specialCoefficient=special, crossedObjectIds=['crossing'] if special != 1 else [],
                    costRub=money(Decimal(str(length)) * Decimal(str(rows[diameter]['newCostRubPerM']))
                                  * Decimal(str(special)) * k))
    if branch:
        for e, flow, diameter in zip(candidate_edges, [25, 10, 15], [125, 80, 100]):
            edges.append(calculated(e, flow, diameter, 3, 3))
    else:
        profile = profile or [(0, 3), (100, 3)]
        special_range = (37, 63) if restriction == 'road' else (48, 52)
        coeff = {'gas_pipeline': 1.25, 'power_cable': 1.15, 'heat_network': 1.05, 'road': 1.6}
        for i, ((x0, h0), (x1, h1)) in enumerate(zip(profile, profile[1:])):
            start = 'root' if i == 0 else 'tech-' + str(i)
            end = 'target' if i == len(profile) - 2 else 'tech-' + str(i + 1)
            if end != 'target': nodes.append(node(end, 'TECHNICAL_NODE', xy(x1)))
            points = [xy(x0), xy(x1)]
            if not restriction: points.insert(1, xy(25))
            special = coeff[restriction] if restriction and special_range[0] <= x0 < x1 <= special_range[1] else 1
            edges.append(calculated(edge('edge-' + str(i + 1), start, end, points), 10, 80, h0, h1, special))
    chambers = [dict(nodeId='branch', diameterMm=125, costRub=3000000)] if branch else []
    construction = sum(Decimal(str(e['costRub'])) for e in edges) + sum(Decimal(str(c['costRub'])) for c in chambers) + 5000000
    length = sum(e['lengthM'] for e in edges)
    summary = dict(constructionCost=money(construction), chamberConstructionCost=3000000 if branch else 0,
                   existingChamberTieInCount=1, existingChamberTieInCost=5000000, unconnectedPenalty=0,
                   calculatedCost=money(construction), newNetworkLength=length,
                   score=float((Decimal('.7') * construction / 25000000 + Decimal('.3') * Decimal(str(length)) / 100)
                               .quantize(Decimal('.0000000001'), rounding=ROUND_HALF_UP)))
    variant = dict(variantId='variant-1', mode='DEPTH', nodes=nodes, edges=edges, attachments=attachment,
                   newChambers=chambers, unconnectedPointIds=[], summary=summary)
    return dict(fixtureVersion='depth-contract-1', status='illustrative-not-solver-output', srid=32637,
                caseId=name, inputObjects=inputs, candidate=candidate, expectedVariant=variant)


def coordinates(value):
    if isinstance(value, list):
        if len(value) == 2 and all(isinstance(n, (int, float)) for n in value): yield tuple(value)
        else:
            for child in value: yield from coordinates(child)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--backend-jar', type=Path, default=ROOT / 'backend/target/heat-network-routing-0.1.0-SNAPSHOT.jar')
    args = parser.parse_args()
    cases = [make_case('flat', (400000, 6170000)),
             make_case('gas-below', (401000, 6170100), [(0,3),(44,3),(48,3.4),(52,3.4),(56,3),(100,3)], 'gas_pipeline'),
             make_case('gas-above', (402000, 6170200), [(0,3),(42,3),(48,2.4),(52,2.4),(58,3),(100,3)], 'gas_pipeline'),
             make_case('cable-below', (403000, 6170300), [(0,3),(44,3),(48,3.4),(52,3.4),(56,3),(100,3)], 'power_cable'),
             make_case('network-below', (404000, 6170400), [(0,3),(38.5,3),(48,3.95),(52,3.95),(61.5,3),(100,3)], 'heat_network'),
             make_case('road', (405000, 6170500), [(0,3),(37,3),(63,3),(100,3)], 'road'),
             make_case('branch', (406000, 6170600), branch=True)]
    points = set()
    for case in cases:
        for obj in case['inputObjects']: points.update(coordinates(obj['geometry']['coordinates']))
        for node in case['expectedVariant']['nodes']: points.add(tuple(node['xy']))
        for edge in case['expectedVariant']['edges']: points.update(coordinates(edge['xy']))
    points = sorted(points)
    java = str(Path(os.environ['JAVA_HOME']) / 'bin' / ('java.exe' if os.name == 'nt' else 'java')) if 'JAVA_HOME' in os.environ else 'java'
    with tempfile.TemporaryDirectory(prefix='depth-fixtures-') as tmp:
        with zipfile.ZipFile(args.backend_jar) as jar:
            lib = next(n for n in jar.namelist() if n.startswith('BOOT-INF/lib/proj4j-'))
            proj = Path(tmp) / 'proj4j.jar'
            proj.write_bytes(jar.read(lib))
        output = subprocess.run([java, '--class-path', str(proj), str(ROOT / 'scripts/ProjectDepthFixtures.java')],
                                input=''.join(f'{x} {y}\n' for x,y in points), text=True,
                                capture_output=True, check=True).stdout
    projected = dict(zip(points, [list(map(float, line.split())) for line in output.splitlines()]))
    if len(projected) != len(points): raise ValueError('Projection output count mismatch')
    def convert(value):
        if len(value) == 2 and all(isinstance(n, (int, float)) for n in value): return projected[tuple(value)]
        return [convert(child) for child in value]
    def feature(props, geometry): return dict(type='Feature', properties=props, geometry=geometry)
    for case in cases:
        folder = 'test-data/synthetic/depth/' + case['caseId'] + '/'
        write(folder + 'metric-case.json', case)
        inp = dict(type='FeatureCollection', features=[feature({k:v for k,v in o.items() if k != 'geometry'},
                   dict(type=o['geometry']['type'], coordinates=convert(o['geometry']['coordinates']))) for o in case['inputObjects']])
        write(folder + 'input.geojson', inp)
        variant = case['expectedVariant']
        def out_id(node): return node['inputObjectId'] if node['inputObjectId'] is not None else 'depth-' + node['id']
        nodes = {n['id']: n for n in variant['nodes']}
        features = []
        for e in variant['edges']:
            props = dict(id='depth-' + e['id'], object_type='heat_network', variant_id='variant-1',
                         start_node_id=out_id(nodes[e['fromNodeId']]), end_node_id=out_id(nodes[e['toNodeId']]),
                         flow_tph=e['flowTph'], diameter=e['diameterMm'], length=e['lengthM'],
                         laying_method=e['layingMethod'].lower(), depth_start=e['depthStartM'], depth_end=e['depthEndM'], cost=e['costRub'])
            features.append(feature(props, dict(type='LineString', coordinates=convert(e['xy']))))
        for n in variant['nodes']:
            if n['kind'] not in ('NEW_CHAMBER', 'TECHNICAL_NODE'): continue
            props = dict(id=out_id(n), object_type='heat_chamber' if n['kind'] == 'NEW_CHAMBER' else 'technical_node', variant_id='variant-1')
            if n['kind'] == 'NEW_CHAMBER':
                cost = next(c for c in variant['newChambers'] if c['nodeId'] == n['id'])
                props.update(diameter=cost['diameterMm'], cost=cost['costRub'])
            features.append(feature(props, dict(type='Point', coordinates=convert(n['xy']))))
        mapping = dict(constructionCost='construction_cost', chamberConstructionCost='chamber_construction_cost',
                       existingChamberTieInCount='existing_chamber_tie_in_count', existingChamberTieInCost='existing_chamber_tie_in_cost',
                       unconnectedPenalty='unconnected_penalty', calculatedCost='calculated_cost', newNetworkLength='new_network_length', score='score')
        summary = dict(id='depth-summary', object_type='variant_summary', variant_id='variant-1', rank=1,
                       unconnected_oks_ids=[], **{mapping[k]:v for k,v in variant['summary'].items()})
        write(folder + 'expected.geojson', dict(type='FeatureCollection', features=features + [feature(summary, None)]))
        if case['caseId'] == 'gas-below':
            api = 'test-data/api/depth/'
            write(api + 'variants.json', [summary])
            write(api + 'map-input.json', dict(**inp, nextCursor=None))
            write(api + 'map-result.json', dict(type='FeatureCollection', features=features, nextCursor=None))
            xy = [p for f in inp['features'] + features for p in coordinates(f['geometry']['coordinates'])]
            write(api + 'map-bounds.json', dict(bbox=[min(p[0] for p in xy),min(p[1] for p in xy),max(p[0] for p in xy),max(p[1] for p in xy)]))
            for status, stage in [('QUEUED','QUEUED'),('RUNNING','CALCULATING'),('SUCCEEDED','DONE')]:
                write(api + 'job-' + status.lower() + '.json', dict(jobId='00000000-0000-4000-8000-000000000003',
                      status=status, stage=stage, mode='depth', diagnostics=[]))
    write('contracts/visualization-rules.v1.json', visualization_rules())
    write('test-data/synthetic/depth/manifest.json', dict(version='depth-contract-1', cases=[c['caseId'] for c in cases],
          apiCase='gas-below', policy='contracts/depth-policy.v1.json'))
    print('Generated', len(cases), 'depth contract examples and frontend fixtures; solver not invoked.')


if __name__ == '__main__': main()
