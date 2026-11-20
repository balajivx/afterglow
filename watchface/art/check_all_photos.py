"""Draw the Clean Feed v25 face over every photo (24 built-in + 24 monthly) into one contact sheet, to check legibility."""
import glob, os, sys
from PIL import Image, ImageDraw, ImageFont

WF = os.path.expanduser("~/Afterglow/watchface")
RES = f"{WF}/app/src/main/res/drawable-nodpi"
F = f"{WF}/app/src/main/res/font"
MON = sys.argv[1]
OUT = sys.argv[2]
ACC = {"arsenal": ((219, 0, 7), (255, 255, 255)), "ferrari": ((255, 220, 0), (0, 0, 0)), "space": ((0, 245, 255), (0, 0, 0))}
semi = lambda s: ImageFont.truetype(f"{F}/barlow_semibold.ttf", s)
med = lambda s: ImageFont.truetype(f"{F}/barlow_medium.ttf", s)
scrim = Image.open(f"{RES}/cf_scrim.png").convert("RGBA")
tab = Image.open(f"{RES}/cf_tab.png").convert("RGBA")
tabd = Image.open(f"{RES}/cf_tab_d.png").convert("RGBA")


def tint(img, rgb):
    t = Image.new("RGBA", img.size, rgb + (255,))
    t.putalpha(img.getchannel("A"))
    return t


def text(layer, box, s, font, fill, align):
    """WFF-like: text vertically centred on its cap height inside the box."""
    x, y, w, h = box
    d = ImageDraw.Draw(layer)
    tw = d.textlength(s, font=font)
    l, t, r, b = font.getbbox("H")
    ty = y + (h - (b - t)) / 2 - t
    tx = {"START": x, "END": x + w - tw, "CENTER": x + (w - tw) / 2}[align]
    d.text((tx, ty), s, font=font, fill=fill)


OUTLINE = eval(os.environ.get("OUTLINE", "[]")); SA = int(os.environ.get("SA", "180"))


def face(photo, theme):
    acc, on = ACC[theme]
    im = Image.open(photo).convert("RGBA").resize((450, 450))
    im.alpha_composite(scrim)
    L = Image.new("RGBA", (450, 450), (0, 0, 0, 0))
    d = ImageDraw.Draw(L)
    # date plate
    d.rectangle([70, 84, 394, 128], fill=(8, 8, 10, 235))
    L.alpha_composite(tint(tabd, acc), (50, 84))
    text(L, (74, 84, 156, 44), "WEDNESDAY", semi(32), (255, 255, 255, 255), "END")
    text(L, (242, 84, 150, 44), "20 NOV", semi(32), acc + (255,), "START")
    # time with shadow
    OFFS = [(dx, dy, None) for dx, dy in OUTLINE] + [(3, 4, None), (0, 0, 1)]
    for dx, dy, cols in OFFS:
        c = lambda c: (0, 0, 0, SA) if cols is None else c
        sub = Image.new("RGBA", (450, 450), (0, 0, 0, 0)); Lsave = L; L = sub
        text(L, (22 + dx, 158 + dy, 190, 104), "10", semi(150), c((255, 255, 255, 255)), "END")
        text(L, (205 + dx, 152 + dy, 40, 104), ":", semi(150), c((255, 255, 255, 230)), "CENTER")
        text(L, (238 + dx, 158 + dy, 190, 104), "08", semi(150), c(acc + (255,)), "START")
        Lsave.alpha_composite(L); L = Lsave
    # readings
    for cx, v, lab in ((69, "12345", "STEPS"), (147, "179", "KCAL"), (225, "65", "BPM"), (303, "57%", "BATTERY"), (381, "1d 2h", "FPL")):
        text(L, (cx - 39, 266, 78, 46), v, semi(34), (255, 255, 255, 255), "CENTER")
        text(L, (cx - 39, 311, 78, 24), lab, med(15), (255, 255, 255, 179), "CENTER")
    # caption bar
    d.rectangle([116, 342, 380, 378], fill=(8, 8, 10, 235))
    L.alpha_composite(tint(tab, acc), (68, 342))
    text(L, (128, 342, 248, 36), "NZ 368 · IND 159/1 · Day 3", semi(23), (255, 255, 255, 255), "START")
    for i in range(10):
        d.rectangle([120 + i * 21, 381, 120 + i * 21 + 18, 384], fill=acc + (255,) if i < 3 else (255, 255, 255, 60))
    im.alpha_composite(L)
    mask = Image.new("L", (450, 450), 0)
    ImageDraw.Draw(mask).ellipse([0, 0, 449, 449], fill=255)
    out = Image.new("RGB", (450, 450), (30, 30, 30))
    out.paste(im, (0, 0), mask)
    return out


items = []
ONLY = os.environ.get("ONLY")
for th in ACC:
    for p in sorted(glob.glob(f"{RES}/wall_{th}_*.jpg")):
        items.append((p, th, "built-in " + os.path.basename(p)[5:-4]))
for th in ACC:
    for p in sorted(glob.glob(f"{MON}/{th}-*.jpg")):
        items.append((p, th, "monthly " + os.path.basename(p)[:-4]))
S, cols = 300, 8
rows = (len(items) + cols - 1) // cols
items = [it for it in items if not ONLY or any(k in it[2] for k in ONLY.split(","))]
rows = (len(items) + cols - 1) // cols
sheet = Image.new("RGB", (cols * S, rows * (S + 22)), (30, 30, 30))
dd = ImageDraw.Draw(sheet)
for i, (p, th, name) in enumerate(items):
    x, y = (i % cols) * S, (i // cols) * (S + 22)
    sheet.paste(face(p, th).resize((S - 6, S - 6), Image.LANCZOS), (x + 3, y + 3))
    dd.text((x + 8, y + S - 2), f"{i + 1}. {name}", fill=(220, 220, 220), font=med(16))
sheet.save(OUT)
# one at full size per half, for detail
print(len(items), "faces ->", OUT)
