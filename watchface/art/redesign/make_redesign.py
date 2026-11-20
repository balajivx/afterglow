"""Four redesign directions for the Gunners x Scuderia face, same data, rendered on real photos for review.

    python3 art/redesign/make_redesign.py art/redesign/out      # -> <concept>-<theme>.png, sheet.png

Every element of today's face is kept: time, weekday + date, battery, heart rate, FPL countdown, steps (vs goal),
calories (vs goal), the sports line with its sport icon and rail, and the day's photo in the day's theme.
Fonts: Outfit (in the face), Bebas Neue and Barlow Condensed (OFL, art/redesign/fonts). Drawn at 2x, then halved.
"""
import math
import os
import sys

from PIL import Image, ImageDraw, ImageEnhance, ImageFilter, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
sys.path.insert(0, os.path.join(os.path.dirname(ROOT), "watch-sports", "art"))
import icons  # noqa: E402  (the sports widget's icons, shared with the face)

K = 2                      # supersampling
S = 450 * K
FONT = os.path.join(HERE, "fonts")
FACE_FONT = os.path.join(ROOT, "app", "src", "main", "res", "font")


def f(name, size):
    path = os.path.join(FONT, name) if os.path.exists(os.path.join(FONT, name)) else os.path.join(FACE_FONT, name)
    return ImageFont.truetype(path, int(size * K))


BEBAS, BARLOW_B, BARLOW_SB, BARLOW_M = "BebasNeue-Regular.ttf", "BarlowCondensed-Bold.ttf", "BarlowCondensed-SemiBold.ttf", "BarlowCondensed-Medium.ttf"
OUT_B, OUT_SB, OUT_L = "outfit_bold.ttf", "outfit_semibold.ttf", "outfit_light.ttf"

# Today's real readings (20 Nov): what the face would show
DATA = dict(hh="10", mm="08", weekday="FRIDAY", wd="FRI", day="20", month="NOVEMBER", mon="NOV", battery=73, hr=63,
            steps=6153, steps_goal=8000, kcal=300, kcal_goal=450, fpl="1d 8h", fpl_pct=0.82,
            sport="cricket", line="LIVE · NZ 368 · IND 159/1", rail_days=2)

THEMES = {
    "arsenal": dict(photo="arsenal-2", accent=(219, 0, 7), accent2=(255, 255, 255), gold=(245, 205, 115),
                    band=(120, 0, 8), ring=[(255, 70, 80), (245, 205, 115), (255, 255, 255)], focus=(0.5, 0.45)),
    "ferrari": dict(photo="ferrari-4", accent=(255, 220, 0), accent2=(219, 0, 7), gold=(255, 220, 0),
                    band=(150, 0, 0), ring=[(255, 60, 50), (255, 220, 0), (255, 255, 255)], focus=(0.55, 0.5)),
    "space": dict(photo="space-5", accent=(0, 245, 255), accent2=(185, 110, 255), gold=(210, 240, 255),
                  band=(10, 14, 48), ring=[(185, 110, 255), (0, 245, 255), (255, 75, 175)], focus=(0.5, 0.5)),
}


# ---- helpers

def photo(name, focus=(0.5, 0.5), size=S):
    im = Image.open(os.path.join(ROOT, "art", "source", name + ".jpg")).convert("RGB")
    side = min(im.size)
    cx, cy = im.width * focus[0], im.height * focus[1]
    left = int(min(max(cx - side / 2, 0), im.width - side))
    top = int(min(max(cy - side / 2, 0), im.height - side))
    return im.crop((left, top, left + side, top + side)).resize((size, size), Image.LANCZOS)


def grade(im, color=1.15, contrast=1.08, bright=1.0):
    im = ImageEnhance.Color(im).enhance(color)
    im = ImageEnhance.Contrast(im).enhance(contrast)
    return ImageEnhance.Brightness(im).enhance(bright)


def vgrad(size, stops):
    """Vertical alpha gradient: stops = [(y 0-1, alpha 0-255), ...] of black."""
    g = Image.new("L", (1, 256))
    for y in range(256):
        t = y / 255
        for (y0, a0), (y1, a1) in zip(stops, stops[1:]):
            if y0 <= t <= y1:
                u = 0 if y1 == y0 else (t - y0) / (y1 - y0)
                u = u * u * (3 - 2 * u)
                g.putpixel((0, y), int(a0 + (a1 - a0) * u))
                break
    return g.resize(size)


def shade(im, stops, colour=(0, 0, 0)):
    layer = Image.new("RGB", im.size, colour)
    return Image.composite(layer, im, vgrad(im.size, stops))


def radial_dark(im, inner=0.0, outer=0.75, centre_alpha=140, edge_alpha=40):
    m = Image.new("L", im.size, 0)
    px = m.load()
    c = im.size[0] / 2
    for y in range(0, im.size[1], 2):
        for x in range(0, im.size[0], 2):
            r = math.hypot(x - c, y - c) / c
            t = min(1, max(0, (r - inner) / (outer - inner)))
            v = int(centre_alpha + (edge_alpha - centre_alpha) * t)
            px[x, y] = v
            if x + 1 < im.size[0]: px[x + 1, y] = v
            if y + 1 < im.size[1]:
                px[x, y + 1] = v
                if x + 1 < im.size[0]: px[x + 1, y + 1] = v
    return Image.composite(Image.new("RGB", im.size, (0, 0, 0)), im, m)


def text(d, xy, s, font, fill, anchor="la", tracking=0, shadow=None):
    if tracking:
        # letter-spaced text, drawn glyph by glyph
        w = sum(font.getlength(ch) for ch in s) + tracking * K * (len(s) - 1)
        x, y = xy
        if anchor[0] == "m": x -= w / 2
        elif anchor[0] == "r": x -= w
        for ch in s:
            if shadow: d.text((x + K, y + K), ch, font=font, fill=shadow, anchor="l" + anchor[1])
            d.text((x, y), ch, font=font, fill=fill, anchor="l" + anchor[1])
            x += font.getlength(ch) + tracking * K
        return
    if shadow:
        d.text((xy[0] + K * 1.5, xy[1] + K * 1.5), s, font=font, fill=shadow, anchor=anchor)
    d.text(xy, s, font=font, fill=fill, anchor=anchor)


def arc(d, box, a0, a1, width, fill, round_caps=True):
    """Arc from a0 to a1 (degrees, 0 = 3 o'clock, clockwise) with round caps."""
    d.arc(box, a0, a1, fill=fill, width=int(width))
    if round_caps and a1 > a0:
        cx, cy = (box[0] + box[2]) / 2, (box[1] + box[3]) / 2
        r = (box[2] - box[0]) / 2 - width / 2
        for a in (a0, a1):
            x, y = cx + r * math.cos(math.radians(a)), cy + r * math.sin(math.radians(a))
            d.ellipse((x - width / 2, y - width / 2, x + width / 2, y + width / 2), fill=fill)


def glass(im, box, radius, tint=(255, 255, 255, 34), border=(255, 255, 255, 90), blur=18):
    """Frosted glass: the photo blurred inside a rounded box, a light tint and a hairline highlight."""
    x0, y0, x1, y1 = [int(v) for v in box]
    region = im.crop((x0, y0, x1, y1)).filter(ImageFilter.GaussianBlur(blur * K))
    region = ImageEnhance.Brightness(region).enhance(0.82)
    mask = Image.new("L", region.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, region.size[0] - 1, region.size[1] - 1), radius, fill=255)
    im.paste(region, (x0, y0), mask)
    ov = Image.new("RGBA", im.size, (0, 0, 0, 0))
    od = ImageDraw.Draw(ov)
    od.rounded_rectangle(box, radius, fill=tint, outline=border, width=K)
    im.paste(Image.alpha_composite(im.convert("RGBA"), ov).convert("RGB"))


def icon(im, name, x, y, size, rgb):
    layer = Image.new("RGBA", im.size, (0, 0, 0, 0))
    icons.draw(layer, name, (int(x), int(y), int(size)), tuple(rgb) + (255,))
    im.paste(Image.alpha_composite(im.convert("RGBA"), layer).convert("RGB"))


def heart(d, x, y, r, fill):
    d.ellipse((x - r, y - r / 2, x, y + r / 2), fill=fill)
    d.ellipse((x, y - r / 2, x + r, y + r / 2), fill=fill)
    d.polygon([(x - r + K, y + K), (x + r - K, y + K), (x, y + r * 1.25)], fill=fill)


def finish(im):
    im = im.resize((450, 450), Image.LANCZOS)
    m = Image.new("L", (450, 450), 0)
    ImageDraw.Draw(m).ellipse((0, 0, 449, 449), fill=255)
    out = Image.new("RGB", (450, 450), (0, 0, 0))
    out.paste(im, (0, 0), m)
    return out


def c(v):
    return int(v * K)


# ---- 1. Broadcast: F1 TV / Sky Sports graphics. One hero (the time), a TV lower third for the sport.

def broadcast(t):
    D = DATA
    im = grade(photo(t["photo"], t["focus"]), 1.2, 1.1)
    im = shade(im, [(0, 150), (0.22, 30), (0.45, 40), (0.68, 205), (1, 255)])
    d = ImageDraw.Draw(im, "RGBA")
    acc = t["accent"]
    # top: telemetry readout
    heart(d, c(178), c(64), c(6), (255, 70, 80))
    text(d, (c(190), c(64)), str(D["hr"]), f(BARLOW_SB, 20), (255, 255, 255), "lm")
    d.rounded_rectangle((c(238), c(57), c(258), c(71)), c(2), outline=(255, 255, 255, 220), width=c(1.5))
    d.rectangle((c(240), c(59), c(240 + 16 * D["battery"] / 100), c(69)), fill=acc)
    d.rectangle((c(259), c(61), c(261), c(67)), fill=(255, 255, 255, 220))
    text(d, (c(266), c(64)), f'{D["battery"]}%', f(BARLOW_SB, 20), (255, 255, 255), "lm")
    # date: small caps over a short accent rule, on a soft band so busy photos don't swallow it
    band = Image.new("RGBA", (c(260), c(44)), (0, 0, 0, 0))
    for x in range(c(260)):
        ImageDraw.Draw(band).line((x, 0, x, c(44)), fill=(0, 0, 0, int(150 * (1 - x / c(260)) ** 1.4)))
    im.paste(band, (c(40), c(146)), band)
    d = ImageDraw.Draw(im, "RGBA")
    d.rectangle((c(62), c(150), c(92), c(153)), fill=acc)
    text(d, (c(62), c(170)), D["weekday"] + "  ·  " + D["day"] + " " + D["mon"], f(BARLOW_SB, 21), (255, 255, 255), "lm", tracking=1.5)
    # the hero: huge condensed time, flush left
    big = f(BEBAS, 150)
    text(d, (c(58), c(305)), D["hh"], big, (255, 255, 255), "ls", shadow=(0, 0, 0, 150))
    hw = big.getlength(D["hh"])
    text(d, (c(58) + hw + c(6), c(305)), D["mm"], big, acc, "ls", shadow=(0, 0, 0, 150))
    # right column: three slim gauges, rev-counter style
    for k, (lab, val, pct, col) in enumerate([("STEPS", f'{D["steps"]:,}', D["steps"] / D["steps_goal"], t["ring"][0]),
                                              ("KCAL", str(D["kcal"]), D["kcal"] / D["kcal_goal"], t["ring"][1]),
                                              ("FPL", D["fpl"], D["fpl_pct"], t["ring"][2])]):
        y = c(176 + k * 46)
        text(d, (c(392), y), val, f(BARLOW_B, 21), (255, 255, 255), "rs")
        text(d, (c(392), y + c(14)), lab, f(BARLOW_M, 12), (255, 255, 255, 170), "rs", tracking=1)
        d.rounded_rectangle((c(330), y + c(19), c(392), y + c(22)), c(1.5), fill=(255, 255, 255, 60))
        d.rounded_rectangle((c(330), y + c(19), c(330 + 62 * min(1, pct)), y + c(22)), c(1.5), fill=col)
    # lower third: a team-colour tab (sport icon) and the line, with the rail as a progress edge
    x0, x1, y0, y1 = c(76), c(380), c(342), c(378)
    d.polygon([(x0, y0), (x0 + c(44), y0), (x0 + c(36), y1), (x0 - c(8), y1)], fill=acc + (255,))
    d.rectangle((x0 + c(40), y0, x1, y1), fill=(8, 8, 10, 235))
    icon(im, D["sport"], x0 + c(6), y0 + c(7), c(22), (0, 0, 0) if sum(acc) > 500 else (255, 255, 255))
    d = ImageDraw.Draw(im, "RGBA")
    text(d, (x0 + c(52), (y0 + y1) / 2), D["line"].upper(), f(BARLOW_SB, 21), (255, 255, 255), "lm", tracking=0.5)
    for k in range(5):
        xa = x0 + c(44) + k * c(60)
        d.rectangle((xa, y1 + c(3), xa + c(54), y1 + c(6)), fill=acc + (255,) if k < D["rail_days"] else (255, 255, 255, 60))
    return finish(im)


# ---- 2. Concentric: Pixel Concentric / Apple Activity rings. Data on the edge, a calm centre.

def concentric(t):
    D = DATA
    im = grade(photo(t["photo"], t["focus"]), 0.75, 1.05, 0.9)
    im = radial_dark(im, 0.0, 0.95, 175, 120)
    d = ImageDraw.Draw(im, "RGBA")
    # three rings round the bezel, open at the bottom for the sports line (from 150° to 30°, clockwise via the top)
    a0, span = 145, 250
    for k, (pct, col) in enumerate([(D["steps"] / D["steps_goal"], t["ring"][0]), (D["kcal"] / D["kcal_goal"], t["ring"][1]),
                                    (D["battery"] / 100, t["ring"][2])]):
        inset = c(14 + k * 17)
        box = (inset, inset, S - inset, S - inset)
        arc(d, box, a0, a0 + span, c(11), tuple(col) + (55,))
        arc(d, box, a0, a0 + span * min(1, pct), c(11), tuple(col) + (255,))
    # (no ring labels, as on Apple's and Pixel's ring faces: the colour says which; values on a long-press/tap)
    # centre: weekday/date, then a light hour against a bold minute
    text(d, (S / 2, c(150)), D["wd"] + " " + D["day"] + " " + D["mon"], f(OUT_SB, 17), (255, 255, 255, 220), "mm", tracking=2)
    hf, mf = f(OUT_L, 112), f(OUT_B, 112)
    w = hf.getlength(D["hh"]) + mf.getlength(D["mm"]) + c(8)
    x = S / 2 - w / 2
    text(d, (x, c(262)), D["hh"], hf, (255, 255, 255), "ls")
    text(d, (x + hf.getlength(D["hh"]) + c(8), c(262)), D["mm"], mf, t["accent"], "ls")
    # chips: heart rate and FPL
    for k, (cx, s, col) in enumerate([(S / 2 - c(58), f'{D["hr"]} BPM', (255, 90, 100)), (S / 2 + c(58), "FPL " + D["fpl"], t["gold"])]):
        fo = f(OUT_SB, 15)
        w = fo.getlength(s) + c(22) + (c(14) if k == 0 else 0)
        d.rounded_rectangle((cx - w / 2, c(282), cx + w / 2, c(306)), c(12), fill=(255, 255, 255, 30), outline=(255, 255, 255, 60), width=K)
        if k == 0:
            heart(d, cx - w / 2 + c(15), c(292), c(5), (255, 90, 100))
            text(d, (cx + c(7), c(294)), s, fo, (255, 255, 255), "mm")
        else:
            text(d, (cx, c(294)), s, fo, col, "mm")
    # the sports line in the rings' opening
    fo = f(OUT_B, 16)
    s = D["line"]
    w = fo.getlength(s) + c(36)
    d.rounded_rectangle((S / 2 - w / 2, c(342), S / 2 + w / 2, c(372)), c(15), fill=(0, 0, 0, 200), outline=tuple(t["accent"]) + (150,), width=K)
    icon(im, D["sport"], S / 2 - w / 2 + c(7), c(347), c(20), t["accent"])
    d = ImageDraw.Draw(im, "RGBA")
    text(d, (S / 2 + c(10), c(357)), s, fo, (255, 255, 255), "mm")
    for k in range(5):
        xa = S / 2 - c(50) + k * c(21)
        d.rounded_rectangle((xa, c(379), xa + c(17), c(382)), c(1.5), fill=tuple(t["accent"]) + (255,) if k < D["rail_days"] else (255, 255, 255, 60))
    return finish(im)


# ---- 3. Matchday: a football programme / editorial cover. Photo above, team colour below, numerals across both.

def matchday(t):
    D = DATA
    im = grade(photo(t["photo"], t["focus"]), 1.2, 1.12)
    split = c(238)
    d = ImageDraw.Draw(im, "RGBA")
    d.rectangle((0, split, S, S), fill=t["band"])
    # a soft shadow where photo meets band
    sh = Image.new("RGBA", (S, c(24)), (0, 0, 0, 0))
    for y in range(c(24)):
        ImageDraw.Draw(sh).line((0, y, S, y), fill=(0, 0, 0, int(110 * (1 - y / c(24)))))
    im.paste(sh, (0, split), sh)
    d = ImageDraw.Draw(im, "RGBA")
    d.rectangle((0, split - K, S, split + K), fill=t["gold"] if t is THEMES["arsenal"] else t["accent"])
    # masthead tags on the photo
    text(d, (S / 2, c(62)), D["weekday"], f(BARLOW_B, 22), (255, 255, 255), "mm", tracking=4, shadow=(0, 0, 0, 160))
    text(d, (S / 2, c(86)), D["day"] + " " + D["month"], f(BARLOW_M, 16), (255, 255, 255, 230), "mm", tracking=3, shadow=(0, 0, 0, 160))
    # the numerals straddle the line
    big = f(BEBAS, 178)
    s = D["hh"] + ":" + D["mm"]
    text(d, (S / 2, c(318)), s, big, (255, 255, 255), "ms", shadow=(0, 0, 0, 120))
    # scoreboard row
    cols = [(f'{D["steps"]:,}', "STEPS"), (str(D["kcal"]), "KCAL"), (str(D["hr"]), "BPM"), (f'{D["battery"]}%', "BATTERY"), (D["fpl"], "FPL")]
    xs = [c(96), c(160), c(225), c(290), c(354)]
    for x, (v, lab) in zip(xs, cols):
        text(d, (x, c(340)), v, f(BARLOW_B, 22), (255, 255, 255), "mm")
        text(d, (x, c(358)), lab, f(BARLOW_M, 10.5), (255, 255, 255, 170), "mm", tracking=1)
    for x in xs[:-1]:
        d.line((x + c(32), c(330), x + c(32), c(364)), fill=(255, 255, 255, 50), width=K)
    # the headline: the sport
    fo = f(BARLOW_SB, 19)
    s = D["line"]
    w = fo.getlength(s) + c(28)
    icon(im, D["sport"], S / 2 - w / 2, c(377), c(20), t["gold"])
    d = ImageDraw.Draw(im, "RGBA")
    text(d, (S / 2 + c(14), c(387)), s, fo, (255, 255, 255), "mm")
    for k in range(5):
        xa = S / 2 - c(50) + k * c(21)
        d.rounded_rectangle((xa, c(404), xa + c(17), c(407)), c(1.5), fill=tuple(t["gold"]) + (255,) if k < D["rail_days"] else (255, 255, 255, 60))
    return finish(im)


# ---- 4. Glass: Apple's Liquid Glass numerals / frosted cockpit. The photo stays vivid; glass carries the data.

def glassface(t):
    D = DATA
    im = grade(photo(t["photo"], t["focus"]), 1.3, 1.1, 1.0)
    # glass disc for the time
    glass(im, (c(95), c(105), c(355), c(275)), c(42), tint=(255, 255, 255, 30))
    d = ImageDraw.Draw(im, "RGBA")
    text(d, (S / 2, c(140)), D["weekday"] + "  " + D["day"] + " " + D["mon"], f(OUT_SB, 15), (255, 255, 255, 235), "mm", tracking=2)
    big = f(OUT_SB, 100)
    s = D["hh"] + ":" + D["mm"]
    text(d, (S / 2, c(245)), s, big, (255, 255, 255), "ms", shadow=(0, 0, 0, 70))
    # top capsule: heart + battery
    glass(im, (c(160), c(52), c(290), c(84)), c(16))
    d = ImageDraw.Draw(im, "RGBA")
    heart(d, c(180), c(67), c(5.5), (255, 80, 95))
    text(d, (c(191), c(68)), str(D["hr"]), f(OUT_SB, 15), (255, 255, 255), "lm")
    text(d, (c(272), c(68)), f'{D["battery"]}%', f(OUT_SB, 15), (255, 255, 255), "rm")
    d.line((c(225), c(60), c(225), c(76)), fill=(255, 255, 255, 80), width=K)
    # three glass tiles: steps, kcal, FPL, each with a hairline progress
    for k, (v, lab, pct, col) in enumerate([(f'{D["steps"]:,}', "STEPS", D["steps"] / D["steps_goal"], t["ring"][0]),
                                            (str(D["kcal"]), "KCAL", D["kcal"] / D["kcal_goal"], t["ring"][1]),
                                            (D["fpl"], "FPL", D["fpl_pct"], t["ring"][2])]):
        x0 = c(78 + k * 102)
        glass(im, (x0, c(288), x0 + c(92), c(340)), c(16))
        d = ImageDraw.Draw(im, "RGBA")
        text(d, (x0 + c(46), c(310)), v, f(OUT_B, 18), (255, 255, 255), "mm")
        text(d, (x0 + c(46), c(327)), lab, f(OUT_SB, 9.5), (255, 255, 255, 190), "mm", tracking=1.5)
        d.rounded_rectangle((x0 + c(14), c(333), x0 + c(78), c(335)), K, fill=(255, 255, 255, 60))
        d.rounded_rectangle((x0 + c(14), c(333), x0 + c(14 + 64 * min(1, pct)), c(335)), K, fill=tuple(col) + (255,))
    # sports pill in glass
    glass(im, (c(70), c(352), c(380), c(386)), c(17))
    icon(im, D["sport"], c(82), c(358), c(22), t["gold"] if t is THEMES["arsenal"] else t["accent"])
    d = ImageDraw.Draw(im, "RGBA")
    text(d, (c(236), c(369)), D["line"], f(OUT_B, 16), (255, 255, 255), "mm")
    for k in range(5):
        xa = S / 2 - c(50) + k * c(21)
        d.rounded_rectangle((xa, c(391), xa + c(17), c(394)), c(1.5), fill=(255, 255, 255, 235) if k < D["rail_days"] else (255, 255, 255, 70))
    return finish(im)


CONCEPTS = [("broadcast", "1 · Broadcast", broadcast), ("concentric", "2 · Concentric", concentric),
            ("matchday", "3 · Matchday", matchday), ("glass", "4 · Glass", glassface)]

if __name__ == "__main__":
    out = sys.argv[1]
    os.makedirs(out, exist_ok=True)
    for key, title, fn in CONCEPTS:
        for theme, t in THEMES.items():
            fn(t).save(os.path.join(out, f"{key}-{theme}.png"))
            print("rendered", key, theme)
