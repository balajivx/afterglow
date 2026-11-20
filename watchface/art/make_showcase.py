"""Six faces side by side: different days' designs, each with the sports widget in a different state.

    python3 art/make_showcase.py <out_dir>      # -> showcase.png and face-1.png ... face-6.png

Lines are what NextUpComplication.faceLine() writes (tested there); a line too wide for the slot is cut with
"…" as the watch does, and the script says so.
"""
import os
import sys
from PIL import Image, ImageDraw, ImageFont

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import make_preview as mp
from themes import THEMES

# (theme index, design 0-7, icon, line, caption)
SHOW = [
    (0, 4, "cricket", "LIVE · NZ 312 · IND 58/1", 102, "Today's design (20 Nov) · India batting, Test day 2 of 5"),
    (0, 4, "cricket", "NZ 312 · IND 98/2 · Day 3 in 16h", 102, "Same day at stumps · counts down to Day 3"),
    (1, 2, "track:vegas", "Las Vegas · FP1 in 6h 50m", 72, "Ferrari day · race week: the circuit, practice nearing"),
    (2, 5, "football", "Newcastle vs ARS · in 2h 14m", 91, "Space day · Arsenal kick-off, the rail nearly full"),
    (1, 6, "f1", "Brazilian · LEC P8 · HAM P9", None, "Ferrari day · where the Ferraris finished"),
    (0, 1, "football", "Newcastle vs ARS · LIVE", 100, "Arsenal day · on now, the rail lit"),
]


def fit(line, font, width=244):
    if font.getlength(line) <= width:
        return line, False
    while font.getlength(line + "…") > width:
        line = line[:-1]
    return line.rstrip() + "…", True


if __name__ == "__main__":
    out = sys.argv[1]
    os.makedirs(out, exist_ok=True)
    f18 = ImageFont.truetype(os.path.join(mp.FONT_DIR, "outfit_bold.ttf"), 17)
    cap = ImageFont.truetype(os.path.join(mp.FONT_DIR, "outfit_semibold.ttf"), 17)
    tiles = []
    for n, (k, design, icon, line, rail, caption) in enumerate(SHOW, 1):
        shown, cut = fit(line, f18)
        print(("CUT " if cut else "ok  ") + str(round(f18.getlength(line))) + "  " + line)
        im = mp.render(THEMES[k], design, (icon, shown)).convert("RGBA")
        mp.sports_pill(im, THEMES[k], (icon, shown), rail)
        # round screen
        mask = Image.new("L", im.size, 0)
        ImageDraw.Draw(mask).ellipse((0, 0, im.size[0] - 1, im.size[1] - 1), fill=255)
        face = Image.new("RGB", im.size, (0, 0, 0))
        face.paste(im, (0, 0), mask)
        face.save(os.path.join(out, f"face-{n}.png"))
        tiles.append((face, caption))
    W, H, pad = 450, 450, 30
    sheet = Image.new("RGB", (3 * W + 4 * pad, 2 * (H + 60) + pad), (12, 13, 18))
    d = ImageDraw.Draw(sheet)
    for i, (face, caption) in enumerate(tiles):
        x = pad + (i % 3) * (W + pad)
        y = pad + (i // 3) * (H + 60)
        sheet.paste(face, (x, y))
        d.text((x + W // 2, y + H + 22), caption, font=cap, fill=(220, 222, 230), anchor="mm")
    sheet.save(os.path.join(out, "showcase.png"))
    print(os.path.join(out, "showcase.png"))
