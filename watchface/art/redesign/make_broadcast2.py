"""Round 2: four takes on the Broadcast direction (the one the user picked), same data, real photos.

    python3 art/redesign/make_broadcast2.py art/redesign/out2

Borrowed: F1's timing tower and onboard graphics, TAG Heuer's race-weekend faces (bezel-aligned gauges, session
cues), Sky Sports / Premier League score bugs, Apple's 2026 F1 coverage (crisp, restrained type); motorsport italics.
"""
import math
import os
import sys

from PIL import Image, ImageDraw

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from make_redesign import (DATA, THEMES, K, S, c, f, text, photo, grade, shade, icon, heart, finish,  # noqa: E402
                           BARLOW_B, BARLOW_SB, BARLOW_M)

XB_I, B_I, SB_I, REG = "BarlowCondensed-ExtraBoldItalic.ttf", "BarlowCondensed-BoldItalic.ttf", "BarlowCondensed-SemiBoldItalic.ttf", "BarlowCondensed-Regular.ttf"
D = DATA
FILL = lambda t: tuple(t["accent"])
ON_ACC = lambda t: (0, 0, 0) if sum(t["accent"]) > 500 else (255, 255, 255)


def tag(d, x, y, s, font, bg, fg, slant=8, padx=10, h=22, anchor_right=False):
    """A slanted broadcast tag (parallelogram) with text; returns its right edge."""
    w = font.getlength(s) + c(padx * 2)
    if anchor_right:
        x -= w
    d.polygon([(x + c(slant), y), (x + w + c(slant), y), (x + w, y + c(h)), (x, y + c(h))], fill=bg)
    text(d, (x + c(padx) + c(slant) / 2, y + c(h) / 2), s, font, fg, "lm")
    return x + w + c(slant)


def segbar(d, x, y, w, h, pct, n, on, off=(255, 255, 255, 55), gap=2, skew=3):
    """An energy-store style bar: n slanted segments, lit up to pct."""
    sw = (w - c(gap) * (n - 1)) / n
    for i in range(n):
        x0 = x + i * (sw + c(gap))
        col = on if (i + 1) / n <= pct + 1e-6 else off
        d.polygon([(x0 + c(skew), y), (x0 + sw + c(skew), y), (x0 + sw, y + h), (x0, y + h)], fill=col)


def lower_third(im, t, y0, eyebrow=True):
    """Two-tier caption: a small accent tab ("LIVE · TEST · DAY 2/5") over the main bar (sport tab + score)."""
    d = ImageDraw.Draw(im, "RGBA")
    x0, x1, h = c(70), c(382), c(36)
    if eyebrow:
        tag(d, x0 + c(46), y0 - c(20), "LIVE  ·  TEST  ·  DAY 2/5", f(B_I, 13), (225, 6, 0, 255) if t is not THEMES["ferrari"] else (255, 220, 0, 255),
            (255, 255, 255) if t is not THEMES["ferrari"] else (0, 0, 0), slant=6, padx=8, h=18)
    d.polygon([(x0 + c(8), y0), (x0 + c(48), y0), (x0 + c(40), y0 + h), (x0, y0 + h)], fill=FILL(t) + (255,))
    d.polygon([(x0 + c(48), y0), (x1 + c(8), y0), (x1, y0 + h), (x0 + c(40), y0 + h)], fill=(6, 7, 10, 238))
    icon(im, D["sport"], x0 + c(10), y0 + c(7), c(22), ON_ACC(t))
    d = ImageDraw.Draw(im, "RGBA")
    text(d, (x0 + c(56), y0 + h / 2), "NZ 368", f(B_I, 22), (255, 255, 255), "lm")
    nz = f(B_I, 22).getlength("NZ 368")
    text(d, (x0 + c(56) + nz + c(10), y0 + h / 2), "·", f(B_I, 22), (255, 255, 255, 120), "lm")
    text(d, (x0 + c(56) + nz + c(22), y0 + h / 2), "IND 159/1", f(B_I, 22), FILL(t) if t is not THEMES["arsenal"] else (255, 255, 255), "lm")
    # rail: the Test's five days along the bar's foot
    for k in range(5):
        xa = x0 + c(48) + k * c(66)
        d.rectangle((xa, y0 + h + c(3), xa + c(60), y0 + h + c(5)), fill=FILL(t) + (255,) if k < D["rail_days"] else (255, 255, 255, 60))


# ---- A. Race Control: the original, refined
def race_control(t):
    im = grade(photo(t["photo"], t["focus"]), 1.2, 1.12)
    im = shade(im, [(0, 160), (0.2, 40), (0.42, 50), (0.64, 210), (1, 255)])
    d = ImageDraw.Draw(im, "RGBA")
    # top: two telemetry chips
    for (x, kind) in [(c(170), "hr"), (c(232), "bat")]:
        d.rounded_rectangle((x, c(52), x + c(52), c(74)), c(4), fill=(0, 0, 0, 170), outline=(255, 255, 255, 60), width=K)
        if kind == "hr":
            heart(d, x + c(12), c(61), c(5), (255, 70, 80))
            text(d, (x + c(22), c(63)), str(D["hr"]), f(B_I, 17), (255, 255, 255), "lm")
        else:
            d.rectangle((x + c(7), c(58), x + c(21), c(68)), outline=(255, 255, 255, 220), width=c(1.2))
            d.rectangle((x + c(9), c(60), x + c(9 + 10 * D["battery"] / 100), c(66)), fill=FILL(t))
            text(d, (x + c(25), c(63)), f'{D["battery"]}', f(B_I, 17), (255, 255, 255), "lm")
    # date tag + day-of-year lap counter
    tag(d, c(56), c(146), "FRI  20 NOV", f(XB_I, 17), (225, 6, 0, 255) if t is not THEMES["ferrari"] else (255, 220, 0, 255),
        (255, 255, 255) if t is not THEMES["ferrari"] else (0, 0, 0))
    text(d, (c(150), c(157)), "DAY 324/366", f(SB_I, 14), (255, 255, 255, 190), "lm")
    # time: motorsport italics
    big = f(XB_I, 150)
    text(d, (c(52), c(300)), D["hh"], big, (255, 255, 255), "ls", shadow=(0, 0, 0, 150))
    hw = big.getlength(D["hh"])
    text(d, (c(52) + hw + c(4), c(300)), D["mm"], big, FILL(t), "ls", shadow=(0, 0, 0, 150))
    # right: energy-store bars
    for k, (lab, val, pct, col) in enumerate([("STEPS", f'{D["steps"]:,}', D["steps"] / D["steps_goal"], t["ring"][0]),
                                              ("KCAL", str(D["kcal"]), D["kcal"] / D["kcal_goal"], t["ring"][1]),
                                              ("FPL", D["fpl"], D["fpl_pct"], t["ring"][2])]):
        y = c(180 + k * 44)
        text(d, (c(396), y), val, f(XB_I, 20), (255, 255, 255), "rs")
        text(d, (c(330), y - c(1)), lab, f(SB_I, 11), (255, 255, 255, 170), "ls")
        segbar(d, c(330), y + c(5), c(66), c(6), min(1, pct), 8, tuple(col) + (255,))
    lower_third(im, t, c(342))
    return finish(im)


# ---- B. Timing Tower: the stats as F1's tower, the time stacked on the left
def timing_tower(t):
    im = grade(photo(t["photo"], t["focus"]), 1.2, 1.1)
    im = shade(im, [(0, 140), (0.2, 50), (0.5, 90), (0.7, 200), (1, 255)])
    # a dark glass panel for the tower
    panel = Image.new("RGBA", im.size, (0, 0, 0, 0))
    pd = ImageDraw.Draw(panel)
    pd.rectangle((c(262), c(96), c(392), c(318)), fill=(4, 5, 8, 200))
    im = Image.alpha_composite(im.convert("RGBA"), panel).convert("RGB")
    d = ImageDraw.Draw(im, "RGBA")
    # tower header, like "LAP 32/57"
    d.rectangle((c(262), c(96), c(392), c(120)), fill=FILL(t) + (255,))
    text(d, (c(272), c(108)), "FRI 20 NOV", f(XB_I, 16), ON_ACC(t), "lm")
    text(d, (c(384), c(108)), "D324", f(B_I, 13), ON_ACC(t), "rm")
    rows = [("1", "STP", f'{D["steps"]:,}', t["ring"][0]), ("2", "KCL", str(D["kcal"]), t["ring"][1]),
            ("3", "BPM", str(D["hr"]), (255, 70, 80)), ("4", "BAT", f'{D["battery"]}%', (255, 255, 255)),
            ("5", "FPL", D["fpl"], t["gold"])]
    for k, (pos, code, val, col) in enumerate(rows):
        y = c(122 + k * 39)
        d.line((c(266), y + c(38), c(388), y + c(38)), fill=(255, 255, 255, 30), width=K)
        text(d, (c(276), y + c(19)), pos, f(XB_I, 15), (255, 255, 255, 150), "mm")
        d.rectangle((c(288), y + c(9), c(292), y + c(29)), fill=tuple(col) + (255,))
        text(d, (c(300), y + c(19)), code, f(B_I, 17), (255, 255, 255), "lm")
        text(d, (c(386), y + c(19)), val, f(XB_I, 18), (255, 255, 255), "rm")
    # time stacked, left
    big = f(XB_I, 128)
    text(d, (c(60), c(205)), D["hh"], big, (255, 255, 255), "ls", shadow=(0, 0, 0, 150))
    text(d, (c(60), c(318)), D["mm"], big, FILL(t), "ls", shadow=(0, 0, 0, 150))
    lower_third(im, t, c(346), eyebrow=False)
    return finish(im)


# ---- C. Onboard: shift lights for steps, the time as the speed readout, gear-style boxes
def onboard(t):
    im = grade(photo(t["photo"], t["focus"]), 1.15, 1.1, 0.95)
    im = shade(im, [(0, 200), (0.25, 120), (0.55, 130), (0.72, 220), (1, 255)])
    d = ImageDraw.Draw(im, "RGBA")
    # shift lights across the top: 15 lights, green -> red -> purple like an F1 wheel; lit by steps
    cols = [(40, 220, 90)] * 5 + [(240, 40, 40)] * 5 + [(150, 80, 255)] * 5
    lit = round(15 * min(1, D["steps"] / D["steps_goal"]))
    for i, col in enumerate(cols):
        a = math.radians(-90 - 52 + i * (104 / 14))
        x, y = S / 2 + c(196) * math.cos(a), S / 2 + c(196) * math.sin(a)
        r = c(6.5)
        on = i < lit
        if on:
            d.ellipse((x - r * 1.9, y - r * 1.9, x + r * 1.9, y + r * 1.9), fill=col + (60,))
        d.ellipse((x - r, y - r, x + r, y + r), fill=col + (255,) if on else (40, 40, 44, 255), outline=(0, 0, 0, 255), width=K)
    text(d, (S / 2, c(76)), f'{D["steps"]:,} STEPS', f(B_I, 15), (255, 255, 255, 220), "mm")
    # the readout
    big = f(XB_I, 112)
    s = D["hh"] + ":" + D["mm"]
    text(d, (S / 2, c(226)), s, big, (255, 255, 255), "ms", shadow=(0, 0, 0, 160))
    # date under the readout, white on a dark slanted band (the photo can be busy here)
    tag(d, S / 2 - c(78), c(238), "FRIDAY  20 NOVEMBER", f(SB_I, 15), (0, 0, 0, 190), (255, 255, 255), slant=6, padx=10, h=20)
    # gear-style boxes either side, clear of the readout
    for (cx, val, lab, col) in [(c(66), str(D["hr"]), "BPM", (255, 70, 80)), (c(384), f'{D["battery"]}', "BAT %", FILL(t))]:
        d.rounded_rectangle((cx - c(23), c(150), cx + c(23), c(198)), c(6), fill=(0, 0, 0, 190), outline=tuple(col) + (230,), width=c(1.6))
        text(d, (cx, c(172)), val, f(XB_I, 22), (255, 255, 255), "mm")
        text(d, (cx, c(190)), lab, f(B_I, 10), tuple(col) + (255,), "mm")
    # delta chips: kcal and FPL
    for (cx, s2, col) in [(S / 2 - c(56), f'+{D["kcal"]} KCAL', t["ring"][1]), (S / 2 + c(56), "FPL  " + D["fpl"], t["gold"])]:
        fo = f(B_I, 15)
        w = fo.getlength(s2) + c(20)
        d.polygon([(cx - w / 2 + c(5), c(278)), (cx + w / 2 + c(5), c(278)), (cx + w / 2 - c(5), c(300)), (cx - w / 2 - c(5), c(300))],
                  fill=(0, 0, 0, 190), outline=tuple(col) + (200,))
        text(d, (cx, c(289)), s2, fo, tuple(col) + (255,), "mm")
    lower_third(im, t, c(344))
    return finish(im)


# ---- D. Score Bug: the live score up top like Sky Sports / PL graphics; a stat ticker at the foot
def score_bug(t):
    im = grade(photo(t["photo"], t["focus"]), 1.2, 1.1)
    im = shade(im, [(0, 170), (0.26, 60), (0.5, 60), (0.74, 190), (1, 250)])
    d = ImageDraw.Draw(im, "RGBA")
    # the bug: [sport tab][IND 159/1][NZ 368][LIVE]
    x0, y0, h = c(100), c(70), c(30)
    d.rectangle((x0, y0, x0 + c(30), y0 + h), fill=FILL(t) + (255,))
    icon(im, D["sport"], x0 + c(5), y0 + c(5), c(20), ON_ACC(t))
    d = ImageDraw.Draw(im, "RGBA")
    d.rectangle((x0 + c(30), y0, x0 + c(196), y0 + h), fill=(250, 250, 252, 245))
    text(d, (x0 + c(38), y0 + h / 2), "IND", f(XB_I, 18), (10, 10, 14), "lm")
    text(d, (x0 + c(72), y0 + h / 2), "159/1", f(XB_I, 18), (10, 10, 14), "lm")
    d.line((x0 + c(116), y0 + c(6), x0 + c(116), y0 + h - c(6)), fill=(0, 0, 0, 60), width=K)
    text(d, (x0 + c(124), y0 + h / 2), "NZ", f(XB_I, 18), (90, 90, 96), "lm")
    text(d, (x0 + c(150), y0 + h / 2), "368", f(XB_I, 18), (90, 90, 96), "lm")
    d.rectangle((x0 + c(196), y0, x0 + c(246), y0 + h), fill=(225, 6, 0, 255))
    text(d, (x0 + c(221), y0 + h / 2), "LIVE", f(XB_I, 15), (255, 255, 255), "mm")
    for k in range(5):  # rail: the Test's days under the bug
        xa = x0 + c(30) + k * c(43)
        d.rectangle((xa, y0 + h + c(2), xa + c(39), y0 + h + c(4)), fill=FILL(t) + (255,) if k < D["rail_days"] else (255, 255, 255, 70))
    text(d, (x0 + c(30), y0 + h + c(16)), "TEST · DAY 2 OF 5 · STUMPS", f(SB_I, 12), (255, 255, 255, 200), "lm")
    # time: big, centred, italic; date tag above
    tag(d, S / 2 - c(58), c(150), "FRIDAY  20 NOV", f(XB_I, 16), (0, 0, 0, 200), (255, 255, 255), slant=6)
    big = f(XB_I, 150)
    s = D["hh"] + ":" + D["mm"]
    w = big.getlength(D["hh"])
    text(d, (S / 2, c(308)), s, big, (255, 255, 255), "ms", shadow=(0, 0, 0, 150))
    # ticker at the foot: five stats in a running strip
    d.rectangle((0, c(338), S, c(372)), fill=(6, 7, 10, 230))
    d.rectangle((0, c(338), S, c(340)), fill=FILL(t) + (255,))
    items = [("STEPS", f'{D["steps"]:,}'), ("KCAL", str(D["kcal"])), ("BPM", str(D["hr"])), ("BAT", f'{D["battery"]}%'), ("FPL", D["fpl"])]
    x = c(70)
    for lab, val in items:
        text(d, (x, c(356)), lab, f(SB_I, 12), FILL(t) + (255,), "lm")
        x += f(SB_I, 12).getlength(lab) + c(4)
        text(d, (x, c(356)), val, f(XB_I, 17), (255, 255, 255), "lm")
        x += f(XB_I, 17).getlength(val) + c(12)
    return finish(im)


VARIANTS = [("race-control", race_control), ("timing-tower", timing_tower), ("onboard", onboard), ("score-bug", score_bug)]

if __name__ == "__main__":
    out = sys.argv[1]
    os.makedirs(out, exist_ok=True)
    for key, fn in VARIANTS:
        for theme, t in THEMES.items():
            fn(t).save(os.path.join(out, f"{key}-{theme}.png"))
    print("done")
