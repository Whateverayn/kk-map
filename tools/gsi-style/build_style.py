#!/usr/bin/env python3
"""
地理院 "最適化ベクトルタイル" の標準スタイルを取得し, アプリ同梱用に加工する.

- タイル取得元を pmtiles:// から通常の XYZ (z/x/y.pbf) に置き換える
- 出典表示にリンクを付ける
- 注記から駅名を除く (駅は N02 から自前で描くため)
- 低ズーム用の鉄道線 (z11 未満) を非表示にする (路線が間引かれているので N02 から自前で描くため)
- 空白を詰めて出力する

出典: 国土地理院 最適化ベクトルタイル (https://github.com/gsi-cyberjapan/optimal_bvmap)
使い方: python3 tools/gsi-style/build_style.py
"""
import json
import pathlib
import urllib.request

SRC = "https://raw.githubusercontent.com/gsi-cyberjapan/optimal_bvmap/main/style/std.json"
XYZ = "https://cyberjapandata.gsi.go.jp/xyz/optimal_bvmap-v1/{z}/{x}/{y}.pbf"
OUT = pathlib.Path(__file__).resolve().parents[2] / "core-map/src/main/assets/gsi/std.json"
# 国土地理院コンテンツ利用規約 (公共データ利用規約 PDL1.0) に従い, 加工したことを明記する.
# MapLibre Android の出典ダイアログは <a> の文字列だけを表示するので, 文言はすべて <a> の中に入れる.
# N02 の駅データ (GeoJSON source) には出典を持たせる手段が無いため, ここに併記する.
ATTRIBUTION = (
    '<a href="https://github.com/gsi-cyberjapan/optimal_bvmap">'
    '国土地理院最適化ベクトルタイル (標準地図風スタイル) を加工して作成</a> '
    '<a href="https://nlftp.mlit.go.jp/ksj/gml/datalist/KsjTmplt-N02-2024.html">'
    '"国土数値情報 (鉄道データ)" (国土交通省) を加工して作成</a>'
)
ANNO_SOURCE_LAYER = "Anno"
# z11 未満の鉄道線レイヤー. タイル側で路線が間引かれている (z4-5 は新幹線のみ, z8 は JR のみ等)
LOW_ZOOM_RAIL_LAYER = "鉄道中心線ZL4-10"
# 注記の分類コードのうち駅名にあたるもの (タイルを実際にデコードして確認: 422 = "大阪駅" など)
STATION_ANNO_CODES = [421, 422, 423]


def exclude_station_annotations(flt):
    """注記レイヤーの filter から駅名を除く.

    filter は ["step", ["zoom"], f0, z1, f1, ...] の形なので, zoom 式を最上位に保ったまま
    各段の条件に "駅名ではない" を AND で足す.
    """
    not_station = ["!", ["in", ["get", "vt_code"], ["literal", STATION_ANNO_CODES]]]
    if isinstance(flt, list) and flt[:2] == ["step", ["zoom"]]:
        out = flt[:2]
        for i, item in enumerate(flt[2:]):
            # 偶数番目が条件, 奇数番目が zoom の閾値
            out.append(["all", item, not_station] if i % 2 == 0 else item)
        return out
    return ["all", flt, not_station]


def main() -> None:
    with urllib.request.urlopen(SRC) as res:
        style = json.load(res)

    for source in style["sources"].values():
        if source.get("type") != "vector":
            continue
        tiles = source.get("tiles", [])
        if any(t.startswith("pmtiles://") for t in tiles):
            source["tiles"] = [XYZ]
        source["attribution"] = ATTRIBUTION

    # 駅は N02 から自前で描くので, 地理院側の駅名注記を消す (二重表示を避ける). 鉄道線は z11 以上は地理院のまま使う
    for layer in style["layers"]:
        if layer.get("source-layer") == ANNO_SOURCE_LAYER and "filter" in layer:
            layer["filter"] = exclude_station_annotations(layer["filter"])
        elif layer["id"] == LOW_ZOOM_RAIL_LAYER:
            layer.setdefault("layout", {})["visibility"] = "none"

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(style, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    print(f"wrote {OUT} ({OUT.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
