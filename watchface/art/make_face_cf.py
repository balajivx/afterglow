"""Clean Feed (chosen 20 Nov 2026, round 3 of the redesign: art/redesign/make_broadcast3.py clean_feed): the time
centred and upright, the date above, five readings in one row, and the TV caption bar for the sports widget.

    python3 art/make_face_cf.py app/src/main/res/raw/watchface.xml

Kept from the classic face (art/make_face.py, still there to roll back to): the 24 built-in photos of the day, the
monthly photo slot (6), the theme of the day ([DAY_OF_YEAR] % 3: arsenal, ferrari, space) and every data source:
steps (3), calories (0), heart rate (1), FPL (4), the sports line (2) and its rail (5), battery. Dropped: the
per-second lap ring and the step lights (simpler, and less drawing). Ambient (not used by its owner): time only.
Fonts: Barlow Condensed (OFL; art/redesign/fonts/OFL-barlow.txt), Outfit.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from make_face import HIDE, ind, monthly_photo, wallpaper  # noqa: E402
from themes import argb  # noqa: E402

THEMES = [  # the day's colour (the same as the approved mock-ups)
    {"name": "arsenal", "accent": (219, 0, 7), "on_accent": (255, 255, 255)},
    {"name": "ferrari", "accent": (255, 220, 0), "on_accent": (0, 0, 0)},
    {"name": "space", "accent": (0, 245, 255), "on_accent": (0, 0, 0)},
]
WHITE, DIM, LABEL = "#ffffffff", "#aaffffff", "#99ffffff"
SEMI, MED = "barlow_semibold", "barlow_medium"


def by_theme(make, tag):
    """[make](t) for today's theme ([DAY_OF_YEAR] % 3, as the photo's)."""
    exprs = "\n".join('<Expression name="%s%d"><![CDATA[[DAY_OF_YEAR] %% 3 == %d]]></Expression>' % (tag, k, k) for k in range(3))
    comps = "\n".join(f'<Compare expression="{tag}{k}">\n{ind(make(t), 4)}\n</Compare>' for k, t in enumerate(THEMES))
    return f'''<Condition>
    <Expressions>
{ind(exprs, 8)}
    </Expressions>
{ind(comps, 4)}
    <Default>
{ind(make(THEMES[1]), 8)}
    </Default>
</Condition>'''


def label(x, y, w, s, size=10, color=LABEL, family=MED, align="CENTER", hide=True):
    return f'''<PartText x="{x}" y="{y}" width="{w}" height="{int(size * 1.6)}">
    {HIDE if hide else ""}<Text align="{align}"><Font family="{family}" size="{size}" color="{color}"><Template>{s}</Template></Font></Text>
</PartText>'''


def scrim():
    return f'''<!-- Darkening for legibility, over any photo (built-in or monthly) -->
<PartImage x="0" y="0" width="450" height="450">
    {HIDE}
    <Image resource="cf_scrim" />
</PartImage>'''


# The date's plate mirrors the caption bar below it (a day's-colour tab, then black), so the face is framed top and
# bottom like a TV broadcast and the date reads on any photo.
DX0, DX1, DY0, DY1 = 50, 394, 84, 128


def date():
    """"FRIDAY  20 NOV" on a black plate: the face format has no upper-case, so weekday and month are chosen by
    number. The weekday (white) ends left of centre and the day + month (the day's colour, like the minutes) start
    right of it."""
    days = ["SUNDAY", "MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY"]  # [DAY_OF_WEEK] 1 = Sunday
    months = ["JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"]
    size, ty = 32, DY0 - 4
    th = int(size * 1.6)
    wexpr = "\n".join(f'<Expression name="wd{i + 1}"><![CDATA[[DAY_OF_WEEK] == {i + 1}]]></Expression>' for i in range(7))
    wcomp = "\n".join(f'<Compare expression="wd{i + 1}">{label(74, ty, 156, d, size, WHITE, SEMI, "END", False)}</Compare>' for i, d in enumerate(days))
    mexpr = "\n".join(f'<Expression name="mo{i + 1}"><![CDATA[[MONTH] == {i + 1}]]></Expression>' for i in range(12))

    def mtext(m):
        return lambda t: f'''<PartText x="242" y="{ty}" width="150" height="{th}">
    <Text align="START"><Font family="{SEMI}" size="{size}" color="{argb(t["accent"])}"><Template>%s {m}<Parameter expression="[DAY]" /></Template></Font></Text>
</PartText>'''
    mcomp = "\n".join(f'<Compare expression="mo{i + 1}">\n{ind(by_theme(mtext(m), f"dm{i}_"), 4)}\n</Compare>' for i, m in enumerate(months))

    def tab(t):
        return f'''<PartImage x="{DX0}" y="{DY0}" width="32" height="{DY1 - DY0}" tintColor="{argb(t["accent"])}">
    <Image resource="cf_tab_d" />
</PartImage>'''
    return f'''<!-- Weekday and date on a plate -->
<Group x="0" y="0" width="450" height="450">
    {HIDE}
    <PartDraw x="{DX0 + 20}" y="{DY0}" width="{DX1 - DX0 - 20}" height="{DY1 - DY0}">
        <Rectangle x="0" y="0" width="{DX1 - DX0 - 20}" height="{DY1 - DY0}"><Fill color="#eb08080a" /></Rectangle>
    </PartDraw>
{ind(by_theme(tab, "dt"), 4)}
    <Condition>
        <Expressions>
{ind(wexpr, 12)}
        </Expressions>
{ind(wcomp, 8)}
    </Condition>
    <Condition>
        <Expressions>
{ind(mexpr, 12)}
        </Expressions>
{ind(mcomp, 8)}
    </Condition>
</Group>'''


SHADOW = "#b4000000"  # a hard drop shadow under the time, so it reads on any photo (red minutes on a red banner)
OUTLINE = "#6e000000"  # eight copies 2 px around each digit: a soft outline (red on red, yellow on DHL yellow)


def time():
    """The hour ends just left of the colon, the minutes start just right of it; the colon sits on the centre line.
    Under each part (screen on only): a thin dark outline (eight copies 2 px around) and a drop shadow 3 px right, 4 down."""
    def hour(dx, dy, color, hide=""):
        return f'''<DigitalClock x="{22 + dx}" y="{128 + dy}" width="190" height="150">
    {hide}<TimeText x="0" y="0" width="190" height="150" format="h" hourFormat="SYNC_TO_DEVICE" align="END">
        <Font family="{SEMI}" size="150" color="{color}" />
    </TimeText>
</DigitalClock>'''

    def colon(dx, dy, color, hide=""):
        return f'''<PartText x="{205 + dx}" y="{122 + dy}" width="40" height="150">
    {hide}<Text align="CENTER"><Font family="{SEMI}" size="150" color="{color}"><Template>:</Template></Font></Text>
</PartText>'''

    def mins(dx, dy, color, hide=""):
        return f'''<DigitalClock x="{238 + dx}" y="{128 + dy}" width="190" height="150">
    {hide}<TimeText x="0" y="0" width="190" height="150" format="mm" align="START">
        <Font family="{SEMI}" size="150" color="{color}" />
    </TimeText>
</DigitalClock>'''
    ring = [(-2, 0), (2, 0), (0, -2), (0, 2), (-2, -2), (2, 2), (-2, 2), (2, -2)]  # a thin dark outline
    shadow = "\n".join(f(dx, dy, OUTLINE) for dx, dy in ring for f in (hour, colon, mins))
    shadow += "\n" + "\n".join(f(3, 4, SHADOW) for f in (hour, colon, mins))
    shadow = f'''<Group x="0" y="0" width="450" height="450">
    {HIDE}
{ind(shadow, 4)}
</Group>'''
    return f'''<!-- The time: tall, upright, the minutes in the day's colour, on a drop shadow -->
{shadow}
{hour(0, 0, WHITE)}
{colon(0, 0, "#e6ffffff")}
{by_theme(lambda t: mins(0, 0, argb(t["accent"])), "mn")}'''


# The five readings: value at y 283-305, label under it. Centres as in the mock-up.
COLS = {"steps": 69, "kcal": 147, "hr": 225, "battery": 303, "fpl": 381}
W = 78
VY, VH, LY = 266, 46, 311   # value box top and height, label top


def value_text(expr_template):
    return f'''<PartText x="0" y="0" width="{W}" height="{VH}">
            <Text align="CENTER"><Font family="{SEMI}" size="34" color="{WHITE}"><Template>{expr_template}</Template></Font></Text>
        </PartText>'''


def reading_slot(slot_id, key, types, provider, provider_type, customizable, bodies):
    x = COLS[key] - W // 2
    comps = "\n    ".join(f'<Complication type="{t}">\n        {b}\n    </Complication>' for t, b in bodies)
    return f'''<ComplicationSlot slotId="{slot_id}" x="{x}" y="{VY}" width="{W}" height="{VH}" supportedTypes="{types}" isCustomizable="{customizable}">
    {HIDE}
    <DefaultProviderPolicy defaultSystemProvider="EMPTY" primaryProvider="{provider}" primaryProviderType="{provider_type}" />
    <BoundingBox x="0" y="0" width="{W}" height="{VH}" />
    {comps}
</ComplicationSlot>'''


def readings():
    oh = "com.heytap.wearable.health/com.heytap.wearable.health.complication.wearos."
    num = '%s<Parameter expression="floor([COMPLICATION.RANGED_VALUE_VALUE])" />'
    txt = '%s<Parameter expression="[COMPLICATION.TEXT]" />'
    out = [
        reading_slot(3, "steps", "RANGED_VALUE", oh + "StepComplicationService", "RANGED_VALUE", "FALSE",
                     [("RANGED_VALUE", value_text(num))]),
        reading_slot(0, "kcal", "RANGED_VALUE SHORT_TEXT", oh + "CaloriesComplicationService", "RANGED_VALUE", "TRUE",
                     [("RANGED_VALUE", value_text(num)), ("SHORT_TEXT", value_text(txt))]),
        reading_slot(1, "hr", "SHORT_TEXT", oh + "HeartRateComplicationService", "SHORT_TEXT", "TRUE",
                     [("SHORT_TEXT", value_text(txt))]),
        reading_slot(4, "fpl", "RANGED_VALUE SHORT_TEXT", "com.balaji.watchsports/com.balaji.watchsports.FplDeadlineComplication",
                     "RANGED_VALUE", "TRUE", [("RANGED_VALUE", value_text(txt)), ("SHORT_TEXT", value_text(txt))]),
    ]
    battery = f'''<PartText x="{COLS["battery"] - W // 2}" y="{VY}" width="{W}" height="{VH}">
    {HIDE}<Text align="CENTER"><Font family="{SEMI}" size="34" color="{WHITE}"><Template>%s%%<Parameter expression="[BATTERY_PERCENT]" /></Template></Font></Text>
</PartText>'''
    labels = "\n".join(label(COLS[k] - W // 2, LY, W, s, 15, "#b3ffffff") for k, s in
                       [("steps", "STEPS"), ("kcal", "KCAL"), ("hr", "BPM"), ("battery", "BATTERY"), ("fpl", "FPL")])
    return "<!-- Five readings in one row: steps, calories, heart rate, battery, FPL -->\n" + "\n\n".join(out) + "\n\n" + battery + "\n" + labels


# The caption bar (round 1, kept exactly): a slanted team-colour tab with the sport icon, then the line on black.
BX0, BX1, BY0, BY1 = 76, 380, 342, 378


def caption_plate():
    def tab(t):
        return f'''<PartImage x="{BX0 - 8}" y="{BY0}" width="52" height="36" tintColor="{argb(t["accent"])}">
    <Image resource="cf_tab" />
</PartImage>'''
    return f'''<!-- The TV caption bar's plate: the black bar and the day's colour tab -->
<Group x="0" y="0" width="450" height="450">
    {HIDE}
    <PartDraw x="{BX0 + 40}" y="{BY0}" width="{BX1 - BX0 - 40}" height="{BY1 - BY0}">
        <Rectangle x="0" y="0" width="{BX1 - BX0 - 40}" height="{BY1 - BY0}"><Fill color="#eb08080a" /></Rectangle>
    </PartDraw>
{ind(by_theme(tab, "tb"), 4)}
</Group>'''


def caption_slot():
    def ico(t):
        return f'''<PartImage x="6" y="7" width="22" height="22" tintColor="{argb(t["on_accent"])}">
    <Image resource="[COMPLICATION.MONOCHROMATIC_IMAGE]" />
</PartImage>'''
    text = lambda tpl: f'''<PartText x="52" y="0" width="{BX1 - BX0 - 56}" height="{BY1 - BY0}">
            <Text align="START" ellipsis="TRUE"><Font family="{SEMI}" size="23" color="{WHITE}"><Template>{tpl}</Template></Font></Text>
        </PartText>'''
    return f'''<!-- The sports widget (watch-sports NextUpComplication) in the caption bar -->
<ComplicationSlot slotId="2" x="{BX0 - 8}" y="{BY0}" width="{BX1 - BX0 + 8}" height="{BY1 - BY0}" supportedTypes="LONG_TEXT SHORT_TEXT" isCustomizable="TRUE">
    {HIDE}
    <DefaultProviderPolicy defaultSystemProvider="EMPTY"
        primaryProvider="com.balaji.watchsports/com.balaji.watchsports.NextUpComplication"
        primaryProviderType="LONG_TEXT"
        secondaryProvider="com.google.android.calendar/com.google.android.apps.calendar.wear.complication.NextEventComplicationService"
        secondaryProviderType="SHORT_TEXT" />
    <BoundingBox x="0" y="0" width="{BX1 - BX0 + 8}" height="{BY1 - BY0}" />
    <Complication type="LONG_TEXT">
{ind(by_theme(ico, "ic"), 8)}
        {text('%s<Parameter expression="[COMPLICATION.TEXT]" />')}
    </Complication>
    <Complication type="SHORT_TEXT">
        {text('%s %s<Parameter expression="[COMPLICATION.TITLE]" /><Parameter expression="[COMPLICATION.TEXT]" />')}
    </Complication>
</ComplicationSlot>'''


# The rail under the bar (watch-sports RailComplication, 0-105): ten dashes for "how near the start is" (0-100),
# five for a live Test's day (101-105). Lit/dim and the two modes switch by alpha arithmetic, as before.
RX, RY, RW, RH = BX0 + 44, BY1 + 2, BX1 - BX0 - 44, 6


def rail_slot():
    V = "[COMPLICATION.RANGED_VALUE_VALUE]"
    fuse_on = f"clamp((100.5 - {V}) * 255, 0, 255)"
    test_on = f"clamp(({V} - 100.5) * 255, 0, 255)"

    def dashes(n, gap, lit_expr, mode_on, t):
        w = (RW - gap * (n - 1)) / n
        out = []
        for i in range(n):
            x = i * (w + gap)
            out.append(f'''<PartDraw x="0" y="0" width="{RW}" height="{RH}" alpha="0">
    <Transform target="alpha" value="{mode_on}" />
    <Rectangle x="{x:.1f}" y="1" width="{w:.1f}" height="3"><Fill color="#3cffffff" /></Rectangle>
</PartDraw>
<PartDraw x="0" y="0" width="{RW}" height="{RH}" alpha="0">
    <Transform target="alpha" value="clamp(({lit_expr(i + 1)} + 1) * 255, 0, 255) * clamp((({mode_on}) / 255), 0, 1)" />
    <Rectangle x="{x:.1f}" y="1" width="{w:.1f}" height="3"><Fill color="{argb(t["accent"])}" /></Rectangle>
</PartDraw>''')
        return "\n".join(out)

    def make(t):
        return dashes(10, 4, lambda i: f"{V} - {i * 10}", fuse_on, t) + "\n" + dashes(5, 6, lambda i: f"{V} - {100 + i}", test_on, t)
    return f'''<!-- The rail under the caption bar (watch-sports RailComplication) -->
<ComplicationSlot slotId="5" x="{RX}" y="{RY}" width="{RW}" height="{RH}" supportedTypes="RANGED_VALUE" isCustomizable="TRUE">
    {HIDE}
    <DefaultProviderPolicy defaultSystemProvider="EMPTY"
        primaryProvider="com.balaji.watchsports/com.balaji.watchsports.RailComplication"
        primaryProviderType="RANGED_VALUE" />
    <BoundingBox x="0" y="0" width="{RW}" height="{RH}" />
    <Complication type="RANGED_VALUE">
{ind(by_theme(make, "rl"), 8)}
    </Complication>
</ComplicationSlot>'''


def build(path):
    body = "\n\n".join([wallpaper(), monthly_photo(), scrim(), date(), time(), readings(), caption_plate(), caption_slot(), rail_slot()])
    xml = f'''<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by art/make_face_cf.py (edit that, not this): Clean Feed. -->
<WatchFace width="450" height="450">
    <Metadata key="CLOCK_TYPE" value="DIGITAL" />
    <Metadata key="PREVIEW_TIME" value="10:08:32" />
    <Scene backgroundColor="#ff000000">
{ind(body, 8)}
    </Scene>
</WatchFace>
'''
    open(path, "w").write(xml)
    print("watchface.xml (Clean Feed):", len(xml), "bytes")


if __name__ == "__main__":
    build(sys.argv[1])
