"""Images for the Clean Feed face: the scrim (darkens top and bottom over any photo, built-in or monthly) and the
caption bar's slanted tab (white; the face tints it the day's colour). Usage: make_cleanfeed_art.py RES_DIR"""
import os
import sys

from PIL import Image, ImageDraw

out = sys.argv[1]
S = 450
# the scrim: same stops as the approved mock-up (art/redesign/make_broadcast3.py clean_feed)
stops = [(0, 90), (0.3, 70), (0.55, 110), (0.72, 200), (1, 250)]
col = Image.new("L", (1, S))
for y in range(S):
    t = y / (S - 1)
    for (y0, a0), (y1, a1) in zip(stops, stops[1:]):
        if y0 <= t <= y1:
            u = (t - y0) / (y1 - y0)
            u = u * u * (3 - 2 * u)
            col.putpixel((0, y), int(a0 + (a1 - a0) * u))
            break
alpha = col.resize((S, S))
scrim = Image.new("RGBA", (S, S), (0, 0, 0, 0))
scrim.putalpha(alpha)
scrim.save(os.path.join(out, "cf_scrim.png"), optimize=True)
# the tab: a parallelogram 52 x 36 (as round 1: top 8..52, bottom 0..44), drawn at 4x and reduced
k = 4
tab = Image.new("RGBA", (52 * k, 36 * k), (0, 0, 0, 0))
ImageDraw.Draw(tab).polygon([(8 * k, 0), (52 * k, 0), (44 * k, 36 * k), (0, 36 * k)], fill=(255, 255, 255, 255))
tab.resize((52, 36), Image.LANCZOS).save(os.path.join(out, "cf_tab.png"), optimize=True)
# the date plate's tab: the same slant, 32 x 44 (top 11..32, bottom 0..21)
tab = Image.new("RGBA", (32 * k, 44 * k), (0, 0, 0, 0))
ImageDraw.Draw(tab).polygon([(11 * k, 0), (32 * k, 0), (21 * k, 44 * k), (0, 44 * k)], fill=(255, 255, 255, 255))
tab.resize((32, 44), Image.LANCZOS).save(os.path.join(out, "cf_tab_d.png"), optimize=True)
print("clean feed art done")
