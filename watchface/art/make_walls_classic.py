"""Daily wallpapers for the watch face: each source photo cropped square, enhanced for vibrant edge detail.
A seamless radial telemetry vignette keeps central readings high-contrast while maintaining full edge-to-edge photography.
Usage: make_walls.py OUT_DIR (reads art/source/*.jpg, writes wall_<category>_<n>.jpg)."""
from PIL import Image, ImageDraw, ImageEnhance, ImageFilter
import math
import os
import sys

S = 450
c = S / 2.0
here = os.path.dirname(os.path.abspath(__file__))
out_dir = sys.argv[1]

# Smooth radial contrast mask for the cockpit: the photo stays vivid around the bezel,
# while the clock and telemetry sit on a deeper, quieter center.
# Outer edge (r >= 225) is untouched; the center gets a 62% smoked-glass blend.
max_alpha = 158
mask = Image.new("L", (S, S), 0)
for y in range(S):
    for x in range(S):
        r = math.hypot(x - c, y - c)
        if r >= 225:
            val = 0
        elif r <= 102:
            val = max_alpha
        else:
            t = (r - 102) / (225 - 102)
            val = int(max_alpha * 0.5 * (1 + math.cos(math.pi * t)))
        mask.putpixel((x, y), val)
mask = mask.filter(ImageFilter.GaussianBlur(8))
dark_layer = Image.new("RGB", (S, S), (4, 6, 12))

for f in sorted(os.listdir(os.path.join(here, "source"))):
    if not f.endswith(".jpg"):
        continue
    im = Image.open(os.path.join(here, "source", f)).convert("RGB")
    side = min(im.size)
    left, top = (im.width - side) // 2, (im.height - side) // 2
    im = im.crop((left, top, left + side, top + side)).resize((S, S), Image.LANCZOS)
    im = ImageEnhance.Color(im).enhance(1.25)
    im = ImageEnhance.Contrast(im).enhance(1.10)
    im = Image.composite(dark_layer, im, mask)
    im.save(os.path.join(out_dir, "wall_" + f[:-4].replace("-", "_") + ".jpg"), quality=90, optimize=True)
print("walls done")
