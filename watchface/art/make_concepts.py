"""Concept mockups (not the face itself): ideas drawn on top of real designs, for review before building.

    python3 art/make_concepts.py art/concepts     # -> concept-*.png and concepts.png

Circuit outlines: art/concepts/data (github.com/bacinger/f1-circuits, MIT).
"""
import json
import math
import os
import sys
from PIL import Image, ImageDraw, ImageFilter, ImageFont

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import make_preview as mp
from themes import THEMES

HERE = os.path.dirname(os.path.abspath(__file__))
FONT = lambda size, w="bold": ImageFont.truetype(os.path.join(mp.FONT_DIR, f"outfit_{w}.ttf"), size)


def circle_mask(im):
    m = Image.new("L", im.size, 0)
    ImageDraw.Draw(m).ellipse((0, 0, im.size[0] - 1, im.size[1] - 1), fill=255)
    out = Image.new("RGB", im.size, (0, 0, 0))
    out.paste(im.convert("RGB"), (0, 0), m)
    return out


def circuit_points(cid, box):
    """The circuit's outline fitted into box (x0, y0, x1, y1), north up."""
    g = json.load(open(os.path.join(HERE, "concepts", "data", cid + ".geojson")))["features"][0]["geometry"]
    pts = g["coordinates"] if g["type"] == "LineString" else g["coordinates"][0]
    lat0 = sum(p[1] for p in pts) / len(pts)
    xy = [(p[0] * math.cos(math.radians(lat0)), -p[1]) for p in pts]
    xs, ys = [p[0] for p in xy], [p[1] for p in xy]
    w, h = max(xs) - min(xs), max(ys) - min(ys)
    x0, y0, x1, y1 = box
    k = min((x1 - x0) / w, (y1 - y0) / h)
    ox = x0 + ((x1 - x0) - w * k) / 2
    oy = y0 + ((y1 - y0) - h * k) / 2
    return [(ox + (x - min(xs)) * k, oy + (y - min(ys)) * k) for x, y in xy]


def pill(im, t, icon, line, progress=None, live=False):
    """The sports pill (as in face v15), optionally with a progress edge along its foot."""
    mp.sports_pill(im, t, (icon, line))
    if progress is not None:
        d = ImageDraw.Draw(im, "RGBA")
        x0, x1, y = 92, 358, 409
        d.rounded_rectangle((x0, y - 1, x1, y + 1), 1, fill=(255, 255, 255, 50))
        d.rounded_rectangle((x0, y - 2, x0 + (x1 - x0) * progress, y + 2), 2, fill=mp.color(t["major"]))
        if live:
            d.rounded_rectangle((x0, y - 2, x1, y + 2), 2, fill=(255, 40, 40, 255))


# ---- 1. Race-week circuit (after TAG Heuer's F1 edition): the next Grand Prix's track, drawn into the face
def concept_circuit():
    t = THEMES[1]
    im = mp.render(t, 0, ("f1", "Las Vegas · Race in 2d 6h")).convert("RGBA")
    layer = Image.new("RGBA", im.size, (0, 0, 0, 0))
    pts = circuit_points("us-2023", (275, 112, 395, 222))
    d = ImageDraw.Draw(layer)
    d.line(pts + [pts[0]], fill=(255, 220, 0, 255), width=4, joint="curve")
    glow = layer.filter(ImageFilter.GaussianBlur(4))
    im.alpha_composite(glow)
    im.alpha_composite(layer)
    d = ImageDraw.Draw(im, "RGBA")
    sx, sy = pts[0]
    d.ellipse((sx - 5, sy - 5, sx + 5, sy + 5), fill=(219, 0, 7, 255), outline=(255, 255, 255, 255))
    pill(im, t, "f1", "Las Vegas · Race in 2d 6h")
    return circle_mask(im)


# ---- 2. Weekend rhythm (TAG's session cues; Test days): where we are in the weekend or the match
def concept_rhythm():
    t = THEMES[0]
    im = mp.render(t, 4, ("cricket", "")).convert("RGBA")
    mp.sports_pill(im, t, ("cricket", ""))
    d = ImageDraw.Draw(im, "RGBA")
    f = FONT(17)
    txt = "NZ 312 · IND 98/2"
    cx = 55 + 63 + 248 // 2 - 26
    d.text((cx + 1, 396), txt, font=f, fill=(0, 0, 0, 204), anchor="mm")
    d.text((cx, 395), txt, font=f, fill=(255, 255, 255), anchor="mm")
    # five Test days as pips: two done (filled), today ringed in the accent
    px = cx + f.getlength(txt) / 2 + 16
    for k in range(5):
        x = px + k * 11
        if k < 2:
            d.ellipse((x - 3.5, 391.5, x + 3.5, 398.5), fill=mp.color(t["major"]))
        elif k == 2:
            d.ellipse((x - 4, 391, x + 4, 399), outline=mp.color(t["major"]), width=2)
        else:
            d.ellipse((x - 2.5, 392.5, x + 2.5, 397.5), fill=(255, 255, 255, 90))
    return circle_mask(im)


# ---- 3. Kick-off fuse (Pixel Concentric / Galaxy Radial): the pill's foot fills as the start nears
def concept_fuse():
    t = THEMES[2]
    im = mp.render(t, 5, ("football", "Newcastle vs ARS · in 2h 14m")).convert("RGBA")
    pill(im, t, "football", "Newcastle vs ARS · in 2h 14m", progress=0.9)
    return circle_mask(im)


# ---- 4. Always-on in outline (Pixel / Apple always-on faces): fewer lit pixels, longer battery
def ambient(outline):
    im = Image.new("RGB", (450, 450), (0, 0, 0))
    d = ImageDraw.Draw(im)
    t = THEMES[0]
    big = FONT(99)
    for txt, x, col in (("11", 120, (255, 255, 255)), ("33", 330, mp.color(t["minutes"])[:3])):
        if outline:
            d.text((x, 175), txt, font=big, fill=(0, 0, 0), anchor="mm", stroke_width=2, stroke_fill=col)
        else:
            d.text((x, 175), txt, font=big, fill=col, anchor="mm")
    d.text((225, 175), ":", font=FONT(90), fill=(160, 160, 160), anchor="mm")
    d.text((225, 85), "FRIDAY", font=FONT(24), fill=(170, 170, 170), anchor="mm")
    d.text((225, 113), "20 NOVEMBER", font=FONT(30), fill=(200, 200, 200), anchor="mm")
    return circle_mask(im)


def lit(im):
    """Share of OLED light: mean pixel brightness over the round screen (an OLED pixel's draw ~ its brightness)."""
    g = im.convert("L")
    m = Image.new("L", g.size, 0)
    ImageDraw.Draw(m).ellipse((0, 0, g.size[0] - 1, g.size[1] - 1), fill=255)
    total = sum(p for p, mm in zip(g.getdata(), m.getdata()) if mm)
    return total / (255 * sum(1 for mm in m.getdata() if mm))


if __name__ == "__main__":
    out = sys.argv[1]
    os.makedirs(out, exist_ok=True)
    solid, hollow = ambient(False), ambient(True)
    a, b = lit(solid), lit(hollow)
    print(f"always-on light: solid {a:.2%} of full white, outline {b:.2%} ({1 - b / a:.0%} less)")
    tiles = [
        (concept_circuit(), "1 · Race-week circuit", "The next Grand Prix's track, in the day's accent"),
        (concept_rhythm(), "2 · Match rhythm", "Test days (or F1 sessions) as pips: done · today · to come"),
        (concept_fuse(), "3 · Kick-off fuse", "The pill's foot fills over the last day; red when live"),
    ]
    for i, (im, _, _) in enumerate(tiles, 1):
        im.save(os.path.join(out, f"concept-{i}.png"))
    W, pad = 450, 30
    sheet = Image.new("RGB", (3 * W + 4 * pad, 2 * (W + 80) + pad), (12, 13, 18))  # row 2: the brief
    d = ImageDraw.Draw(sheet)
    for i, (im, title, sub) in enumerate(tiles):
        x = pad + (i % 3) * (W + pad)
        y = pad + (i // 3) * (W + 80)
        sheet.paste(im, (x, y))
        d.text((x + W // 2, y + W + 22), title, font=FONT(20), fill=(240, 240, 245), anchor="mm")
        d.text((x + W // 2, y + W + 48), sub, font=FONT(15, "semibold"), fill=(170, 172, 182), anchor="mm")
    # sixth cell: the brief
    x, y = pad + 2 * (W + pad), pad + (W + 80)
    lines = ["Rule for every idea:", "", "• wow and feel, from the best faces", "• zero added battery",
             "• no new network wakes", "• built for this watch's limits", "", "Inspired by: TAG Heuer F1 edition,",
             "Pixel Concentric, Galaxy Radial,", "(always-on idea dropped: AOD not used)"]
    for k, s in enumerate(lines):
        d.text((x + 30, y + 60 + k * 32), s, font=FONT(19 if k == 0 else 17, "bold" if k == 0 else "semibold"),
               fill=(240, 240, 245) if k == 0 else (190, 192, 200))
    sheet.save(os.path.join(out, "concepts.png"))
    print(os.path.join(out, "concepts.png"))
