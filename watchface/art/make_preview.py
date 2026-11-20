"""Render all 24 daily design variants as three theme galleries."""
from PIL import Image, ImageDraw, ImageFont
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from themes import THEMES
from date_contrast import choose_ink

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app/src/main/res/drawable-nodpi")
FONT_DIR = os.path.join(ROOT, "app/src/main/res/font")
bold = ImageFont.truetype(os.path.join(FONT_DIR, "outfit_bold.ttf"), 24)
semi = ImageFont.truetype(os.path.join(FONT_DIR, "outfit_semibold.ttf"), 15)
small = ImageFont.truetype(os.path.join(FONT_DIR, "outfit_semibold.ttf"), 14)
metric = ImageFont.truetype(os.path.join(FONT_DIR, "outfit_bold.ttf"), 24)
DESIGN_NAMES = ("Grandstand", "Twin Registers", "Race Stack", "Split Lap", "Minute Lead", "Compact", "Speed Strip", "Tachometer")

def color(rgb, a=255): return tuple(rgb) + (a,)

def render(t, n, sports=("football", "Newcastle vs ARS  ·  in 2h 14m")):
    im = Image.open(os.path.join(RES, f"wall_{t['name']}_{n+1}.jpg")).convert("RGBA")
    im.alpha_composite(Image.open(os.path.join(RES, f"overlay_{t['name']}_{n}.png")).convert("RGBA"))
    d = ImageDraw.Draw(im, "RGBA")
    d.rounded_rectangle((82,45,102,47),1,fill=color(t["left"]))
    d.rounded_rectangle((348,45,368,47),1,fill=color(t["right"]))
    _, day_choice, day_score = choose_ink(t["name"], n, "SATURDAY", 48, 24)
    day_ink = (255,255,255,255) if day_choice == "#ffffffff" else (115,0,15,255)
    date_ink = day_ink
    depth = color(t["left"])
    def raised(value, y, font, ink):
        for dx, dy in ((3,3),(2,2),(1,1)):
            d.text((225+dx,y+dy),value,font=font,fill=depth,anchor="mm")
        d.text((225,y),value,font=font,fill=ink,anchor="mm")
    raised("SATURDAY",48,ImageFont.truetype(os.path.join(FONT_DIR,"outfit_bold.ttf"),24),day_ink)
    raised("07 NOVEMBER",77,ImageFont.truetype(os.path.join(FONT_DIR,"outfit_bold.ttf"),30),date_ink)
    d.rounded_rectangle((25,105,25+35+n*4,107),1,fill=color(t["major"],190))
    d.rounded_rectangle((400-(35+n*3),230,400,232),1,fill=color(t["left"],210))
    d.rounded_rectangle((25,105,26,119+n*2),1,fill=color(t["major"],145))
    d.rounded_rectangle((424,216-n,425,230),1,fill=color(t["left"],145))
    draw_time(d,t,n)
    # Telemetry capsule: Battery on left, Heart rate on right
    cap_w, cap_h = 224, 26
    cx_c, cy_c = 225, 236
    d.rounded_rectangle((cx_c - cap_w//2, cy_c - cap_h//2, cx_c + cap_w//2, cy_c + cap_h//2), 13,
                        fill=(8, 10, 16, 235), outline=(255, 255, 255, 120), width=1)
    d.line((cx_c, cy_c - 7, cx_c, cy_c + 7), fill=(255, 255, 255, 80), width=1)

    bx, by = cx_c - 80, cy_c - 5
    d.rectangle((bx, by, bx + 15, by + 10), outline=color(t['battery'], 240), width=1)
    d.rectangle((bx + 16, by + 3, bx + 17, by + 7), fill=color(t['battery'], 240))
    d.rectangle((bx + 2, by + 2, bx + 11, by + 8), fill=color(t['battery']))
    d.text((cx_c - 36, cy_c), '86%', font=ImageFont.truetype(os.path.join(FONT_DIR, 'outfit_bold.ttf'), 15), fill=(255, 255, 255), anchor='mm')

    # Heart icon + BPM
    hx, hy, hr = cx_c + 24, cy_c - 2, 5
    red = (219, 0, 7, 255) if t["name"] != "space" else (255, 75, 175, 255)
    d.ellipse([hx - hr, hy - hr//2, hx, hy + hr//2], fill=red)
    d.ellipse([hx, hy - hr//2, hx + hr, hy + hr//2], fill=red)
    d.polygon([(hx - hr + 1, hy + 1), (hx + hr - 1, hy + 1), (hx, hy + hr + 3)], fill=red)
    d.text((cx_c + 63, cy_c), '72 BPM', font=ImageFont.truetype(os.path.join(FONT_DIR, 'outfit_bold.ttf'), 15), fill=(255, 255, 255), anchor='mm')

    # Wings
    d.rounded_rectangle((cx_c - cap_w//2 - 24, cy_c - 1, cx_c - cap_w//2 - 6, cy_c + 1), 1, fill=color(t['left']))
    d.rounded_rectangle((cx_c + cap_w//2 + 6, cy_c - 1, cx_c + cap_w//2 + 24, cy_c + 1), 1, fill=color(t['right']))

    for cx, name, val, col in ((120,"FPL","10d 1h",t["battery"]),(225,"STEPS","6.2k",t["steps"]),(330,"KCAL","420",t["kcal"])):
        d.ellipse((cx-45,260,cx+45,350),fill=(5,7,13,190),outline=color(col,225),width=2)
        d.ellipse((cx-38,267,cx+38,343),outline=(255,255,255,80),width=1)
        d.arc((cx-37,268,cx+37,342),135,405,fill=color(col),width=6)
        fnt = ImageFont.truetype(os.path.join(FONT_DIR, "outfit_bold.ttf"), 20) if name == "FPL" else metric
        d.text((cx,305),val,font=fnt,fill=color(col),anchor="mm")
        d.rounded_rectangle((cx-19,354,cx+19,356),1,fill=color(col,210))
        d.text((cx,365),name,font=small,fill=(255,255,255),anchor="mm")
    sports_pill(im, t, sports)
    return im.convert("RGB")


# The sport icons are defined once, in the sports app (~/Afterglow/watch-sports/art/icons.py)
sys.path.insert(0, os.path.join(os.path.dirname(ROOT), "watch-sports", "art"))
import icons


def sports_pill(im, t, sports=("football", "Newcastle vs ARS  ·  in 2h 14m"), rail=None):
    """The sports widget as the face draws it: solid plate, border in the day's accent, the sport's icon tinted
    the same (sports[0]: "cricket", "football", "f1", or "track:<circuitId>" in race week), the line (white, 1px
    shadow) centred after the icon, and the rail along the top: rail = RailComplication's value (0-105) or None."""
    d = ImageDraw.Draw(im, "RGBA")
    d.rounded_rectangle((55,379,395,411),16,fill=(0,0,0,248),outline=color(t["major"],0xaa),width=2)
    if sports[0].startswith("track:"):
        icons.draw_track(im, sports[0][6:], (55+37, 379+5, 22), color(t["major"]))
    else:
        icons.draw(im, sports[0], (55+37, 379+5, 22), color(t["major"]))
    f = ImageFont.truetype(os.path.join(FONT_DIR,"outfit_bold.ttf"),17)
    cx = 55 + 63 + 248 // 2
    d.text((cx+1,397),sports[1],font=f,fill=(0,0,0,204),anchor="mm")
    d.text((cx,396),sports[1],font=f,fill=(255,255,255),anchor="mm")
    if rail is not None:
        # on a layer of its own: drawing straight onto an RGBA image replaces pixels instead of blending
        layer = Image.new("RGBA", im.size, (0, 0, 0, 0))
        d = ImageDraw.Draw(layer)
        test = rail > 100.5
        n, gap = (5, 6) if test else (10, 4)
        w = (210 - gap * (n - 1)) / n
        for i in range(n):
            x = 120 + i * (w + gap)
            lit = (rail - 100 >= i + 1) if test else (rail >= (i + 1) * 10)
            if lit:
                d.rounded_rectangle((x, 382, x + w, 386), 2, fill=color(t["major"]))
            else:
                d.rounded_rectangle((x, 382.5, x + w, 385.5), 1.5, fill=(255, 255, 255, 64))
        im.alpha_composite(layer)


def draw_time(d,t,style):
    white=color(t["hours"]); accent=color(t["minutes"]); colon=color(t["colon"])
    shadow=(0, 0, 0, 240)
    def text(value,x,y,size,fill):
        font=ImageFont.truetype(os.path.join(FONT_DIR,"outfit_bold.ttf"),size)
        d.text((x+2,y+2),value,font=font,fill=shadow,anchor="mm")
        d.text((x,y),value,font=font,fill=fill,anchor="mm")
    if style == 2:
        text("11",225,124,55,white); text(":",225,155,24,colon); text("33",225,187,55,accent)
    elif style == 1:
        text("11",130,167,84,white); text(":",225,164,72,colon); text("33",320,167,84,accent)
    elif style == 3:
        d.rounded_rectangle((223,116,227,212),1,fill=color(t["major"],180))
        text("11",115,168,94,white); text("33",335,168,94,accent)
    elif style == 4:
        text("11",100,166,76,white); text(":",198,166,66,colon); text("33",320,160,108,accent)
    elif style == 5:
        text("11",128,166,88,white); text(":",225,164,74,colon); text("33",322,166,88,accent)
    elif style == 6:
        text("11",122,164,92,white); text(":",225,164,78,colon); text("33",328,164,92,accent)
    elif style == 7:
        text("11",90,168,72,white); text(":",177,168,70,colon); text("33",315,160,105,accent)
    else:
        text("11",75,169,99,white); text(":",225,168,98,colon); text("33",332,169,99,accent)

if __name__ == "__main__":
    faces = {(t["name"],i): render(t,i) for t in THEMES for i in range(8)}
    faces[("ferrari",0)].save(os.path.join(RES,"preview.png"))
    out_dir = sys.argv[1] if len(sys.argv)>1 else os.path.join(ROOT,"art","design-previews")
    os.makedirs(out_dir,exist_ok=True)
    for t in THEMES:
        sheet=Image.new("RGB",(4*450,2*478),(5,7,12))
        draw=ImageDraw.Draw(sheet)
        for i in range(8):
            x=(i%4)*450; y=(i//4)*478
            draw.rectangle((x,y,x+450,y+28),fill=(14,17,25))
            draw.text((x+14,y+14),f"{i+1:02d}  {DESIGN_NAMES[i].upper()}",font=small,fill=color(t["major"]),anchor="lm")
            sheet.paste(faces[(t["name"],i)],(x,y+28))
        sheet.save(os.path.join(out_dir,f"{t['name']}-designs.png"))
    print(out_dir)
