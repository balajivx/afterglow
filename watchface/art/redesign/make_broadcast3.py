"""Round 3: simpler Broadcast. Keep round 1's TV caption bar exactly; at most four elements on the face:
time, date, one quiet stats line, the caption bar. Upright type.

    python3 art/redesign/make_broadcast3.py art/redesign/out3
"""
import os
import sys

from PIL import ImageDraw

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from make_redesign import (DATA, THEMES, K, S, c, f, text, photo, grade, shade, icon, heart, finish,  # noqa: E402
                           BEBAS, BARLOW_B, BARLOW_SB, BARLOW_M)

D = DATA


def caption_bar(im, t):
    """Round 1's TV caption bar, unchanged: a team-colour tab with the sport icon, the line, the rail beneath."""
    d = ImageDraw.Draw(im, "RGBA")
    acc = t["accent"]
    x0, x1, y0, y1 = c(76), c(380), c(342), c(378)
    d.polygon([(x0, y0), (x0 + c(44), y0), (x0 + c(36), y1), (x0 - c(8), y1)], fill=acc + (255,))
    d.rectangle((x0 + c(40), y0, x1, y1), fill=(8, 8, 10, 235))
    icon(im, D["sport"], x0 + c(6), y0 + c(7), c(22), (0, 0, 0) if sum(acc) > 500 else (255, 255, 255))
    d = ImageDraw.Draw(im, "RGBA")
    text(d, (x0 + c(52), (y0 + y1) / 2), D["line"].upper(), f(BARLOW_SB, 21), (255, 255, 255), "lm", tracking=0.5)
    for k in range(5):
        xa = x0 + c(44) + k * c(60)
        d.rectangle((xa, y1 + c(3), xa + c(54), y1 + c(6)), fill=acc + (255,) if k < D["rail_days"] else (255, 255, 255, 60))


def stats_row(d, y, t, size=17, label=10):
    """Five readings in one quiet row: value over a tiny label."""
    items = [(f'{D["steps"]:,}', "STEPS"), (str(D["kcal"]), "KCAL"), (str(D["hr"]), "BPM"), (f'{D["battery"]}%', "BATTERY"), (D["fpl"], "FPL")]
    xs = [c(112), c(170), c(225), c(282), c(340)]
    for x, (v, lab) in zip(xs, items):
        text(d, (x, y), v, f(BARLOW_SB, size), (255, 255, 255), "mm")
        text(d, (x, y + c(size * 0.85)), lab, f(BARLOW_M, label), (255, 255, 255, 150), "mm", tracking=1)


# ---- 1. Clean Feed: the time centred and upright, everything else in one row
def clean_feed(t):
    im = grade(photo(t["photo"], t["focus"]), 1.15, 1.06)
    im = shade(im, [(0, 90), (0.3, 70), (0.55, 110), (0.72, 200), (1, 250)])
    d = ImageDraw.Draw(im, "RGBA")
    text(d, (S / 2, c(118)), D["weekday"] + "  " + D["day"] + " " + D["mon"], f(BARLOW_SB, 18), (255, 255, 255, 230), "mm", tracking=3)
    big = f(BARLOW_SB, 150)
    hw, cw, mw = big.getlength(D["hh"]), big.getlength(":"), big.getlength(D["mm"])
    x = S / 2 - (hw + cw + mw) / 2
    text(d, (x, c(262)), D["hh"], big, (255, 255, 255), "ls", shadow=(0, 0, 0, 110))
    text(d, (x + hw, c(256)), ":", big, (255, 255, 255, 170), "ls")
    text(d, (x + hw + cw, c(262)), D["mm"], big, t["accent"], "ls", shadow=(0, 0, 0, 110))
    stats_row(d, c(294), t)
    caption_bar(im, t)
    return finish(im)


# ---- 2. Left Hand: round 1's left-aligned time; one plain stats column; nothing at the top
def left_hand(t):
    im = grade(photo(t["photo"], t["focus"]), 1.2, 1.1)
    im = shade(im, [(0, 120), (0.25, 40), (0.45, 50), (0.68, 205), (1, 255)])
    d = ImageDraw.Draw(im, "RGBA")
    text(d, (c(62), c(160)), D["weekday"] + "  ·  " + D["day"] + " " + D["mon"], f(BARLOW_SB, 20), (255, 255, 255), "lm", tracking=1.5, shadow=(0, 0, 0, 140))
    big = f(BEBAS, 150)
    text(d, (c(58), c(300)), D["hh"], big, (255, 255, 255), "ls", shadow=(0, 0, 0, 150))
    hw = big.getlength(D["hh"])
    text(d, (c(58) + hw + c(6), c(300)), D["mm"], big, t["accent"], "ls", shadow=(0, 0, 0, 150))
    rows = [(f'{D["steps"]:,}', "STEPS"), (str(D["kcal"]), "KCAL"), (str(D["hr"]), "BPM"), (f'{D["battery"]}%', "BATTERY"), (D["fpl"], "FPL")]
    # a soft shade from the right edge, so the column reads on busy photos (crowds)
    from PIL import Image
    sh = Image.new("RGBA", (c(150), c(170)), (0, 0, 0, 0))
    sd = ImageDraw.Draw(sh)
    H = c(170)
    for x in range(c(150)):
        for y in range(0, H, 2):
            v = min(1, y / (H * 0.2), (H - y) / (H * 0.2))   # fade at top and bottom too
            sd.line((x, y, x, y + 1), fill=(0, 0, 0, int(150 * (x / c(150)) ** 1.3 * v)))
    im.paste(sh, (c(270), c(146)), sh)
    d = ImageDraw.Draw(im, "RGBA")
    for k, (v, lab) in enumerate(rows):
        y = c(166 + k * 30)
        text(d, (c(392), y), v, f(BARLOW_SB, 19), (255, 255, 255), "rs", shadow=(0, 0, 0, 140))
        text(d, (c(392), y + c(11)), lab, f(BARLOW_M, 10), (255, 255, 255, 160), "rs", tracking=1)
    caption_bar(im, t)
    return finish(im)


# ---- 3. Quiet: a medium time high up, the photo carries the face, stats just above the caption bar
def quiet(t):
    im = grade(photo(t["photo"], t["focus"]), 1.18, 1.06)
    im = shade(im, [(0, 150), (0.3, 30), (0.55, 40), (0.68, 175), (1, 245)])
    d = ImageDraw.Draw(im, "RGBA")
    text(d, (S / 2, c(70)), D["weekday"] + "  " + D["day"] + " " + D["mon"], f(BARLOW_SB, 16), (255, 255, 255, 230), "mm", tracking=3)
    big = f(BARLOW_SB, 96)
    s = D["hh"] + ":" + D["mm"]
    text(d, (S / 2, c(172)), s, big, (255, 255, 255), "ms", shadow=(0, 0, 0, 120))
    stats_row(d, c(306), t, size=16, label=9.5)
    caption_bar(im, t)
    return finish(im)


VARIANTS = [("clean-feed", clean_feed), ("left-hand", left_hand), ("quiet", quiet)]

if __name__ == "__main__":
    out = sys.argv[1]
    os.makedirs(out, exist_ok=True)
    for key, fn in VARIANTS:
        for theme, t in THEMES.items():
            fn(t).save(os.path.join(out, f"{key}-{theme}.png"))
    print("done")
