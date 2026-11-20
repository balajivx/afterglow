"""The three day themes, each matching that day's wallpaper category (wallpaper = [DAY_OF_YEAR] % 24, and its
category = [DAY_OF_YEAR] % 3, in this order). Read by make_bg.py (the overlay art) and make_face.py
(watchface.xml). Colours as (r, g, b). High saturation and calibrated contrast for crystal clarity."""

THEMES = [
    {   # Arsenal: Highbury crimson and pure white with Art Deco gold accents
        "name": "arsenal",
        "left": (219, 0, 7), "left_line": (255, 255, 255),
        "right": (255, 255, 255), "right_line": (219, 0, 7),
        "glass": (26, 4, 6), "ticks": (255, 255, 255), "major": (245, 205, 115),
        "hours": (255, 255, 255), "minutes": (255, 55, 65), "colon": (245, 205, 115), "date": (245, 205, 115),
        "battery": (255, 255, 255), "steps": (255, 55, 65), "kcal": (245, 205, 115),
        "leds": [(219, 0, 7)] * 5 + [(255, 255, 255)] * 4 + [(245, 205, 115)] * 2,
        "stars": False,
    },
    {   # Ferrari: Rosso Corsa and Scuderia yellow
        "name": "ferrari",
        "left": (219, 0, 7), "left_line": (255, 255, 255),
        "right": (255, 220, 0), "right_line": (212, 0, 0),
        "glass": (6, 6, 10), "ticks": (255, 255, 255), "major": (255, 220, 0),
        "hours": (255, 255, 255), "minutes": (255, 220, 0), "colon": (225, 6, 0), "date": (255, 220, 0),
        "battery": (255, 255, 255), "steps": (255, 50, 50), "kcal": (255, 220, 0),
        "leds": [(219, 0, 7)] * 4 + [(255, 220, 0)] * 5 + [(255, 255, 255)] * 2,
        "stars": False,
    },
    {   # Space: Electric cyber cyan, neon violet, and hyper pink against deep JWST cosmos
        "name": "space",
        "left": (140, 100, 255), "left_line": (0, 245, 255),
        "right": (0, 245, 255), "right_line": (140, 100, 255),
        "glass": (4, 6, 22), "ticks": (190, 215, 255), "major": (0, 245, 255),
        "hours": (255, 255, 255), "minutes": (0, 245, 255), "colon": (185, 110, 255), "date": (210, 240, 255),
        "battery": (0, 245, 255), "steps": (185, 110, 255), "kcal": (255, 75, 175),
        "leds": [(0, 245, 255)] * 4 + [(140, 100, 255)] * 4 + [(255, 75, 175)] * 3,
        "stars": True,
    },
]


def argb(rgb, alpha=255):
    """'#ffrrggbb' for watchface.xml."""
    return "#%02x%02x%02x%02x" % ((alpha,) + tuple(rgb))
