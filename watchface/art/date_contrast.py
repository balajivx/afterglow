"""Choose day/date ink from the actual pixels beneath each watch-face label."""
from functools import lru_cache
import os

from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app/src/main/res/drawable-nodpi")
FONTS = os.path.join(ROOT, "app/src/main/res/font")
INKS = {
    "white": (255, 255, 255, "#ffffffff"),
    "wine": (115, 0, 15, "#ff73000f"),
    "charcoal": (16, 21, 30, "#ff10151e"),
}


def _luminance(rgb):
    linear = []
    for value in rgb:
        channel = value / 255
        linear.append(channel / 12.92 if channel <= 0.04045 else ((channel + 0.055) / 1.055) ** 2.4)
    return 0.2126 * linear[0] + 0.7152 * linear[1] + 0.0722 * linear[2]


@lru_cache(maxsize=24)
def _background(theme, style):
    image = Image.open(os.path.join(RES, f"wall_{theme}_{style + 1}.jpg")).convert("RGBA")
    overlay = Image.open(os.path.join(RES, f"overlay_{theme}_{style}.png")).convert("RGBA")
    image.alpha_composite(overlay)
    return image.convert("RGB")


@lru_cache(maxsize=96)
def choose_ink(theme, style, text, y, size):
    """Rank white and wine by contrast at the rendered glyph pixels, not whole-image averages."""
    font = ImageFont.truetype(os.path.join(FONTS, "outfit_bold.ttf"), size)
    mask = Image.new("L", (450, 450), 0)
    ImageDraw.Draw(mask).text((225, y), text, font=font, fill=255, anchor="mm")
    bounds = mask.getbbox()
    bg = _background(theme, style)
    points = []
    for py in range(bounds[1], bounds[3]):
        for px in range(bounds[0], bounds[2]):
            if mask.getpixel((px, py)) >= 128:
                points.append(_luminance(bg.getpixel((px, py))))

    ranked = []
    for name, (red, green, blue, xml_color) in INKS.items():
        ink_lum = _luminance((red, green, blue))
        ratios = sorted((max(ink_lum, value) + 0.05) / (min(ink_lum, value) + 0.05) for value in points)
        low = ratios[int(0.1 * (len(ratios) - 1))]
        average = sum(ratios) / len(ratios)
        ranked.append((0.45 * low + 0.55 * average, name, xml_color))
    score, name, xml_color = max(ranked)
    return name, xml_color, score
