"""This month's 24 watch-face photos, ready to show: picked from Wikimedia Commons, processed like the face's
built-in walls, published for the watch to download (WallpaperComplication).

    python3 wallpapers/make_month.py [YYYY-MM]          # default: this month (UTC)

Writes wallpapers/<YYYY-MM>/{arsenal,ferrari,space}-<1..8>.jpg (450 x 450), CREDITS.tsv, updates
wallpapers/current.json (what the watch reads) and wallpapers/history.txt (so no photo is used twice), and
keeps only the two newest months. Run monthly by .github/workflows/watch-wallpapers.yml in the afterglow repo.

Each theme draws from hand-picked categories, newest uploads first; photo n of a theme shows on the days the
face uses its design n ([DAY_OF_YEAR] % 24 = 3 (n - 1) + theme), so the band behind the date suits that
design's ink: four designs print the date dark (DARK_INK), the rest white.
"""
import io
import json
import math
import os
import re
import sys
import time
import urllib.parse
import urllib.request
from datetime import datetime, timezone

from PIL import Image, ImageEnhance, ImageFilter

HERE = os.path.dirname(os.path.abspath(__file__))
API = "https://commons.wikimedia.org/w/api.php"
UA = "WatchFaceWallpapers/1.0 (personal, non-commercial; one run a month)"
S = 450

# Where each theme's photos come from, best first: a Commons category, or "search:" a Commons search (deepcat:
# takes in a category's subcategories). Newest uploads first within each.
SOURCES = {
    "arsenal": ["Emirates Stadium", "Arsenal W.F.C.", "Arsenal F.C. supporters", "Bukayo Saka", "Martin Ødegaard",
                "Declan Rice", "William Saliba", "Gabriel Magalhães", "Leandro Trossard", "Kai Havertz"],
    "ferrari": ['search:deepcat:"Scuderia Ferrari" "Grand Prix" filetype:bitmap',
                'search:deepcat:"Lewis Hamilton" Ferrari filetype:bitmap',
                'search:deepcat:"Charles Leclerc" "Grand Prix" filetype:bitmap', "Ferrari SF-26", "Ferrari SF-25"],
    "space": ["Hubble images of nebulae", "Hubble images of galaxies", "Images by the James Webb Space Telescope"],
}
# A photo's title must name the team (racing photos, not red carpets) ...
MUST = {"ferrari": re.compile(r"Ferrari|Leclerc|Hamilton|SF-2\d|Scuderia", re.I)}
# ... and mustn't be one of these: off-theme events, or news graphics with insets, labels and diagrams.
NOT = {
    "ferrari": re.compile(r"Cannes|Festival|Film|Downing|Minister|Starmer|Lego|Gala|Award|Premiere|Wedding|Vogue"
                          r"|Fashion|Concert|Interview|Press|Museum|Stradale|Roma|Purosangue|road car", re.I),
    "arsenal": re.compile(r"shield|trophy cabinet|programme|museum|capsule|plaque|sign\b|statue", re.I),
    "space": re.compile(r"locates|evidence|finds|spots|reveals|discover|comparison|compass|annotat|label|diagram"
                        r"|illustration|artist|concept|spectrum|field|cygni|light curve|infographic|animation", re.I),
}
# Designs whose date the face prints dark (watchface art/date_contrast.py chose charcoal for their photos):
# theme -> design numbers (1-8). Keep in step with the face.
DARK_INK = {"arsenal": {5, 6}, "ferrari": {5, 7}}
SKIP = re.compile(r"logo|map|diagram|chart|graph|plot|spectrum|signed|poster|ticket|card|badge|crest|kit|shirt"
                  r"|scheme|infographic|annotated|compass|label|screenshot|text", re.I)


def api(params):
    q = urllib.parse.urlencode(dict(params, format="json"))
    for attempt in range(4):
        try:
            req = urllib.request.Request(API + "?" + q, headers={"User-Agent": UA})
            with urllib.request.urlopen(req, timeout=40) as r:
                return json.load(r)
        except Exception as e:
            time.sleep(5 * (attempt + 1))
            err = e
    raise err


def candidates(theme, used):
    """Photos for [theme], newest uploads first, that suit a round 450 px face."""
    seen, out = set(), []
    for src in SOURCES[theme]:
        time.sleep(1.5)
        info = {"prop": "imageinfo", "iiprop": "size|mime|url|extmetadata", "iiurlwidth": 900,
                "iiextmetadatafilter": "LicenseShortName|Artist|ObjectName"}
        if src.startswith("search:"):
            d = api(dict(info, action="query", generator="search", gsrnamespace=6, gsrsearch=src[7:],
                         gsrsort="create_timestamp_desc", gsrlimit=50))
        else:
            d = api(dict(info, action="query", generator="categorymembers", gcmtitle="Category:" + src,
                         gcmtype="file", gcmsort="timestamp", gcmdir="desc", gcmlimit=50))
        pages = sorted(d.get("query", {}).get("pages", {}).values(), key=lambda p: p.get("index", 0))
        for p in pages:
            ii = (p.get("imageinfo") or [None])[0]
            if not ii or p["title"] in used or p["title"] in seen:
                continue
            w, h, mime = ii.get("width", 0), ii.get("height", 0), ii.get("mime", "")
            photo = mime == "image/jpeg" or (theme == "space" and mime == "image/png")
            if not photo or min(w, h) < 800 or not 0.6 <= w / h <= 2.0 or SKIP.search(p["title"]):
                continue
            if (theme in MUST and not MUST[theme].search(p["title"])) or (theme in NOT and NOT[theme].search(p["title"])):
                continue
            meta = ii.get("extmetadata", {})
            artist = re.sub(r"<[^>]+>", "", meta.get("Artist", {}).get("value", "")).strip()[:80]
            seen.add(p["title"])
            out.append({"title": p["title"], "url": ii.get("thumburl") or ii["url"], "page": ii.get("descriptionurl", ""),
                        "licence": meta.get("LicenseShortName", {}).get("value", ""), "artist": artist,
                        "order": len(out)})
    return out


def lum(im, box):
    g = im.convert("L").crop(box).resize((60, 20))
    return sum(g.getdata()) / (255 * 1200)


def process(raw, dark_ink):
    """Like the face's built-in walls (make_walls.py): square crop, livelier colour, a smoked centre for the
    clock; then a band behind the date, dark for white ink or light for dark ink, until it reads. None for a
    photo that's mostly empty black (a sparse star field reads as a blank face)."""
    im = Image.open(io.BytesIO(raw)).convert("RGB")
    side = min(im.size)
    left, top = (im.width - side) // 2, (im.height - side) // 2
    im = im.crop((left, top, left + side, top + side)).resize((S, S), Image.LANCZOS)
    g = im.convert("L").resize((90, 90))
    if sum(1 for v in g.getdata() if v < 14) > 0.7 * 8100:
        return None
    if sum(1 for v in g.getdata() if v <= 2) > 0.06 * 8100:  # cut-off corners (old Hubble "staircase" mosaics)
        return None
    im = ImageEnhance.Contrast(ImageEnhance.Color(im).enhance(1.25)).enhance(1.10)
    c = S / 2
    mask = Image.new("L", (S, S), 0)
    px = mask.load()
    for y in range(S):
        for x in range(S):
            r = math.hypot(x - c, y - c)
            px[x, y] = 0 if r >= 225 else 158 if r <= 102 else int(158 * 0.5 * (1 + math.cos(math.pi * (r - 102) / 123)))
    im = Image.composite(Image.new("RGB", (S, S), (4, 6, 12)), im, mask.filter(ImageFilter.GaussianBlur(8)))
    # the date sits at y 34-97 (weekday 48, date 77)
    box = (80, 30, 370, 96)
    band = Image.new("L", (S, S), 0)
    bp = band.load()
    for y in range(S):
        a = 1.0 if 26 <= y <= 100 else max(0.0, 1 - min(abs(y - 26), abs(y - 100)) / 24)
        for x in range(S):
            bp[x, y] = int(255 * a)
    tone = (250, 248, 244) if dark_ink else (4, 6, 12)
    for k in range(1, 9):
        need_ok = lum(im, box) >= 0.55 if dark_ink else lum(im, box) <= 0.22
        if need_ok:
            break
        layer = Image.new("RGB", (S, S), tone)
        im = Image.composite(layer, im, band.point(lambda v: v * 0.12))
    return im


def main():
    month = sys.argv[1] if len(sys.argv) > 1 else datetime.now(timezone.utc).strftime("%Y-%m")
    hist_path = os.path.join(HERE, "history.txt")
    used = set(open(hist_path).read().split("\n")) if os.path.exists(hist_path) else set()
    out_dir = os.path.join(HERE, month)
    os.makedirs(out_dir, exist_ok=True)
    walls, credits = [], ["file\ttitle\tauthor\tlicence\tsource"]
    for theme in SOURCES:
        pool = candidates(theme, used)
        picked, prefixes = [], set()
        for c in pool:  # newest first, one per series ("Holloway, match day ... (1)", "(2)" ...)
            key = re.sub(r"[\d_()\-.,\s]+", "", c["title"].lower())[:22]
            if key in prefixes:
                continue
            n = len(picked) + 1
            im = None
            for attempt in range(5):  # Commons asks for patience ("429 Too many requests"): wait longer, then again
                try:
                    time.sleep(2 if attempt == 0 else 20 * attempt)
                    req = urllib.request.Request(c["url"], headers={"User-Agent": UA})
                    raw = urllib.request.urlopen(req, timeout=60).read()
                    im = process(raw, n in DARK_INK.get(theme, set()))
                    break
                except Exception as e:
                    print("retry" if attempt < 4 else "skip", c["title"][5:60], e)
            if im is None:
                continue
            name = f"{theme}-{n}.jpg"
            im.save(os.path.join(out_dir, name), quality=84, optimize=True, progressive=False)
            prefixes.add(key)
            picked.append(c)
            walls.append({"theme": theme, "n": n, "file": f"{month}/{name}"})
            credits.append("\t".join([name, c["title"][5:], c["artist"], c["licence"], c["page"]]))
            if len(picked) == 8:
                break
        print(theme, len(picked), "of", len(pool))
        if len(picked) < 8:
            sys.exit(f"only {len(picked)} photos for {theme}: keeping last month's set")
        used.update(c["title"] for c in picked)
    open(os.path.join(out_dir, "CREDITS.tsv"), "w").write("\n".join(credits) + "\n")
    json.dump({"month": month, "walls": walls}, open(os.path.join(HERE, "current.json"), "w"), indent=1)
    open(hist_path, "w").write("\n".join(sorted(t for t in used if t)) + "\n")
    months = sorted(d for d in os.listdir(HERE) if re.fullmatch(r"\d{4}-\d{2}", d))
    for old in months[:-2]:
        for f in os.listdir(os.path.join(HERE, old)):
            os.remove(os.path.join(HERE, old, f))
        os.rmdir(os.path.join(HERE, old))
    print("published", month)


if __name__ == "__main__":
    main()
