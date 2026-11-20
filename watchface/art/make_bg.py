"""Build eight theme-matched bezel treatments per photo theme, plus the edge-to-edge minute track and pulse icon.
All art is original and uses the theme palette without club or team logos.
Usage: make_bg.py OUT_DIR -> 24 overlay_<theme>_<design>.png assets and bg.png fallback."""
from PIL import Image, ImageDraw, ImageFilter
import math
import os
import random
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from themes import THEMES  # noqa: E402

S = 450 * 2  # drawn at 2x, scaled down for smooth edges
c = S // 2


def arc(d, a0, a1, r, width, color):
    """An arc of radius r round the centre. Angles in degrees, 0 = 3 o'clock, clockwise."""
    d.arc([c - r, c - r, c + r, c + r], a0, a1, fill=color, width=width)


def polar(r, deg):
    a = math.radians(deg)
    return c + r * math.cos(a), c + r * math.sin(a)


def frame_ferrari(layer, t):
    """Subtle Scuderia racing stripes right along the outer bezel edge."""
    d = ImageDraw.Draw(layer)
    arc(d, 108, 252, 442, 6, t["left"] + (255,))
    arc(d, -72, 72, 442, 6, t["right"] + (255,))
    return layer


def frame_arsenal(layer, t):
    """Subtle Art Deco outer bezel: gold perimeter ring with hour pips."""
    d = ImageDraw.Draw(layer)
    red, gold = t["left"] + (220,), t["major"] + (255,)
    d.ellipse([c - 444, c - 444, c + 444, c + 444], outline=gold, width=2)
    for h in range(12):
        deg = h * 30 - 90
        x, y = polar(440, deg)
        s = 4 if h % 3 == 0 else 2.5
        d.ellipse([x - s, y - s, x + s, y + s], fill=gold)
    return layer


def frame_space(layer, t):
    """Subtle cosmic glow right along the outer rim."""
    glow = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    gd = ImageDraw.Draw(glow)
    for i in range(36):
        f = i / 35
        col = tuple(int(t["left"][j] * (1 - f) + t["right"][j] * f) for j in range(3))
        arc(gd, 90 + i * 5, 90 + i * 5 + 6, 442, 12, col + (140,))
        arc(gd, 90 - i * 5 - 6, 90 - i * 5, 442, 12, col + (140,))
    return Image.alpha_composite(layer, glow.filter(ImageFilter.GaussianBlur(5)))


FRAMES = {"ferrari": frame_ferrari, "arsenal": frame_arsenal, "space": frame_space}


def styled_frame(layer, t, style):
    if style == 0:
        return FRAMES[t["name"]](layer, t)
    d = ImageDraw.Draw(layer)
    major, left, right = t["major"] + (220,), t["left"] + (220,), t["right"] + (220,)
    if style == 1:  # double precision bezel
        d.ellipse([c-442,c-442,c+442,c+442], outline=major, width=3)
        d.ellipse([c-416,c-416,c+416,c+416], outline=left[:3]+(105,), width=2)
        for deg in range(-90,270,30):
            x,y=polar(429,deg); d.ellipse([x-4,y-4,x+4,y+4],fill=right)
    elif style == 2:  # split-color half-lap
        arc(d,198,270,440,7,left); arc(d,270,342,440,7,right)
        for deg,col in ((-90,major),(0,right),(90,major),(180,left)):
            x,y=polar(439,deg); d.rounded_rectangle([x-7,y-7,x+7,y+7],radius=3,fill=col)
    elif style == 3:  # tachometer redline across the crown
        for i in range(25):
            deg=204+i*6
            x0,y0=polar(420,deg); x1,y1=polar(441,deg)
            col=left if i<15 else right
            d.line((x0,y0,x1,y1),fill=col,width=5 if i%5==0 else 3)
        arc(d,206,244,410,3,major); arc(d,318,342,410,3,major)
    elif style == 4:  # segmented quarter markers
        for a0,a1,col in ((195,225,left),(235,265,major),(275,305,right),(315,345,left),
                          (15,45,right),(55,85,major),(95,125,left),(135,165,right)):
            arc(d,a0,a1,439,8,col)
        for deg in (-90,0,90,180):
            x,y=polar(408,deg); d.ellipse([x-5,y-5,x+5,y+5],outline=major,width=2)
    elif style == 5:  # precision index dots and side rails
        for i in range(32):
            deg=-174+i*11
            x,y=polar(440,deg); r=5 if i%4==0 else 3
            col=(left,right,major)[i%3]
            d.ellipse([x-r,y-r,x+r,y+r],fill=col)
        arc(d,108,154,422,4,left); arc(d,26,72,422,4,right)
    elif style == 6:  # twin orbit tracks
        arc(d,204,336,439,4,left); arc(d,24,156,439,4,right)
        arc(d,208,332,427,2,major); arc(d,28,152,427,2,major)
        for deg in (-150,-30,30,150):
            x,y=polar(432,deg); d.ellipse([x-6,y-6,x+6,y+6],fill=major)
    else:  # style 7, a bold twelve-point crown
        for i in range(12):
            deg=i*30-90; x0,y0=polar(418,deg); x1,y1=polar(442,deg)
            col=left if i%3==0 else (right if i%3==1 else major)
            d.line((x0,y0,x1,y1),fill=col,width=6 if i%3==0 else 3)
        d.ellipse([c-410,c-410,c+410,c+410],outline=right[:3]+(95,),width=2)
    return layer


def overlay(t, style=0):
    layer = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)

    # Outer minute track right along the bezel (r=422 to 438 at 2x)
    # An inset hairline gives the watch face a machined, layered bezel.
    arc(d, 0, 359, 406, 2, (255, 255, 255, 35))
    for k in range(60):
        a = math.radians(k * 6 - 90)
        major = k % 5 == 0
        if t["stars"]:
            r = 432
            x, y = c + r * math.cos(a), c + r * math.sin(a)
            s = 5 if major else 2.5
            d.ellipse([x - s, y - s, x + s, y + s], fill=(t["major"] if major else t["ticks"]) + (235 if major else 130,))
            continue
        r0, r1 = (420 if major else 429), 438
        col = (t["major"] if major else t["ticks"]) + (240 if major else 118,)
        d.line([c + r0 * math.cos(a), c + r0 * math.sin(a), c + r1 * math.cos(a), c + r1 * math.sin(a)],
               fill=col, width=6 if major else 3)

    if t["stars"]:
        rnd = random.Random(7)
        for _ in range(50):
            a, r = rnd.uniform(0, 2 * math.pi), rnd.uniform(410, 442)
            x, y, s = c + r * math.cos(a), c + r * math.sin(a), rnd.choice([1.5, 2, 3])
            d.ellipse([x - s, y - s, x + s, y + s], fill=(255, 255, 255, rnd.randint(80, 200)))

    # The frame accents on the outer rim
    layer = styled_frame(layer, t, style)
    return layer


out_dir = sys.argv[1]
for t in THEMES:
    for style in range(8):
        art = overlay(t, style).resize((450, 450), Image.LANCZOS)
        art.save(os.path.join(out_dir, "overlay_%s_%d.png" % (t["name"], style)))
        if style == 0:
            art.save(os.path.join(out_dir, "overlay_%s.png" % t["name"]))

# The fallback background: carbon fibre under the Ferrari layer.
carbon = Image.new("RGB", (S, S), (10, 10, 12))
cd = ImageDraw.Draw(carbon)
step = 12
for y in range(0, S, step):
    for x in range(0, S, step):
        v = 22 if ((x // step) + (y // step)) % 2 else 14
        cd.rectangle([x, y, x + step - 1, y + step // 2 - 1], fill=(v, v, v + 2))
        cd.rectangle([x, y + step // 2, x + step - 1, y + step - 1], fill=(36 - v, 36 - v, 38 - v))
Image.alpha_composite(carbon.convert("RGBA"), overlay(THEMES[1])).convert("RGB").resize((450, 450), Image.LANCZOS) \
    .save(os.path.join(out_dir, "bg.png"))
