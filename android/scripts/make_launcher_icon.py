#!/usr/bin/env python3
"""Derive the Android launcher icon from the iOS app icon.

The iOS icon is an orange Ik Onkar on a vertical navy gradient. Android
needs it as an adaptive icon (minSdk 26): a gradient background drawable
(res/drawable/ic_launcher_background.xml, hand-written) plus this script's
output — a transparent foreground PNG per density, with the glyph scaled into
the 66dp safe circle of the 108dp canvas so no launcher mask clips it — and a
512x512 Play Store icon.

Needs Pillow: python3 -m venv /tmp/v && /tmp/v/bin/pip install pillow
Run from the repo root: /tmp/v/bin/python android/scripts/make_launcher_icon.py
"""
import math
from pathlib import Path

from PIL import Image

SOURCE = Path("Ardaas/Assets.xcassets/AppIcon.appiconset/AppIcon.png")
RES = Path("android/app/src/main/res")
PLAY_ICON = Path("docs/play/icon-512.png")
GLYPH = (255, 153, 51)
SAFE_RADIUS = 33 / 108  # of the canvas side; adaptive-icon safe zone is a 66dp circle
DENSITIES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}


def glyph_layer(src: Image.Image) -> Image.Image:
    """The glyph alone, on transparency: alpha from how far each pixel sits
    between its row's background colour and the glyph colour (red channel,
    where the two differ most), so anti-aliased edges survive."""
    w, h = src.size
    px = src.load()
    out = Image.new("RGBA", src.size)
    opx = out.load()
    for y in range(h):
        bg_r = px[0, y][0]
        for x in range(w):
            a = (px[x, y][0] - bg_r) / (GLYPH[0] - bg_r)
            a = max(0.0, min(1.0, a))
            if a > 0:
                opx[x, y] = (*GLYPH, round(a * 255))
    return out


def fit_to_safe_zone(glyph: Image.Image, side: int) -> Image.Image:
    bbox = glyph.getbbox()
    cropped = glyph.crop(bbox)
    cx, cy = cropped.width / 2, cropped.height / 2
    alpha = cropped.getchannel("A").load()
    radius = max(
        math.hypot(x + 0.5 - cx, y + 0.5 - cy)
        for y in range(cropped.height)
        for x in range(cropped.width)
        if alpha[x, y] > 8
    )
    scale = (SAFE_RADIUS * side) / radius
    size = (round(cropped.width * scale), round(cropped.height * scale))
    scaled = cropped.resize(size, Image.LANCZOS)
    canvas = Image.new("RGBA", (side, side))
    canvas.paste(scaled, ((side - size[0]) // 2, (side - size[1]) // 2), scaled)
    return canvas


def main() -> None:
    src = Image.open(SOURCE).convert("RGB")
    glyph = glyph_layer(src)
    master = fit_to_safe_zone(glyph, 432 * 4)
    for name, factor in DENSITIES.items():
        side = round(108 * factor)
        out = RES / f"mipmap-{name}" / "ic_launcher_foreground.png"
        out.parent.mkdir(parents=True, exist_ok=True)
        master.resize((side, side), Image.LANCZOS).save(out, optimize=True)
    PLAY_ICON.parent.mkdir(parents=True, exist_ok=True)
    # Play requires a 32-bit PNG.
    src.resize((512, 512), Image.LANCZOS).convert("RGBA").save(PLAY_ICON, optimize=True)


if __name__ == "__main__":
    main()
