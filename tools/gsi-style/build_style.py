#!/usr/bin/env python3
"""
地理院 "最適化ベクトルタイル" の標準スタイルを取得し, アプリ同梱用に加工する.

- タイル取得元を pmtiles:// から通常の XYZ (z/x/y.pbf) に置き換える
- 出典表示にリンクを付ける
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
# 国土地理院コンテンツ利用規約 (公共データ利用規約 PDL1.0) に従い, 加工したことを明記する
ATTRIBUTION = (
    '<a href="https://github.com/gsi-cyberjapan/optimal_bvmap">'
    '国土地理院最適化ベクトルタイル (標準地図風スタイル)</a>を加工して作成'
)


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

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(style, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    print(f"wrote {OUT} ({OUT.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
