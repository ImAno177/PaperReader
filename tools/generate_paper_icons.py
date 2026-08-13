#!/usr/bin/env python3
"""Regenerate PaperReader's pinned Tabler and Material Symbols vector assets."""

from __future__ import annotations

import argparse
import re
import tarfile
import tempfile
import urllib.request
from pathlib import Path

from fontTools.pens.boundsPen import BoundsPen
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont

TABLER_VERSION = "3.46.0"
TABLER_TARBALL = (
    "https://registry.npmjs.org/@tabler/icons-webfont/-/"
    f"icons-webfont-{TABLER_VERSION}.tgz"
)
MATERIAL_COMMIT = "50f0603134ce7b70b2d71b686cc13e8b57ccb74c"
MATERIAL_BASE = (
    "https://raw.githubusercontent.com/google/material-design-icons/"
    f"{MATERIAL_COMMIT}/variablefont/MaterialSymbolsOutlined%5BFILL,GRAD,opsz,wght%5D"
)

ICONS = {
    "add": ("plus", "add"),
    "back": ("arrow-left", "arrow_back"),
    "bookmark_add": ("bookmark-plus", "bookmark_add"),
    "bookmark_remove": ("bookmark-minus", "bookmark_remove"),
    "bookmarks": ("bookmarks", "bookmarks"),
    "close": ("x", "close"),
    "copy": ("copy", "content_copy"),
    "delete": ("trash", "delete"),
    "done": ("circle-check", "check_circle"),
    "download": ("download", "download"),
    "edit": ("edit", "edit"),
    "error": ("alert-circle", "error"),
    "folder": ("folder", "folder"),
    "forward": ("chevron-right", "chevron_right"),
    "grid": ("grid-dots", "grid_view"),
    "history": ("history", "history"),
    "info": ("info-circle", "info"),
    "library": ("book", "menu_book"),
    "list": ("list", "list"),
    "mark_read": ("mail-check", "mark_email_read"),
    "more_horizontal": ("dots", "more_horiz"),
    "more_vertical": ("dots-vertical", "more_vert"),
    "notifications_off": ("bell-off", "notifications_off"),
    "notifications_on": ("bell", "notifications_active"),
    "offline": ("pin", "offline_pin"),
    "open_external": ("external-link", "open_in_new"),
    "palette": ("palette", "palette"),
    "pdf": ("file-type-pdf", "picture_as_pdf"),
    "public": ("world", "public"),
    "search": ("search", "search"),
    "sort": ("sort-ascending", "sort"),
    "sync": ("refresh", "sync"),
    "updates": ("refresh", "update"),
    "upload": ("upload", "upload"),
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


def tabler_codepoints(path: Path) -> dict[str, int]:
    css = path.read_text(encoding="utf-8")
    return {
        name: int(value, 16)
        for name, value in re.findall(
            r"\.ti-([a-z0-9-]+):before\s*\{\s*content:\s*[\"']\\([0-9a-f]+)",
            css,
        )
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
        tabler_archive = temp / "tabler.tgz"
        material_font_path = temp / "material.ttf"
        material_codepoints_path = temp / "material.codepoints"
        download(TABLER_TARBALL, tabler_archive)
        with tarfile.open(tabler_archive) as archive:
            for member in archive.getmembers():
                target = (temp / member.name).resolve()
                if temp.resolve() not in target.parents:
                    raise ValueError(f"Unsafe archive member: {member.name}")
                archive.extract(member, temp)
        download(f"{MATERIAL_BASE}.ttf", material_font_path)
        download(f"{MATERIAL_BASE}.codepoints", material_codepoints_path)

        tabler_font = TTFont(temp / "package/dist/fonts/tabler-icons-200.ttf")
        tabler_codes = tabler_codepoints(temp / "package/dist/tabler-icons-200.css")
        material_font = instantiateVariableFont(
            TTFont(material_font_path),
            {"FILL": 0, "GRAD": 0, "opsz": 24, "wght": 400},
            inplace=False,
        )
        material_codes = codepoints(material_codepoints_path)

        for semantic_name, (tabler_name, material_name) in sorted(ICONS.items()):
            assets = (
                ("tabler", tabler_font, tabler_codes[tabler_name]),
                ("material_symbol", material_font, material_codes[material_name]),
            )
            for family, font, point in assets:
                output = args.output / f"ic_{family}_{semantic_name}.xml"
                output.write_text(vector_xml(glyph_path(font, point)), encoding="utf-8")


if __name__ == "__main__":
    main()
