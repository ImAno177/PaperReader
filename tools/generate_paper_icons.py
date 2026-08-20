#!/usr/bin/env python3
"""Regenerate PaperReader's pinned Material Symbols vector assets."""

from __future__ import annotations

import argparse
import tempfile
import urllib.request
from pathlib import Path

from fontTools.pens.boundsPen import BoundsPen
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont

MATERIAL_COMMIT = "50f0603134ce7b70b2d71b686cc13e8b57ccb74c"
MATERIAL_BASE = (
    "https://raw.githubusercontent.com/google/material-design-icons/"
    f"{MATERIAL_COMMIT}/variablefont/MaterialSymbolsOutlined%5BFILL,GRAD,opsz,wght%5D"
)

ICONS = {
    "add": "add",
    "back": "arrow_back",
    "bookmark_add": "bookmark_add",
    "bookmark_remove": "bookmark_remove",
    "bookmarks": "bookmarks",
    "close": "close",
    "copy": "content_copy",
    "delete": "delete",
    "done": "check_circle",
    "download": "download",
    "edit": "edit",
    "error": "error",
    "folder": "folder",
    "forward": "chevron_right",
    "grid": "grid_view",
    "history": "history",
    "info": "info",
    "library": "menu_book",
    "list": "list",
    "mark_read": "mark_email_read",
    "more_horizontal": "more_horiz",
    "more_vertical": "more_vert",
    "notifications_off": "notifications_off",
    "notifications_on": "notifications_active",
    "offline": "offline_pin",
    "open_external": "open_in_new",
    "palette": "palette",
    "pdf": "picture_as_pdf",
    "public": "public",
    "search": "search",
    "sort": "sort",
    "sync": "sync",
    "updates": "update",
    "upload": "upload",
}

VIEWPORT = 2400
DRAWABLE_SIZE = 2000


def download(url: str, target: Path) -> None:
    with urllib.request.urlopen(url) as response, target.open("wb") as output:
        output.write(response.read())


def glyph_path(font: TTFont, codepoint: int) -> str:
    glyph_set = font.getGlyphSet()
    glyph_name = font.getBestCmap().get(codepoint)
    if glyph_name is None:
        raise ValueError(f"No glyph for U+{codepoint:04X}")
    glyph = glyph_set[glyph_name]
    bounds_pen = BoundsPen(glyph_set)
    glyph.draw(bounds_pen)
    if bounds_pen.bounds is None:
        raise ValueError(f"Empty glyph for U+{codepoint:04X}")
    left, bottom, right, top = bounds_pen.bounds
    width = right - left
    height = top - bottom
    scale = min(DRAWABLE_SIZE / width, DRAWABLE_SIZE / height)
    x_offset = (VIEWPORT - width * scale) / 2 - left * scale
    y_offset = (VIEWPORT - height * scale) / 2 + top * scale
    path_pen = SVGPathPen(glyph_set, ntos=lambda value: str(int(round(value))))
    glyph.draw(TransformPen(path_pen, (scale, 0, 0, -scale, x_offset, y_offset)))
    return path_pen.getCommands()


def codepoints(path: Path) -> dict[str, int]:
    return {
        name: int(value, 16)
        for name, value in (line.split() for line in path.read_text(encoding="utf-8").splitlines())
    }


def vector_xml(path_data: str) -> str:
    return f"""<?xml version=\"1.0\" encoding=\"utf-8\"?>
<vector xmlns:android=\"http://schemas.android.com/apk/res/android\"
    android:width=\"24dp\"
    android:height=\"24dp\"
    android:viewportWidth=\"{VIEWPORT}\"
    android:viewportHeight=\"{VIEWPORT}\">
    <path
        android:fillColor=\"#FF000000\"
        android:fillType=\"nonZero\"
        android:pathData=\"{path_data}\" />
</vector>
"""


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="paperreader-icons-") as raw_temp:
        temp = Path(raw_temp)
        material_font_path = temp / "material.ttf"
        material_codepoints_path = temp / "material.codepoints"
        download(f"{MATERIAL_BASE}.ttf", material_font_path)
        download(f"{MATERIAL_BASE}.codepoints", material_codepoints_path)

        material_font = instantiateVariableFont(
            TTFont(material_font_path),
            {"FILL": 0, "GRAD": 0, "opsz": 24, "wght": 400},
            inplace=False,
        )
        material_codes = codepoints(material_codepoints_path)

        for semantic_name, material_name in sorted(ICONS.items()):
            output = args.output / f"ic_material_symbol_{semantic_name}.xml"
            output.write_text(
                vector_xml(glyph_path(material_font, material_codes[material_name])),
                encoding="utf-8",
            )


if __name__ == "__main__":
    main()
