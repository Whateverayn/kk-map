#!/usr/bin/env python3
"""
国土数値情報 鉄道データ (N02) から, 経路計算用の線路グラフを作る.

出力: core-map/src/main/assets/rail/graph.bin (形式は下の write_graph() を参照)

N02 の駅の形状は, ほぼすべて (99%) が線路区間そのもの (ホーム部分を切り出した区間) になっている.
そこで次のようにグラフを組む.

1. 線路区間の端点を節点, 区間を辺とする (事業者をまたいで, 物理的につながっていればつながる).
   座標の微小な誤差や数mの途切れは, 丸めと近接結合で吸収する
2. 同じ駅 (グループコード + 事業者 + 路線名) のホーム区間の端点を1つの "駅節点" にまとめる.
   ホームが複数本あっても, 経路がホームの違いだけで何通りにも分かれないようにするため
3. 駅節点と分岐点 (次数が2以外) だけを残し, その間の区間の連なりを1本の辺にまとめる

出典: "国土数値情報 (鉄道データ)" (国土交通省) https://nlftp.mlit.go.jp/ksj/gml/datalist/KsjTmplt-N02-2024.html
使い方: python3 tools/rail-data/build_graph.py
"""
import collections
import math
import pathlib
import struct
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from build_rail import OUT_DIR, load_n02, simplify  # noqa: E402

MAGIC = b"KKRG"
SCHEMA_VERSION = 1
# 座標は 1e-6 度単位の整数で持つ (約0.1m)
COORD_SCALE = 1_000_000
# 形状の間引き (度). 約3m. ハイライト表示用なので駅付近でも道路からずれない程度
SIMPLIFY_TOLERANCE = 0.00003

# 端点の照合に使う小数桁. N02 には同じ点が 1e-13 度だけずれて記録されている所があるため丸める (6桁で約0.1m)
SNAP_DIGITS = 6
# 行き止まりの端点をつなぐ距離 (m)
JOIN_DANGLING_METERS = 2.0

# 事業者名の短縮 (候補の区別表示用). 載っていないものはそのまま使う
OPERATOR_SHORT = {
    "北海道旅客鉄道": "JR北海道",
    "東日本旅客鉄道": "JR東日本",
    "東海旅客鉄道": "JR東海",
    "西日本旅客鉄道": "JR西日本",
    "四国旅客鉄道": "JR四国",
    "九州旅客鉄道": "JR九州",
    "日本貨物鉄道": "JR貨物",
}


def haversine(a, b) -> float:
    lon1, lat1 = map(math.radians, a)
    lon2, lat2 = map(math.radians, b)
    h = math.sin((lat2 - lat1) / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin((lon2 - lon1) / 2) ** 2
    return 2 * 6_371_000 * math.asin(math.sqrt(h))


def snap_coords(coords) -> tuple:
    return tuple((round(x, SNAP_DIGITS), round(y, SNAP_DIGITS)) for x, y in coords)


class UnionFind:
    def __init__(self):
        self.parent = {}

    def find(self, x):
        self.parent.setdefault(x, x)
        while self.parent[x] != x:
            self.parent[x] = self.parent[self.parent[x]]
            x = self.parent[x]
        return x

    def union(self, a, b):
        ra, rb = self.find(a), self.find(b)
        if ra != rb:
            self.parent[rb] = ra


def build(sections: dict, stations: dict):
    secs = [
        (snap_coords(f["geometry"]["coordinates"]), f["properties"]["N02_004"], f["properties"]["N02_003"])
        for f in sections["features"]
    ]
    by_geometry = {geom: i for i, (geom, _, _) in enumerate(secs)}

    # 駅 (グループ) の情報. 代表点は全ホームの頂点の平均
    groups: dict[str, dict] = {}
    station_key_of_section: dict[int, tuple] = {}
    unmatched = []
    for f in stations["features"]:
        p = f["properties"]
        g = groups.setdefault(p["N02_005g"], {"names": collections.Counter(), "points": [], "operators": set(), "lines": set()})
        g["names"][p["N02_005"]] += 1
        g["points"].extend(f["geometry"]["coordinates"])
        g["operators"].add(p["N02_004"])
        g["lines"].add(p["N02_003"])
        key = (p["N02_005g"], p["N02_004"], p["N02_003"])
        geom = snap_coords(f["geometry"]["coordinates"])
        if geom in by_geometry:
            station_key_of_section[by_geometry[geom]] = key
        else:
            unmatched.append((key, geom))

    # 1-2. 端点を節点にし, 同じ駅のホーム区間の端点をまとめる
    uf = UnionFind()
    station_endpoints: dict[tuple, list] = collections.defaultdict(list)
    for i, (geom, _, _) in enumerate(secs):
        uf.find(geom[0])
        uf.find(geom[-1])
        key = station_key_of_section.get(i)
        if key is not None:
            station_endpoints[key] += [geom[0], geom[-1]]

    # 線路区間と形状が一致しない駅は, 同じ路線の最寄りの端点に付ける
    endpoints_by_line: dict[tuple, set] = collections.defaultdict(set)
    for geom, operator, line in secs:
        endpoints_by_line[(operator, line)] |= {geom[0], geom[-1]}
    for key, geom in unmatched:
        candidates = endpoints_by_line.get((key[1], key[2]))
        if not candidates:
            print(f"warning: 駅を線路に付けられない: {key}", file=sys.stderr)
            continue
        center = (sum(c[0] for c in geom) / len(geom), sum(c[1] for c in geom) / len(geom))
        station_endpoints[key].append(min(candidates, key=lambda c: haversine(c, center)))

    for key, pts in station_endpoints.items():
        for pt in pts[1:]:
            uf.union(pts[0], pt)

    # 行き止まりの端点のうち, すぐ近くに別の端点があるものはつなぐ (N02 には数mずれて途切れている所がある)
    degree = collections.Counter()
    for geom, _, _ in secs:
        degree[geom[0]] += 1
        degree[geom[-1]] += 1
    buckets: dict = collections.defaultdict(list)
    for pt in degree:
        buckets[(round(pt[0] * 1000), round(pt[1] * 1000))].append(pt)
    joined = 0
    for pt, n in degree.items():
        if n != 1:
            continue
        bx, by = round(pt[0] * 1000), round(pt[1] * 1000)
        near = [
            other
            for dx in (-1, 0, 1) for dy in (-1, 0, 1)
            for other in buckets[(bx + dx, by + dy)]
            if other != pt and haversine(pt, other) <= JOIN_DANGLING_METERS
        ]
        if near:
            uf.union(pt, min(near, key=lambda o: haversine(pt, o)))
            joined += 1
    node_station = {uf.find(pts[0]): key for key, pts in station_endpoints.items()}

    # 3. 駅節点と分岐点だけを残して, 区間の連なりを1本の辺にまとめる
    adjacency: dict = collections.defaultdict(list)
    for i, (geom, _, _) in enumerate(secs):
        a, b = uf.find(geom[0]), uf.find(geom[-1])
        if a == b:
            continue  # ホーム区間 (駅節点の中に畳まれる) や, 駅を持たない小さな環
        adjacency[a].append((i, b, False))
        adjacency[b].append((i, a, True))

    def is_kept(n) -> bool:
        return n in node_station or len(adjacency[n]) != 2

    visited = set()
    chains = []
    for start in list(adjacency):
        if not is_kept(start):
            continue
        for sec_id, nxt, reverse in adjacency[start]:
            if sec_id in visited:
                continue
            parts = [(sec_id, reverse)]
            visited.add(sec_id)
            prev_sec, node = sec_id, nxt
            while not is_kept(node):
                # 次数2の節点: 来た区間ではない方へ進む
                sec2, nxt2, rev2 = next(e for e in adjacency[node] if e[0] != prev_sec)
                if sec2 in visited:
                    break
                parts.append((sec2, rev2))
                visited.add(sec2)
                prev_sec, node = sec2, nxt2
            chains.append((start, node, parts))

    # 節点に番号を振る
    node_ids: dict = {}
    for a, b, _ in chains:
        node_ids.setdefault(a, len(node_ids))
        node_ids.setdefault(b, len(node_ids))

    group_codes = sorted(groups)
    group_index = {code: i for i, code in enumerate(group_codes)}

    edges = []
    for a, b, parts in chains:
        coords = []
        # 路線名ごとの距離 (通った順). 分岐駅の構内などで別の路線名が短く混ざるので, 表示側で距離で間引く
        lines: list[list] = []
        for sec_id, reverse in parts:
            geom, _, line = secs[sec_id]
            pts = list(reversed(geom)) if reverse else list(geom)
            section_length = sum(haversine(pts[i], pts[i + 1]) for i in range(len(pts) - 1))
            coords.extend(pts if not coords else pts[1:])
            if lines and lines[-1][0] == line:
                lines[-1][1] += section_length
            else:
                lines.append([line, section_length])
        length = sum(x[1] for x in lines)
        edges.append((node_ids[a], node_ids[b], length, lines, simplify(coords, SIMPLIFY_TOLERANCE)))

    nodes = [None] * len(node_ids)
    for n, i in node_ids.items():
        key = node_station.get(n)
        nodes[i] = (n, group_index[key[0]] if key else -1, key[2] if key else None)

    group_rows = []
    for code in group_codes:
        g = groups[code]
        pts = g["points"]
        group_rows.append((
            int(code),
            g["names"].most_common(1)[0][0],
            (sum(c[0] for c in pts) / len(pts), sum(c[1] for c in pts) / len(pts)),
            sorted(OPERATOR_SHORT.get(o, o) for o in g["operators"]),
            sorted(g["lines"]),
        ))
    print(
        f"groups={len(group_rows)} nodes={len(nodes)} edges={len(edges)} "
        f"unmatched_stations={len(unmatched)} joined_dangling={joined}"
    )
    return group_rows, nodes, edges


def write_graph(path: pathlib.Path, group_rows, nodes, edges) -> None:
    """
    バイナリ形式 (ビッグエンディアン). 座標は (経度, 緯度) を COORD_SCALE 倍した int32.

    magic "KKRG", schema version u16
    strings:  count u32, [len u16, utf8]
    groups:   count u32, [code i32, name str, lon i32, lat i32, operators (count u8, [str]), lines (count u8, [str])]
    nodes:    count u32, [lon i32, lat i32, group i32 (-1: 駅ではない), line str (駅でなければ 0xFFFFFFFF)]
    edges:    count u32, [from u32, to u32, length_m f32, lines (count u8, [str, length_m f32]),
               points (count u32, [lon i32, lat i32])]
    str は strings の添字 (u32)
    """
    strings: dict[str, int] = {}

    def s(text: str) -> int:
        return strings.setdefault(text, len(strings))

    def coord(c):
        return round(c[0] * COORD_SCALE), round(c[1] * COORD_SCALE)

    body = bytearray()
    body += struct.pack(">I", len(group_rows))
    for code, name, center, operators, lines in group_rows:
        body += struct.pack(">iIii", code, s(name), *coord(center))
        body += struct.pack(">B", len(operators)) + b"".join(struct.pack(">I", s(o)) for o in operators)
        body += struct.pack(">B", len(lines)) + b"".join(struct.pack(">I", s(x)) for x in lines)
    body += struct.pack(">I", len(nodes))
    for point, group, line in nodes:
        body += struct.pack(">iiiI", *coord(point), group, s(line) if line else 0xFFFFFFFF)
    body += struct.pack(">I", len(edges))
    for a, b, length, lines, points in edges:
        body += struct.pack(">IIf", a, b, length)
        body += struct.pack(">B", len(lines)) + b"".join(struct.pack(">If", s(x), m) for x, m in lines)
        body += struct.pack(">I", len(points)) + b"".join(struct.pack(">ii", *coord(p)) for p in points)

    head = bytearray(MAGIC + struct.pack(">H", SCHEMA_VERSION))
    head += struct.pack(">I", len(strings))
    for text in strings:  # dict は挿入順 = 添字順
        raw = text.encode("utf-8")
        head += struct.pack(">H", len(raw)) + raw

    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(bytes(head + body))
    print(f"wrote {path} ({path.stat().st_size} bytes)")


def main() -> None:
    sections, stations = load_n02()
    write_graph(OUT_DIR / "graph.bin", *build(sections, stations))


if __name__ == "__main__":
    main()
