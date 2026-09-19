#!/usr/bin/env python3
"""
国土数値情報 鉄道データ (N02) から, アプリで描画する路線と駅の GeoJSON を作る.

出力 (core-map/src/main/assets/rail/):
- lines.geojson: 低ズーム用の路線. (区分, 事業者, 路線名) ごとに MultiLineString にまとめ, 形状を間引く.
  地理院タイルは低ズームで路線を間引いている (z4-5 は新幹線のみ, z8 は JR のみ等) ので, z11 未満はこれで描く
- stations.geojson: 駅. グループコード (N02_005g) ごとに1点にまとめる (乗換駅は1点になる)

出典: "国土数値情報 (鉄道データ)" (国土交通省) https://nlftp.mlit.go.jp/ksj/gml/datalist/KsjTmplt-N02-2024.html
ライセンス: CC BY 4.0 (2020年以降のデータ)
使い方: python3 tools/rail-data/build_rail.py
"""
import collections
import io
import json
import pathlib
import urllib.request
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[2]
CACHE = pathlib.Path(__file__).resolve().parent / ".cache"
OUT_DIR = ROOT / "core-map/src/main/assets/rail"

N02_URL = "https://nlftp.mlit.go.jp/ksj/gml/data/N02/N02-24/N02-24_GML.zip"
SECTION_ENTRY = "UTF-8/N02-24_RailroadSection.geojson"
STATION_ENTRY = "UTF-8/N02-24_Station.geojson"

# 座標の小数桁 (5桁で約1m)
PRECISION = 5
# Douglas-Peucker の許容誤差 (度). 約10m (z11 未満でしか使わないので粗くてよい)
SIMPLIFY_TOLERANCE = 0.0001


def load_n02() -> tuple[dict, dict]:
    CACHE.mkdir(exist_ok=True)
    zip_path = CACHE / "N02-24_GML.zip"
    if not zip_path.exists():
        print(f"downloading {N02_URL}")
        with urllib.request.urlopen(N02_URL) as res:
            zip_path.write_bytes(res.read())
    with zipfile.ZipFile(zip_path) as z:
        sections = json.load(io.TextIOWrapper(z.open(SECTION_ENTRY), encoding="utf-8"))
        stations = json.load(io.TextIOWrapper(z.open(STATION_ENTRY), encoding="utf-8"))
    return sections, stations


def simplify(coords: list, tolerance: float) -> list:
    """Douglas-Peucker 法で折れ線を間引く (両端は必ず残す)."""
    if len(coords) <= 2:
        return coords
    keep = [False] * len(coords)
    keep[0] = keep[-1] = True
    stack = [(0, len(coords) - 1)]
    while stack:
        start, end = stack.pop()
        (x1, y1), (x2, y2) = coords[start], coords[end]
        dx, dy = x2 - x1, y2 - y1
        norm = (dx * dx + dy * dy) ** 0.5
        max_dist, max_i = 0.0, -1
        for i in range(start + 1, end):
            px, py = coords[i]
            if norm == 0:
                dist = ((px - x1) ** 2 + (py - y1) ** 2) ** 0.5
            else:
                dist = abs(dy * px - dx * py + x2 * y1 - y2 * x1) / norm
            if dist > max_dist:
                max_dist, max_i = dist, i
        if max_dist > tolerance:
            keep[max_i] = True
            stack.append((start, max_i))
            stack.append((max_i, end))
    return [c for c, k in zip(coords, keep) if k]


def round_coords(coords: list) -> list:
    return [[round(x, PRECISION), round(y, PRECISION)] for x, y in coords]


def build_lines(sections: dict) -> dict:
    groups: dict[tuple, list] = collections.defaultdict(list)
    for f in sections["features"]:
        p = f["properties"]
        key = (p["N02_001"], p["N02_002"], p["N02_004"], p["N02_003"])
        groups[key].append(round_coords(simplify(f["geometry"]["coordinates"], SIMPLIFY_TOLERANCE)))
    features = []
    for (rail_class, operator_type, operator, line), parts in sorted(groups.items()):
        features.append({
            "type": "Feature",
            "properties": {
                # 鉄道区分 (11: 普通鉄道JR, 12: 普通鉄道, 21: 軌道 など)
                "class": int(rail_class),
                # 事業者種別 (1: 新幹線, 2: JR在来線, 3: 公営, 4: 民営, 5: 第三セクター)
                "type": int(operator_type),
                "operator": operator,
                "line": line,
            },
            "geometry": {"type": "MultiLineString", "coordinates": parts},
        })
    return {"type": "FeatureCollection", "features": features}


def build_stations(stations: dict) -> dict:
    groups: dict[str, list] = collections.defaultdict(list)
    for f in stations["features"]:
        groups[f["properties"]["N02_005g"]].append(f)
    features = []
    for group_code, members in sorted(groups.items()):
        # 駅の地物はホームの範囲を表す線なので, 全メンバの全頂点の平均を代表点にする
        points = [c for m in members for c in m["geometry"]["coordinates"]]
        x = sum(c[0] for c in points) / len(points)
        y = sum(c[1] for c in points) / len(points)
        names = collections.Counter(m["properties"]["N02_005"] for m in members)
        lines = {(m["properties"]["N02_004"], m["properties"]["N02_003"]) for m in members}
        features.append({
            "type": "Feature",
            "properties": {
                "group": group_code,
                "name": names.most_common(1)[0][0],
                # 乗り入れ路線数. ラベルの表示優先度に使う
                "lines": len(lines),
            },
            "geometry": {"type": "Point", "coordinates": [round(x, PRECISION), round(y, PRECISION)]},
        })
    return {"type": "FeatureCollection", "features": features}


def write(name: str, data: dict) -> None:
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    path = OUT_DIR / name
    path.write_text(json.dumps(data, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    print(f"wrote {path} ({path.stat().st_size} bytes, {len(data['features'])} features)")


def main() -> None:
    sections, stations = load_n02()
    write("lines.geojson", build_lines(sections))
    write("stations.geojson", build_stations(stations))


if __name__ == "__main__":
    main()
