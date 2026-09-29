"""Independent audit of a result GeoJSON against the technical appendix (2D and depth).

Does not use the backend code: its own UTM 37N projection (Krueger series) and plain-Python geometry.
Numbers come from the appendix tables via catalog-v1.json / depth-v1.json, which check_repository.py
ties to the DOCX. Checks the output format and references, the tree topology, flows, diameters, the
length limit, chambers and the 10 m rule, turns, crossings of new edges, forbidden restrictions and the
own-OKS final approach, special passes, costs, penalty, score and, in the depth mode, the profile.

Usage (from the repository root):
  python scripts/audit_result.py INPUT.geojson RESULT.geojson \
      backend/src/main/resources/rules/catalog-v1.json backend/src/main/resources/rules/depth-v1.json 2d|depth
Exit code 1 when problems are found. Informational notes (e.g. length-driven upsizing) are printed first.
"""
import json
import math
import sys
from collections import defaultdict
from decimal import Decimal

# ---------------------------------------------------------------- projection (Krüger series)
A = 6378137.0
F = 1 / 298.257223563
K0 = 0.9996
LON0 = math.radians(39.0)  # UTM zone 37
N_ = F / (2 - F)
A_ = A / (1 + N_) * (1 + N_ ** 2 / 4 + N_ ** 4 / 64)
ALPHA = [N_ / 2 - 2 * N_ ** 2 / 3 + 5 * N_ ** 3 / 16,
         13 * N_ ** 2 / 48 - 3 * N_ ** 3 / 5,
         61 * N_ ** 3 / 240]


def utm(lon, lat):
    phi, lam = math.radians(lat), math.radians(lon) - LON0
    t = math.sinh(math.atanh(math.sin(phi)) - 2 * math.sqrt(N_) / (1 + N_) * math.atanh(2 * math.sqrt(N_) / (1 + N_) * math.sin(phi)))
    xi = math.atan2(t, math.cos(lam))
    eta = math.atanh(math.sin(lam) / math.sqrt(1 + t * t))
    e = 500000 + K0 * A_ * (eta + sum(a * math.cos(2 * j * xi) * math.sinh(2 * j * eta) for j, a in enumerate(ALPHA, 1)))
    n = K0 * A_ * (xi + sum(a * math.sin(2 * j * xi) * math.cosh(2 * j * eta) for j, a in enumerate(ALPHA, 1)))
    return (e, n)


# ---------------------------------------------------------------- geometry
def sub(a, b): return (a[0] - b[0], a[1] - b[1])
def cross(a, b): return a[0] * b[1] - a[1] * b[0]
def dot(a, b): return a[0] * b[0] + a[1] * b[1]
def dist(a, b): return math.hypot(a[0] - b[0], a[1] - b[1])


def pt_seg(p, a, b):
    ab = sub(b, a)
    L = dot(ab, ab)
    t = 0 if L == 0 else max(0, min(1, dot(sub(p, a), ab) / L))
    return dist(p, (a[0] + t * ab[0], a[1] + t * ab[1]))


def seg_inter(a, b, c, d, eps=1e-9):
    d1, d2 = cross(sub(b, a), sub(c, a)), cross(sub(b, a), sub(d, a))
    d3, d4 = cross(sub(d, c), sub(a, c)), cross(sub(d, c), sub(b, c))
    if ((d1 > eps and d2 < -eps) or (d1 < -eps and d2 > eps)) and ((d3 > eps and d4 < -eps) or (d3 < -eps and d4 > eps)):
        return True
    return min(pt_seg(c, a, b), pt_seg(d, a, b), pt_seg(a, c, d), pt_seg(b, c, d)) <= 1e-7


def seg_seg(a, b, c, d):
    return 0.0 if seg_inter(a, b, c, d) else min(pt_seg(c, a, b), pt_seg(d, a, b), pt_seg(a, c, d), pt_seg(b, c, d))


def in_ring(p, ring):
    inside = False
    for i in range(len(ring) - 1):
        a, b = ring[i], ring[i + 1]
        if (a[1] > p[1]) != (b[1] > p[1]):
            x = a[0] + (p[1] - a[1]) * (b[0] - a[0]) / (b[1] - a[1])
            if x > p[0]:
                inside = not inside
    return inside


class Shape:
    """Rings (polygons, holes) and lines of one restriction, in metres."""
    def __init__(self, geometry):
        self.polys, self.lines = [], []
        t, c = geometry["type"], geometry["coordinates"]
        if t == "Polygon":
            self.polys.append([[utm(*p) for p in r] for r in c])
        elif t == "MultiPolygon":
            self.polys += [[[utm(*p) for p in r] for r in poly] for poly in c]
        elif t == "LineString":
            self.lines.append([utm(*p) for p in c])
        elif t == "MultiLineString":
            self.lines += [[utm(*p) for p in l] for l in c]
        pts = [p for poly in self.polys for r in poly for p in r] + [p for l in self.lines for p in l]
        self.box = (min(p[0] for p in pts), min(p[1] for p in pts), max(p[0] for p in pts), max(p[1] for p in pts))

    def segments(self):
        for poly in self.polys:
            for r in poly:
                for i in range(len(r) - 1):
                    yield r[i], r[i + 1]
        for l in self.lines:
            for i in range(len(l) - 1):
                yield l[i], l[i + 1]

    def contains(self, p):
        return any(in_ring(p, poly[0]) and not any(in_ring(p, h) for h in poly[1:]) for poly in self.polys)

    def seg_distance(self, a, b):
        if self.contains(a) or self.contains(b):
            return 0.0
        return min((seg_seg(a, b, c, d) for c, d in self.segments()), default=math.inf)

    def near(self, a, b, pad):
        return not (max(a[0], b[0]) < self.box[0] - pad or min(a[0], b[0]) > self.box[2] + pad
                    or max(a[1], b[1]) < self.box[1] - pad or min(a[1], b[1]) > self.box[3] + pad)


def floats(v):
    if isinstance(v, dict):
        return {k: floats(x) for k, x in v.items()}
    if isinstance(v, list):
        return [floats(x) for x in v]
    return float(v) if isinstance(v, Decimal) else v


def crossing_points(a, b, shape):
    """Points where segment a-b crosses the shape boundary."""
    result = []
    for c, d in shape.segments():
        r, q = sub(b, a), sub(d, c)
        den = cross(r, q)
        if abs(den) < 1e-12:
            continue
        t = cross(sub(c, a), q) / den
        u = cross(sub(c, a), r) / den
        if 0 <= t <= 1 and 0 <= u <= 1:
            result.append((a[0] + t * r[0], a[1] + t * r[1]))
    return result


def length(line): return sum(dist(line[i], line[i + 1]) for i in range(len(line) - 1))


# ---------------------------------------------------------------- audit
def main(inp_path, res_path, cat_path, depth_path, mode):
    inp = json.load(open(inp_path, encoding="utf-8"), parse_float=Decimal)
    res = json.load(open(res_path, encoding="utf-8"), parse_float=Decimal)
    cat = json.load(open(cat_path, encoding="utf-8"))
    dep = json.load(open(depth_path, encoding="utf-8"))
    rows = {r["diameterMm"]: r for r in cat["diameters"]}
    rules = {r["type"]: r for r in cat["restrictions"]}
    problems, notes = [], []
    P = problems.append

    def key(v): return ("s", v) if isinstance(v, str) else ("n", Decimal(str(v)).normalize())

    inputs, points, chambers, lines, restr = {}, {}, {}, {}, []
    for f in inp["features"]:
        pr = f["properties"]
        inputs[key(pr["id"])] = pr
        g = f["geometry"]
        if pr["object_type"] == "oks_connection_point":
            points[key(pr["id"])] = (utm(*map(float, g["coordinates"])), Decimal(str(pr["flow_tph"])), pr["id"])
        elif pr["object_type"] == "heat_chamber":
            chambers[key(pr["id"])] = utm(*map(float, g["coordinates"]))
        elif pr["object_type"] == "heat_network":
            lines[key(pr["id"])] = ([utm(*map(float, p)) for p in g["coordinates"]], int(pr["diameter"]))
        elif pr["object_type"] == "restriction":
            restr.append((pr["id"], pr["restriction_type"], Shape(floats(g))))
    line_shapes = {lid: Shape({"type": "LineString", "coordinates": [[0, 0], [0, 0]]}) for lid in lines}
    for lid, (ln, d) in lines.items():
        s = line_shapes[lid]
        s.lines = [ln]
        s.box = (min(p[0] for p in ln), min(p[1] for p in ln), max(p[0] for p in ln), max(p[1] for p in ln))

    def existing_adjacency(p, tol=0.01):
        n, dmax = 0, 0
        for lid, (ln, d) in lines.items():
            if min(pt_seg(p, ln[i], ln[i + 1]) for i in range(len(ln) - 1)) > tol:
                continue
            ends = (dist(p, ln[0]) <= tol) + (dist(p, ln[-1]) <= tol)
            n += ends if ends else 2
            dmax = max(dmax, d)
        return n, dmax

    by_variant = defaultdict(list)
    ids = set()
    for f in res["features"]:
        pr = f["properties"]
        for fld in ("id", "object_type", "variant_id"):
            if fld not in pr:
                P(f"feature without {fld}: {pr}")
        k = key(pr["id"])
        if k in ids:
            P(f"duplicate output id {pr['id']}")
        if k in inputs:
            P(f"output id collides with input id {pr['id']}")
        ids.add(k)
        by_variant[pr["variant_id"]].append(f)

    ranks = []
    for vid, feats in by_variant.items():
        V = lambda m: P(f"[{vid}] {m}")
        nets = [f for f in feats if f["properties"]["object_type"] == "heat_network"]
        newch = {key(f["properties"]["id"]): f for f in feats if f["properties"]["object_type"] == "heat_chamber"}
        tech = {key(f["properties"]["id"]): f for f in feats if f["properties"]["object_type"] == "technical_node"}
        summ = [f for f in feats if f["properties"]["object_type"] == "variant_summary"]
        other = [f for f in feats if f["properties"]["object_type"] not in ("heat_network", "heat_chamber", "technical_node", "variant_summary")]
        if other:
            V(f"unexpected object types {set(o['properties']['object_type'] for o in other)}")
        if len(summ) != 1 or summ[0]["geometry"] is not None:
            V("exactly one variant_summary with null geometry expected")
            continue
        S = summ[0]["properties"]
        node_pos = {}
        for k2, f in list(newch.items()) + list(tech.items()):
            if f["geometry"]["type"] != "Point":
                V(f"node {f['properties']['id']} not a Point")
            node_pos[k2] = utm(*map(float, f["geometry"]["coordinates"]))
        for k2, p in chambers.items():
            node_pos.setdefault(k2, p)
        for k2, p in points.items():
            node_pos.setdefault(k2, p[0])

        edges = []
        for f in nets:
            pr, g = f["properties"], f["geometry"]
            if g["type"] != "LineString":
                V(f"edge {pr['id']} not LineString")
                continue
            ln = [utm(*map(float, c[:2])) for c in g["coordinates"]]
            if any(len(c) != 2 for c in g["coordinates"]):
                notes.append(f"[{vid}] edge {pr['id']} has Z coordinates")
            for fld in ("start_node_id", "end_node_id", "flow_tph", "diameter", "length", "laying_method", "depth_start", "depth_end", "cost"):
                if fld not in pr:
                    V(f"edge {pr['id']} lacks {fld}")
            a, b = key(pr["start_node_id"]), key(pr["end_node_id"])
            for nk, end in ((a, ln[0]), (b, ln[-1])):
                if nk not in node_pos:
                    V(f"edge {pr['id']} references unknown node {nk[1]}")
                elif dist(node_pos[nk], end) > 0.01:
                    V(f"edge {pr['id']} end does not coincide with node {nk[1]} ({dist(node_pos[nk], end):.3f} m)")
            L = length(ln)
            if abs(L - float(pr["length"])) > 0.01:
                V(f"edge {pr['id']} length {pr['length']} vs geometry {L:.3f}")
            d = pr["diameter"]
            if not isinstance(d, int) or d not in rows:
                V(f"edge {pr['id']} diameter {d} not in table 1")
                continue
            if pr["laying_method"] not in ("base", "special"):
                V(f"edge {pr['id']} laying_method {pr['laying_method']}")
            if mode == "2d" and (pr["depth_start"] is not None or pr["depth_end"] is not None):
                V(f"edge {pr['id']} has depth in 2D")
            edges.append(dict(id=pr["id"], a=a, b=b, line=ln, L=L, d=d, flow=Decimal(str(pr["flow_tph"])),
                              lay=pr["laying_method"], cost=Decimal(str(pr["cost"])), hs=pr["depth_start"], he=pr["depth_end"]))

        # ---- topology: forest rooted at attachment chambers
        adj = defaultdict(list)
        for e in edges:
            adj[e["a"]].append(e)
            adj[e["b"]].append(e)
        connected = {k2 for k2 in adj if k2 in points}
        attach = []
        for n in adj:
            if n in chambers:
                attach.append(n)
            elif n in newch and existing_adjacency(node_pos[n])[0] > 0:
                attach.append(n)
        seen, parent_edge, order = set(), {}, []
        for r in attach:
            if r in seen:
                continue
            stack = [r]
            seen.add(r)
            while stack:
                n = stack.pop()
                order.append(n)
                for e in adj[n]:
                    m = e["b"] if e["a"] == n else e["a"]
                    if m in seen:
                        if parent_edge.get(n) is not e:
                            V(f"cycle or second attachment path through {m[1]} (edge {e['id']})")
                        continue
                    seen.add(m)
                    parent_edge[m] = e
                    stack.append(m)
        if seen != set(adj):
            V(f"{len(set(adj) - seen)} nodes not reachable from an attachment chamber")
        for n in adj:
            if n in points and len(adj[n]) != 1:
                V(f"connection point {n[1]} has degree {len(adj[n])}")
            if n in tech and len(adj[n]) != 2:
                V(f"technical node {n[1]} has degree {len(adj[n])}")
            if len(adj[n]) >= 3 and n not in chambers and n not in newch:
                V(f"branching outside a chamber at {n[1]}")
            if n in chambers or n in newch:
                ex, _ = existing_adjacency(node_pos[n])
                if ex + len(adj[n]) > cat["maxChamberDegree"]:
                    V(f"chamber {n[1]} has {ex}+{len(adj[n])} adjacencies")
        # 10 m rule for new attachment chambers
        for n in attach:
            if n in newch:
                for ck, cp in chambers.items():
                    if dist(cp, node_pos[n]) <= cat["existingChamberRadiusM"] + 1e-9:
                        ex, _ = existing_adjacency(cp)
                        used = len(adj.get(ck, []))
                        if ex + used + len(adj[n]) <= cat["maxChamberDegree"]:
                            V(f"new chamber {n[1]} within 10 m of chamber {ck[1]} with free adjacencies")
        # all points accounted for
        unc = S.get("unconnected_oks_ids", [])
        unk = {key(u) for u in unc}
        for pk in points:
            if (pk in connected) == (pk in unk):
                V(f"point {pk[1]} connected={pk in connected} listed_unconnected={pk in unk}")
        for u in unc:
            if key(u) in points and type(u) is not type(points[key(u)][2]):
                V(f"unconnected id {u} changed its JSON type")

        # ---- flows, diameters, monotonicity, length limit
        down = {}
        for n in reversed(order):
            f = points[n][1] if n in points else Decimal(0)
            for e in adj[n]:
                if parent_edge.get(n) is e:
                    continue
                m = e["b"] if e["a"] == n else e["a"]
                f += down.get(m, Decimal(0))
            down[n] = f
        child_of = {}
        for m, e in parent_edge.items():
            child_of[e["id"]] = m
            if e["flow"] != down[m]:
                V(f"edge {e['id']} flow {e['flow']} != downstream {down[m]}")
            minimal = min((d for d, r in rows.items() if Decimal(str(r["capacityTph"])) >= e["flow"]), default=None)
            if minimal is None or e["d"] < minimal:
                V(f"edge {e['id']} ДУ {e['d']} below capacity need (min {minimal})")
            up = parent_edge.get(e["a"] if child_of[e['id']] == e["b"] else e["b"])
            if up is not None and up["d"] < e["d"]:
                V(f"ДУ decreases towards attachment: {up['id']} {up['d']} < {e['id']} {e['d']}")
        same_down = {}
        for n in reversed(order):
            e = parent_edge.get(n)
            if e is None:
                continue
            best = 0.0
            for c in adj[n]:
                if c is e:
                    continue
                m = c["b"] if c["a"] == n else c["a"]
                if c["d"] == e["d"]:
                    best = max(best, same_down.get(m, 0.0) + c["L"])
            same_down[n] = best
            run = e["L"] + best
            top = e["a"] if n == e["b"] else e["b"]
            pe = parent_edge.get(top)
            if (pe is None or pe["d"] != e["d"]) and run > rows[e["d"]]["maxLengthM"] + 1e-6:
                V(f"continuous ДУ{e['d']} path from edge {e['id']} is {run:.1f} m > {rows[e['d']]['maxLengthM']}")
        # smallest diameter: a larger one must be forced by children or by the length limit
        for m, e in parent_edge.items():
            minimal = min(d for d, r in rows.items() if Decimal(str(r["capacityTph"])) >= e["flow"])
            kids = [c["d"] for c in adj[m] if c is not e]
            if e["d"] > max([minimal] + kids):
                top = e["a"] if m == e["b"] else e["b"]
                # upsizing is justified only if the smaller one would break the length limit (checked loosely)
                notes.append(f"[{vid}] edge {e['id']} ДУ{e['d']} > flow/children minimum {max([minimal] + kids)} (length-driven?)")

        # ---- turns and crossings of new edges
        for e in edges:
            ln = e["line"]
            for i in range(1, len(ln) - 1):
                u, w = sub(ln[i], ln[i - 1]), sub(ln[i + 1], ln[i])
                c = dot(u, w) / (math.hypot(*u) * math.hypot(*w))
                if c < -1e-9:
                    V(f"turn > 90° in edge {e['id']} ({math.degrees(math.acos(max(-1, min(1, c)))):.1f}°)")
        for i, e in enumerate(edges):
            for f2 in edges[i + 1:]:
                shared = {e["a"], e["b"]} & {f2["a"], f2["b"]}
                for s1 in range(len(e["line"]) - 1):
                    for s2 in range(len(f2["line"]) - 1):
                        a1, b1, a2, b2 = e["line"][s1], e["line"][s1 + 1], f2["line"][s2], f2["line"][s2 + 1]
                        if seg_seg(a1, b1, a2, b2) > 1e-6:
                            continue
                        if shared and any(pt_seg(node_pos[s], a1, b1) < 0.01 and pt_seg(node_pos[s], a2, b2) < 0.01 for s in shared):
                            continue
                        V(f"new edges {e['id']} and {f2['id']} cross outside a shared node")

        # ---- restrictions
        own = {}
        for pk, (p, _, _) in points.items():
            for rid, rt, sh in restr:
                if rt == "oks" and sh.contains(p):
                    own[pk] = rid
        for e in edges:
            hw = rows[e["d"]]["widthM"] / 2
            end_point = e["b"] if e["b"] in points else (e["a"] if e["a"] in points else None)
            for rid, rt, sh in restr + [(lid[1], "heat_network", s) for lid, s in line_shapes.items()]:
                rule = rules.get(rt)
                if rule is None:
                    continue
                clearance = rule.get("clearanceM")
                if rule.get("clearanceBands"):
                    clearance = next(b["clearanceM"] for b in rule["clearanceBands"] if e["d"] <= b["maxDiameterMm"])
                need = clearance + hw
                if rt == "heat_network":
                    need += rows.get(lines[key(rid)][1], rows[max(rows)])["widthM"] / 2 if key(rid) in lines else 0
                segs = [(e["line"][i], e["line"][i + 1]) for i in range(len(e["line"]) - 1)]
                if not any(sh.near(a, b, need + 1) for a, b in segs):
                    continue
                dmin = min(sh.seg_distance(a, b) for a, b in segs)
                if rule["crossing"] == "forbidden":
                    if rt == "oks" and end_point is not None and own.get(end_point) == rid:
                        continue  # own polygon: final straight approach checked below
                    if dmin < need - 0.001:
                        V(f"edge {e['id']} {'crosses' if dmin == 0 else 'is %.2f m from' % dmin} forbidden {rt} {rid} (need {need:.2f})")
                elif dmin == 0:
                    # The connection itself: the line carrying/ending in the attachment chamber touches the edge end.
                    trimmed = list(segs)
                    for n, idx, far in ((e["a"], 0, 1), (e["b"], len(segs) - 1, 0)):
                        if n in attach and sh.seg_distance(node_pos[n], node_pos[n]) <= 0.01:
                            a, b = trimmed[idx]
                            end, other = (a, b) if far == 1 else (b, a)
                            L0 = dist(end, other)
                            cut = (end[0] + (other[0] - end[0]) * 0.05 / L0, end[1] + (other[1] - end[1]) * 0.05 / L0)
                            trimmed[idx] = (cut, other) if far == 1 else (other, cut)
                    if all(sh.seg_distance(a, b) > 0 for a, b in trimmed):
                        continue
                    if e["lay"] != "special":
                        V(f"edge {e['id']} crosses {rt} {rid} without special laying")
                    elif len(e["line"]) != 2:
                        V(f"special edge {e['id']} is not one straight segment")
            if end_point is not None and end_point in own:
                sh = next(s for rid, rt, s in restr if rid == own[end_point])
                ln = e["line"] if e["b"] == end_point else list(reversed(e["line"]))
                inside = [i for i, p in enumerate(ln) if sh.contains(p)]
                if inside and inside[0] < len(ln) - 1:
                    V(f"edge {e['id']} bends inside the own OKS polygon of {points[end_point][2]}")
                target = points[end_point][0]
                entry = max((dist(target, q) for q in crossing_points(ln[-2], ln[-1], sh)), default=0.0)
                nearest = min(pt_seg(target, a, b) for a, b in sh.segments())
                if entry > nearest + 0.5:
                    notes.append(f"[{vid}] point {points[end_point][2]}: enters its building {entry:.1f} m from the point, "
                                 f"nearest boundary {nearest:.1f} m (farther entry)")

        # ---- costs
        pipe = Decimal(0)
        for e in edges:
            k_spec = Decimal(1)
            if e["lay"] == "special":
                crossed = []
                for rid, rt, sh in restr + [(lid[1], "heat_network", s) for lid, s in line_shapes.items()]:
                    rule = rules.get(rt)
                    if rule and rule["crossing"] == "special" and sh.seg_distance(e["line"][0], e["line"][-1]) == 0:
                        crossed.append(Decimal(str(rule["specialCoefficient"])))
                k_spec = max(crossed, default=Decimal(1))
            k_depth = Decimal(1)
            if mode == "depth":
                kh = lambda h: Decimal(1) if h <= 3 else Decimal(1) + Decimal("0.1") * (Decimal(str(h)) - 3)
                k_depth = (kh(e["hs"]) + kh(e["he"])) / 2
            expected = (Decimal(str(e["L"])) * Decimal(str(rows[e["d"]]["newCostRubPerM"])) * k_spec * k_depth)
            if abs(expected - e["cost"]) > Decimal("0.05"):
                V(f"edge {e['id']} cost {e['cost']} vs expected {expected:.2f} (Kspec {k_spec}, Kgl {k_depth})")
            pipe += e["cost"]
        ch_cost = Decimal(0)
        for k2, f in newch.items():
            pr = f["properties"]
            ex_n, ex_d = existing_adjacency(node_pos[k2])
            dmax = max([e["d"] for e in adj.get(k2, [])] + [ex_d])
            if pr.get("diameter") != dmax:
                V(f"new chamber {pr['id']} diameter {pr.get('diameter')} != max adjacent {dmax}")
            price = next(c["costRub"] for c in cat["chambers"] if dmax <= c["maxDiameterMm"])
            if Decimal(str(pr.get("cost"))) != price:
                V(f"new chamber {pr['id']} cost {pr.get('cost')} != {price}")
            ch_cost += Decimal(str(pr.get("cost")))
        tie = sum(len(adj.get(k2, [])) for k2 in chambers)
        penalty = sum((Decimal(cat["unconnectedFixedRub"]) + Decimal(str(cat["unconnectedPerTphRub"])) * points[key(u)][1] for u in unc), Decimal(0))
        D = lambda x: Decimal(str(x))
        checks = [("chamber_construction_cost", ch_cost), ("existing_chamber_tie_in_count", tie),
                  ("existing_chamber_tie_in_cost", tie * cat["existingChamberTieInRub"]),
                  ("construction_cost", pipe + ch_cost + tie * cat["existingChamberTieInRub"]),
                  ("unconnected_penalty", penalty)]
        for fld, val in checks:
            if abs(D(S.get(fld)) - D(val)) > Decimal("0.01"):
                V(f"summary {fld} {S.get(fld)} != {val}")
        if D(S["calculated_cost"]) != D(S["construction_cost"]) + D(S["unconnected_penalty"]):
            V("calculated_cost != construction + penalty")
        total_len = sum(e["L"] for e in edges)
        if abs(float(S["new_network_length"]) - total_len) > 0.01:
            V(f"new_network_length {S['new_network_length']} != {total_len:.3f}")
        rk = cat["ranking"]
        score = rk["costWeight"] * float(S["calculated_cost"]) / rk["costBaseRub"] + rk["lengthWeight"] * float(S["new_network_length"]) / rk["lengthBaseM"]
        if abs(score - float(S["score"])) > 1e-6:
            V(f"score {S['score']} != {score:.8f}")
        ranks.append((S["rank"], float(S["score"]), vid))

        # ---- depth profile
        if mode == "depth":
            at = defaultdict(set)
            for e in edges:
                for h in (e["hs"], e["he"]):
                    if not isinstance(h, (int, float, Decimal)) or isinstance(h, bool) or float(h) < dep["minimumDepthM"] - 1e-9:
                        V(f"edge {e['id']} invalid depth {h}")
                if abs(float(e["he"]) - float(e["hs"])) > dep["maximumSlope"] * e["L"] + 1e-9:
                    V(f"edge {e['id']} slope {abs(float(e['he']) - float(e['hs'])) / e['L']:.4f}")
                if (float(e["hs"]) - 3) * (float(e["he"]) - 3) < -1e-12:
                    V(f"edge {e['id']} crosses 3 m without a split")
                at[e["a"]].add(round(float(e["hs"]), 6))
                at[e["b"]].add(round(float(e["he"]), 6))
            for n, hs in at.items():
                if len(hs) > 1:
                    V(f"depth discontinuity at {n[1]}: {hs}")
            vr = {c["type"]: c for c in dep["crossings"]}
            for e in edges:
                if e["lay"] != "special":
                    continue
                h, H = float(e["hs"]), rows[e["d"]]["heightM"]
                for rid, rt, sh in restr + [(lid[1], "heat_network", s) for lid, s in line_shapes.items()]:
                    c = vr.get(rt)
                    if c is None or sh.seg_distance(e["line"][0], e["line"][-1]) > 0:
                        continue
                    if c.get("minimumNewTopDepthM") is not None and h < c["minimumNewTopDepthM"] - 1e-9:
                        V(f"edge {e['id']} too shallow under {rt}")
                    if c.get("existingTopDepthM") is not None:
                        Ho = c.get("profileHeightM") or rows.get(lines[key(rid)][1], rows[max(rows)])["heightM"]
                        top, cl = c["existingTopDepthM"], c["minimumVerticalClearanceM"]
                        if not (h + H <= top - cl + 1e-9 or h >= top + Ho + cl - 1e-9):
                            V(f"edge {e['id']} vertical clearance to {rt} {rid} violated at h={h}")
        notes.append(f"[{vid}] rank {S['rank']} score {S['score']} cost {S['calculated_cost']} length {S['new_network_length']} "
                     f"edges {len(edges)} special {sum(e['lay'] == 'special' for e in edges)} new chambers {len(newch)} "
                     f"tech {len(tech)} tie-ins {tie} unconnected {len(unc)} connected {len(connected)}/{len(points)}")
    ranks.sort()
    if [r[0] for r in ranks] != list(range(1, len(ranks) + 1)) or [r[1] for r in ranks] != sorted(r[1] for r in ranks):
        P(f"ranks/score order wrong: {ranks}")
    if not 1 <= len(ranks) <= 3:
        P(f"{len(ranks)} variants (1..3 expected)")
    print("\n".join(notes))
    print(f"PROBLEMS: {len(problems)}")
    print("\n".join(problems[:60]))
    return 1 if problems else 0


if __name__ == "__main__":
    if len(sys.argv) != 6:
        print(__doc__)
        sys.exit(2)
    sys.exit(main(*sys.argv[1:6]))
