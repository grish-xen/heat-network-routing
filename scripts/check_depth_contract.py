"""Independent checks for small depth fixtures; not a general spatial validator or DEPTH solver."""
import copy
import hashlib
import json
import math
from decimal import Decimal, ROUND_HALF_UP
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
D = Decimal


def read(path):
    return json.loads((ROOT / path).read_text(encoding='utf-8'), parse_float=D)


def require(condition, message):
    if not condition: raise ValueError('DEPTH: ' + message)


def scalar(value):
    return isinstance(value, (int, D)) and not isinstance(value, bool) and math.isfinite(value)


def key(value):
    return ('s', value) if isinstance(value, str) else ('n', D(value))


def near(a, b, tolerance=D('0.000001')):
    return abs(D(str(a)) - D(str(b))) <= tolerance


def rounded(value): return value.quantize(D('.01'), rounding=ROUND_HALF_UP)


def length(edge):
    return D(str(sum(math.dist(a,b) for a,b in zip(edge['xy'], edge['xy'][1:]))))


def depth_diagnostics(variant, inputs, rows, depth, policy):
    """Checks fixture endpoint depths/gradients and designated constant-depth crossings only."""
    codes = set()
    heights = {}
    objects = {key(o['id']): o for o in inputs}
    crossing_rules = {r['type']: r for r in depth['crossings']}
    for e in variant['edges']:
        a, b = e['depthStartM'], e['depthEndM']
        if not all(scalar(h) and h >= depth['minimumDepthM'] for h in (a,b)):
            codes.add('DEPTH_VALUE_INVALID')
            continue
        if abs(a-b) > depth['maximumSlope'] * length(e) + D('0.000000001'):
            codes.add('DEPTH_SLOPE_VIOLATION')
        for node, h in ((e['fromNodeId'],a),(e['toNodeId'],b)):
            if node in heights and not near(heights[node], h): codes.add('DEPTH_CONTINUITY_VIOLATION')
            heights[node] = h
        for identifier in e['crossedObjectIds']:
            obj = objects[key(identifier)]
            rule = crossing_rules[obj.get('restriction_type', obj['object_type'])]
            if not near(a,b): codes.add('DEPTH_CONTINUITY_VIOLATION')
            if 'minimumNewTopDepthM' in rule:
                valid = min(a,b) >= rule['minimumNewTopDepthM']
            else:
                existing_height = rule.get('profileHeightM', rows[obj.get('diameter',80)]['heightM'])
                top = rule['existingTopDepthM']
                gap = rule['minimumVerticalClearanceM']
                valid = (max(a,b) + rows[e['diameterMm']]['heightM'] <= top - gap
                         or min(a,b) >= top + existing_height + gap)
            if not valid: codes.add('DEPTH_CLEARANCE_VIOLATION')
    for n in variant['nodes']:
        boundary = policy['consumerTopDepthM'] if n['kind'] == 'CONNECTION_POINT' else (
            policy['rootTopDepthM'] if any(a['rootNodeId'] == n['id'] for a in variant['attachments']) else None)
        if boundary is not None and n['id'] in heights and not near(heights[n['id']], boundary):
            codes.add('DEPTH_CONTINUITY_VIOLATION')
    return codes


def check_depth():
    catalog = read('backend/src/main/resources/rules/catalog-v1.json')
    depth = read('backend/src/main/resources/rules/depth-v1.json')
    policy = read('contracts/depth-policy.v1.json')
    source = ROOT / 'docs/reference/current-technical-appendix.docx'
    require(depth['sourceSha256'] == hashlib.sha256(source.read_bytes()).hexdigest(), 'source changed')
    require([depth[k] for k in ('ordinaryDepthM','minimumDepthM','maximumSlope','maximumDepthM','depthStepM')]
            == [3,D('.7'),D('.10'),None,None], 'section 5 numeric rules differ')
    require(depth['cost'] == dict(thresholdM=3, baseCoefficient=1, incrementPerM=D('.1')), 'depth cost rule')
    require(depth['crossings'] == [dict(type='road',minimumNewTopDepthM=1),
            dict(type='tram_tracks',minimumNewTopDepthM=D('1.2')),
            dict(type='gas_pipeline',existingTopDepthM=D('2.8'),profileWidthM=D('.4'),profileHeightM=D('.4'),minimumVerticalClearanceM=D('.2')),
            dict(type='power_cable',existingTopDepthM=D('2.7'),profileWidthM=D('.2'),profileHeightM=D('.2'),minimumVerticalClearanceM=D('.5')),
            dict(type='heat_network',existingTopDepthM=3,profileDimensionsFrom='catalog-v1.diameters',minimumVerticalClearanceM=D('.5'))], 'table 2 depth rules differ')
    from generate_depth_fixtures import visualization_rules
    require(read('contracts/visualization-rules.v1.json') == json.loads(json.dumps(visualization_rules()), parse_float=D), 'stale visualization catalog; regenerate')
    require(policy['version'] == 'depth-contract-1', 'policy version')
    rows = {r['diameterMm']:r for r in catalog['diameters']}
    rules = {r['type']:r for r in catalog['restrictions']}
    base = 'test-data/synthetic/depth/'
    manifest = read(base + 'manifest.json')
    require(len(set(manifest['cases'])) == len(manifest['cases']), 'duplicate case')
    cases = {}
    for name in manifest['cases']:
        case = read(base + name + '/metric-case.json')
        cases[name] = case
        v = case['expectedVariant']
        require(case['srid'] == 32637 and v['mode'] == 'DEPTH', name + ' CRS/mode')
        require(not depth_diagnostics(v, case['inputObjects'], rows, depth, policy), name + ' depth validity')
        nodes = {n['id']:n for n in v['nodes']}
        require(len(nodes) == len(v['nodes']), name + ' duplicate node')
        inputs = {key(o['id']):o for o in case['inputObjects']}
        require(len(inputs) == len(case['inputObjects']), name + ' duplicate input ID')
        parents, children = {}, {n:[] for n in nodes}
        for e in v['edges']:
            require(e['xy'][0] == nodes[e['fromNodeId']]['xy'] and e['xy'][-1] == nodes[e['toNodeId']]['xy'], name + ' edge endpoints')
            require(e['toNodeId'] not in parents, name + ' multiple parents')
            parents[e['toNodeId']] = e
            children[e['fromNodeId']].append(e)
            require(near(length(e),e['lengthM']) and e['lengthM'] > 0, name + ' horizontal length')
            a,b = e['depthStartM'],e['depthEndM']
            require(not min(a,b) < 3 < max(a,b), name + ' unsplit threshold')
            k = (max(D(1), 1+(a-3)/10) + max(D(1), 1+(b-3)/10))/2
            coeff = max([D(1)] + [rules[inputs[key(i)].get('restriction_type',inputs[key(i)]['object_type'])]['specialCoefficient'] for i in e['crossedObjectIds']])
            require(coeff == e['specialCoefficient'], name + ' special coefficient')
            require(e['layingMethod'] == ('SPECIAL' if e['crossedObjectIds'] else 'BASE'), name + ' laying method')
            cost = rounded(D(e['lengthM']) * D(rows[e['diameterMm']]['newCostRubPerM']) * coeff * k)
            require(e['costRub'] == cost, name + ' edge cost')
        def visit(node, ancestors):
            require(node not in ancestors, name + ' cycle')
            n = nodes[node]
            if n['kind'] == 'CONNECTION_POINT':
                require(not children[node], name + ' target not leaf')
                return inputs[key(n['inputObjectId'])]['flow_tph']
            total = 0
            for e in children[node]:
                flow = visit(e['toNodeId'], ancestors | {node})
                require(flow == e['flowTph'], name + ' flow conservation')
                require(rows[e['diameterMm']]['capacityTph'] >= flow, name + ' capacity')
                total += flow
            return total
        visit('root', set())
        require(set(parents) == set(nodes)-{'root'}, name + ' disconnected graph')
        chamber_cost = sum(c['costRub'] for c in v['newChambers'])
        for c in v['newChambers']:
            row = next(r for r in catalog['chambers'] if c['diameterMm'] <= r['maxDiameterMm'])
            require(c['costRub'] == row['costRub'], name + ' chamber cost')
        s = v['summary']
        require(s['existingChamberTieInCount'] == 1 and s['existingChamberTieInCost'] == catalog['existingChamberTieInRub'], name + ' tie-in')
        require(s['chamberConstructionCost'] == chamber_cost, name + ' chamber summary')
        require(s['constructionCost'] == sum(e['costRub'] for e in v['edges']) + chamber_cost + s['existingChamberTieInCost'], name + ' construction summary')
        require(s['unconnectedPenalty'] == 0 and not v['unconnectedPointIds'] and s['calculatedCost'] == s['constructionCost'], name + ' penalty')
        require(s['newNetworkLength'] == sum(e['lengthM'] for e in v['edges']), name + ' length summary')
        require(near(s['score'],D('.7')*s['calculatedCost']/25000000 + D('.3')*s['newNetworkLength']/100,D('0.0000000001')), name + ' score')
        inp = read(base + name + '/input.geojson')
        out = read(base + name + '/expected.geojson')
        exported = [f['properties'] for f in out['features'] if f['properties']['object_type'] == 'heat_network']
        require(len(exported) == len(v['edges']), name + ' export count')
        for e,p in zip(v['edges'],exported):
            for field,internal in [('depth_start','depthStartM'),('depth_end','depthEndM'),('cost','costRub'),('length','lengthM'),('diameter','diameterMm'),('flow_tph','flowTph')]:
                require(p[field] == e[internal], name + ' exported '+field)
        external = {key(f['properties']['id']):f for f in inp['features'] + out['features'] if f['geometry'] and f['geometry']['type']=='Point'}
        for f in out['features']:
            if f['properties']['object_type'] == 'heat_network':
                for field,idx in [('start_node_id',0),('end_node_id',-1)]:
                    require(external[key(f['properties'][field])]['geometry']['coordinates'] == f['geometry']['coordinates'][idx], name + ' export endpoint')
        summary = out['features'][-1]['properties']
        require(summary['construction_cost'] == s['constructionCost'] and summary['score'] == s['score'], name + ' export summary')
    negatives = read(base + 'negative-cases.json')['cases']
    for test in negatives:
        case = cases[test['base']]
        v = copy.deepcopy(case['expectedVariant'])
        for pointer,value in test['replace'].items():
            parts = pointer.strip('/').split('/')
            cursor = v
            for part in parts[:-1]: cursor = cursor[int(part)] if isinstance(cursor,list) else cursor[part]
            cursor[parts[-1]] = value
        require(test['expectedCode'] in depth_diagnostics(v,case['inputObjects'],rows,depth,policy), test['id']+' did not fail as intended')
    units = read(base + 'profile-units.json')
    for u in units['costCases']:
        a,b,l = D(u['depthStartM']),D(u['depthEndM']),D(u['lengthM'])
        split = [l*(3-a)/(b-a)] if min(a,b)<3<max(a,b) else []
        require(split == u['expectedSplitAtM'], u['id'] + ' split')
        bounds = [D(0)] + split + [l]
        cost = D(0)
        for start,end in zip(bounds,bounds[1:]):
            k0,k1 = [max(D(1),1+(a+(b-a)*s/l-3)/10) for s in (start,end)]
            cost += rounded((end-start)*rows[u['diameterMm']]['newCostRubPerM']*u['specialCoefficient']*(k0+k1)/2)
        require(cost == u['expectedCostRub'], u['id']+' cost')
    interpolation = units['interpolationCase']
    distances = [0]
    for a,b in zip(interpolation['xy'],interpolation['xy'][1:]): distances.append(distances[-1]+math.dist(a,b))
    depths = [D(interpolation['depthStartM']) + (interpolation['depthEndM']-interpolation['depthStartM'])*D(str(s/distances[-1])) for s in distances]
    require(depths == interpolation['expectedVertexDepthsM'] and depths[::-1] == interpolation['reversedExpectedVertexDepthsM'], 'interpolation')
    t = units['insufficientTransition']
    require((t['requiredEndDepthM']-t['depthStartM'])/depth['maximumSlope'] == t['minimumRequiredLengthM'] > t['availableLengthM'], 'insufficient transition')
    api = 'test-data/api/depth/'
    exported = read(base + manifest['apiCase'] + '/expected.geojson')['features']
    require(read(api+'variants.json') == [exported[-1]['properties']], 'API summary drift')
    require(read(api+'map-result.json')['features'] == exported[:-1], 'API result drift')
    require(read(api+'map-input.json')['features'] == read(base+manifest['apiCase']+'/input.geojson')['features'], 'API input drift')
    coords = []
    def collect(value):
        if len(value)==2 and all(scalar(n) for n in value): coords.append(value)
        else:
            for child in value: collect(child)
    for file in ('map-input.json','map-result.json'):
        for f in read(api+file)['features']: collect(f['geometry']['coordinates'])
    require(read(api+'map-bounds.json')['bbox'] == [min(p[0] for p in coords),min(p[1] for p in coords),max(p[0] for p in coords),max(p[1] for p in coords)], 'API bounds drift')
    print(f'OK: depth contract examples ({len(cases)} networks, {len(negatives)} negative cases), costs, profiles, visualization catalog and API fixtures.')


if __name__ == '__main__': check_depth()
