"""Writes the edge-to-edge watch face: 24 daily photo/layout pairings, eight designs per theme, and live
health and sports complications. The daily design index follows the selected wallpaper's group of three.
Usage: make_face.py OUT_XML"""
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from themes import THEMES, argb  # noqa: E402
from date_contrast import choose_ink  # noqa: E402

CATS = ["arsenal", "ferrari", "space"]
WALLS = ["wall_%s_%d" % (CATS[i % 3], i // 3 + 1) for i in range(24)]
HIDE = '<Variant mode="AMBIENT" target="alpha" value="0" />'


def ind(text, n):
    return "\n".join((" " * n + line) if line else line for line in text.split("\n"))


def monthly_photo():
    """This month's photo of the day from the sports app (WallpaperComplication: 24 new photos a month, downloaded
    on the watch), over the built-in one; with nothing downloaded the slot draws nothing and the built-in shows."""
    return f'''<!-- Monthly photos (watch-sports WallpaperComplication), over the built-in photo of the day -->
<ComplicationSlot slotId="6" x="0" y="0" width="450" height="450" supportedTypes="PHOTO_IMAGE" isCustomizable="TRUE">
    {HIDE}
    <DefaultProviderPolicy defaultSystemProvider="EMPTY"
        primaryProvider="com.balaji.watchsports/com.balaji.watchsports.WallpaperComplication"
        primaryProviderType="PHOTO_IMAGE" />
    <BoundingBox x="0" y="0" width="450" height="450" />
    <Complication type="PHOTO_IMAGE">
        <PartImage x="0" y="0" width="450" height="450">
            <Image resource="[COMPLICATION.PHOTO_IMAGE]" />
        </PartImage>
    </Complication>
</ComplicationSlot>'''


def wallpaper():
    exprs = "\n".join('<Expression name="d%d"><![CDATA[[DAY_OF_YEAR] %% 24 == %d]]></Expression>' % (i, i) for i in range(24))
    comps = "\n".join('<Compare expression="d%d"><PartImage x="0" y="0" width="450" height="450"><Image resource="%s" /></PartImage></Compare>'
                      % (i, w) for i, w in enumerate(WALLS))
    return f'''<!-- Today's photo: Arsenal, Ferrari, space, nature in turn, one a day (24 in all) -->
<Group x="0" y="0" width="450" height="450">
    {HIDE}
    <Condition>
        <Expressions>
{ind(exprs, 12)}
        </Expressions>
{ind(comps, 8)}
        <Default><PartImage x="0" y="0" width="450" height="450"><Image resource="bg" /></PartImage></Default>
    </Condition>
</Group>'''


def leds(t, k_theme, pct, ox=0, oy=0):
    """The 11 step lights along the upper outer rim, lit by [pct] (0-100); (ox, oy) is where the enclosing element starts.
    Each is a dim light with the lit one over it, faded in by arithmetic on [pct]."""
    out = []
    for k, col in enumerate(t["leds"]):
        th = math.radians(-32 + 6.4 * k)
        x, y = 225 + 208 * math.sin(th) - ox, 225 - 208 * math.cos(th) - oy
        need = round((k + 1) * 100 / 11)
        out.append(f'''<Group x="{x - 8:.0f}" y="{y - 8:.0f}" width="16" height="16">
    {HIDE}
    <PartDraw x="0" y="0" width="16" height="16"><Ellipse x="2" y="2" width="12" height="12"><Fill color="{argb(col, 64)}" /><Stroke color="#ff111111" thickness="2" /></Ellipse></PartDraw>
    <PartDraw x="0" y="0" width="16" height="16" alpha="0">
        <Transform target="alpha" value="clamp(({pct} - {need} + 1) * 255, 0, 255)" />
        <Ellipse x="1" y="1" width="14" height="14"><Fill color="{argb(col)}" /><Stroke color="#ffffffff" thickness="1" /></Ellipse>
    </PartDraw>
</Group>''')
    return "\n".join(out)


def dial(cx, color, value_expr, text_xml=None, ox=0, oy=0, track=True, font_size=28):
    """A 90 px goal dial following OnePlus Health (360° circular ring from 12 o'clock / -90°)."""
    plate = f'''<Ellipse x="0.5" y="0.5" width="89" height="89"><Stroke color="{argb(color, 65)}" thickness="1.5" /></Ellipse>
    <Ellipse x="4" y="4" width="82" height="82"><Fill color="#e506080e" /><Stroke color="{argb(color, 130)}" thickness="1.5" /></Ellipse>''' if track else ""
    ring = f'''<Arc centerX="45" centerY="45" width="76" height="76" startAngle="-90" endAngle="-90">
        <Transform target="endAngle" value="-90 + {value_expr} * 3.6" />
        <Stroke color="{argb(color)}" thickness="7" cap="ROUND" />
    </Arc>''' if value_expr else ""
    tr = '<Arc centerX="45" centerY="45" width="76" height="76" startAngle="0" endAngle="360"><Stroke color="#35ffffff" thickness="6" cap="ROUND" /></Arc>' if track else ""
    out = f'''<PartDraw x="{cx - 45 - ox}" y="{260 - oy}" width="90" height="90">
    {HIDE}
    {plate}
    {tr}
    {ring}
</PartDraw>'''
    if text_xml:
        out += f'''
<PartText x="{cx - 42 - ox}" y="{291 - oy}" width="86" height="32">
    {HIDE}
    <Text align="CENTER"><Font family="outfit_bold" size="{font_size}" color="#cc000000"><Template>{text_xml}</Template></Font></Text>
</PartText>
<PartText x="{cx - 43 - ox}" y="{290 - oy}" width="86" height="32">
    {HIDE}
    <Text align="CENTER"><Font family="outfit_bold" size="{font_size}" color="{argb(color)}"><Template>{text_xml}</Template></Font></Text>
</PartText>'''
    return out


def clock_piece(fmt, x, y, width, height, size, color, align="CENTER", shadow=False):
    hour = ' hourFormat="SYNC_TO_DEVICE"' if fmt == "hh" else ""
    hide = HIDE if shadow else ""
    return f'''<DigitalClock x="{x}" y="{y}" width="{width}" height="{height}">
    {hide}
    <TimeText x="0" y="0" width="{width}" height="{height}" format="{fmt}"{hour} align="{align}">
        <Font family="outfit_bold" size="{size}" color="{color}" />
    </TimeText>
</DigitalClock>'''


def colon_piece(x, y, width, height, size, color, shadow=False):
    hide = HIDE if shadow else ""
    return f'''<PartText x="{x}" y="{y}" width="{width}" height="{height}">
    {hide}<Text align="CENTER"><Font family="outfit_bold" size="{size}" color="{color}"><Template>:</Template></Font></Text>
</PartText>'''


def raised_text(x, y, width, height, size, family, template, color, depth_color):
    """Offset text layers create a raised shadow without a panel or outline."""
    layers = []
    for dx, dy in ((3, 3), (2, 2), (1, 1)):
        layers.append(f'''<PartText x="{x + dx}" y="{y + dy}" width="{width}" height="{height}">
    <Text align="CENTER"><Font family="{family}" size="{size}" color="{depth_color}"><Template>{template}</Template></Font></Text>
</PartText>''')
    layers.append(f'''<PartText x="{x}" y="{y}" width="{width}" height="{height}">
    <Text align="CENTER"><Font family="{family}" size="{size}" color="{color}"><Template>{template}</Template></Font></Text>
</PartText>''')
    return "\n".join(layers)


def clock_layout(t, style):
    """Eight distinct time treatments; all preserve the same bottom complication positions."""
    white, accent, shadow = argb(t["hours"]), argb(t["minutes"]), "#ee000000"
    if style == 2:  # vertical race number stack
        return '\n'.join((
            clock_piece("hh", 132, 105, 190, 54, 55, shadow, shadow=True),
            colon_piece(209, 147, 36, 24, 22, shadow, shadow=True),
            clock_piece("mm", 132, 162, 190, 54, 55, shadow, shadow=True),
            clock_piece("hh", 130, 103, 190, 54, 55, white),
            colon_piece(207, 145, 36, 24, 22, argb(t["colon"])),
            clock_piece("mm", 130, 160, 190, 54, 55, accent)))
    if style == 4:  # minute-led chrono
        return '\n'.join((clock_piece("hh", 26, 123, 145, 86, 76, shadow, "END", True),
            colon_piece(174, 125, 45, 82, 66, shadow, True),
            clock_piece("mm", 222, 107, 200, 110, 108, shadow, "START", True),
            clock_piece("hh", 24, 121, 145, 86, 76, white, "END"),
            colon_piece(174, 123, 45, 82, 66, argb(t["colon"])),
            clock_piece("mm", 220, 105, 200, 110, 108, accent, "START")))
    if style == 1:  # twin registers with raised drop shadow (no box)
        return '\n'.join((
            clock_piece("hh", 57, 121, 150, 94, 84, shadow, shadow=True),
            colon_piece(207, 121, 40, 94, 72, shadow, shadow=True),
            clock_piece("mm", 247, 121, 150, 94, 84, shadow, shadow=True),
            clock_piece("hh", 55, 119, 150, 94, 84, white),
            colon_piece(205, 119, 40, 94, 72, argb(t["colon"])),
            clock_piece("mm", 245, 119, 150, 94, 84, accent)))
    if style == 3:  # split-screen with a calibrated center rail
        rail = f'''<PartDraw x="223" y="116" width="4" height="96"><RoundRectangle x="1" y="0" width="2" height="96" cornerRadius="1"><Fill color="{argb(t["major"],150)}"/></RoundRectangle><Ellipse x="0" y="44" width="4" height="8"><Fill color="{argb(t["colon"])}"/></Ellipse></PartDraw>'''
        return '\n'.join((clock_piece("hh", 28, 114, 180, 108, 94, shadow, "END", True),
            clock_piece("hh", 26, 112, 180, 108, 94, white, "END"), rail,
            clock_piece("mm", 242, 114, 180, 108, 94, shadow, "START", True),
            clock_piece("mm", 240, 112, 180, 108, 94, accent, "START")))
    if style == 5:  # clean, compact central signature
        return '\n'.join((clock_piece("hh", 45, 120, 170, 92, 88, shadow, "END", True),
            colon_piece(207, 121, 38, 90, 74, shadow, True),
            clock_piece("mm", 235, 120, 170, 92, 88, shadow, "START", True),
            clock_piece("hh", 43, 118, 170, 92, 88, white, "END"),
            colon_piece(205, 119, 38, 90, 74, argb(t["colon"])),
            clock_piece("mm", 233, 118, 170, 92, 88, accent, "START")))
    if style == 6:  # illuminated speed strip (no box)
        return '\n'.join((
            clock_piece("hh", 35, 111, 178, 112, 92, shadow, "END", True),
            colon_piece(205, 112, 40, 106, 78, shadow, True),
            clock_piece("mm", 237, 111, 178, 112, 92, shadow, "START", True),
            clock_piece("hh", 33, 109, 178, 112, 92, white, "END"),
            colon_piece(203, 110, 40, 106, 78, argb(t["colon"])),
            clock_piece("mm", 235, 109, 178, 112, 92, accent, "START")))
    if style == 7:  # asymmetric tachometer readout
        return '\n'.join((clock_piece("hh", 25, 130, 132, 80, 72, shadow, "END", True),
            colon_piece(155, 127, 48, 84, 70, shadow, True), clock_piece("mm", 207, 107, 216, 108, 105, shadow, "START", True),
            clock_piece("hh", 23, 128, 132, 80, 72, white, "END"), colon_piece(153, 125, 48, 84, 70, argb(t["colon"])),
            clock_piece("mm", 205, 105, 216, 108, 105, accent, "START")))
    # style 0: open, full-width chronograph hero
    return '\n'.join((clock_piece("hh", 27, 114, 188, 114, 106, shadow, "END", True),
        clock_piece("mm", 239, 114, 188, 114, 106, shadow, "START", True),
        colon_piece(190, 114, 74, 106, 98, shadow, True),
        clock_piece("hh", 25, 112, 188, 114, 106, white, "END"),
        clock_piece("mm", 237, 112, 188, 114, 106, accent, "START"),
        colon_piece(188, 112, 74, 106, 98, argb(t["colon"]))))


def theme(k, t, style):
    _, day_color, _ = choose_ink(t["name"], style, "SATURDAY", 48, 24)
    # Keep the whole two-line label in one ink so the month never drops to red alone.
    date_color = day_color
    depth_color = argb(t["left"])
    accent_line = f'''<PartDraw x="25" y="105" width="400" height="127">
        {HIDE}
        <RoundRectangle x="0" y="0" width="{35 + style * 4}" height="2" cornerRadius="1"><Fill color="{argb(t["major"], 190)}" /></RoundRectangle>
        <RoundRectangle x="{365 - style * 3}" y="125" width="{35 + style * 3}" height="2" cornerRadius="1"><Fill color="{argb(t["left"], 210)}" /></RoundRectangle>
        <RoundRectangle x="0" y="0" width="1" height="{14 + style * 2}" cornerRadius="1"><Fill color="{argb(t["major"], 145)}" /></RoundRectangle>
        <RoundRectangle x="399" y="{111 - style}" width="1" height="{14 + style}" cornerRadius="1"><Fill color="{argb(t["left"], 145)}" /></RoundRectangle>
    </PartDraw>'''
    date = f'''<PartDraw x="82" y="39" width="286" height="63">
        {HIDE}
        <RoundRectangle x="0" y="6" width="20" height="2" cornerRadius="1"><Fill color="{argb(t["left"])}" /></RoundRectangle>
        <RoundRectangle x="266" y="6" width="20" height="2" cornerRadius="1"><Fill color="{argb(t["right"])}" /></RoundRectangle>
        <RoundRectangle x="74" y="60" width="138" height="2" cornerRadius="1"><Fill color="{argb(t["major"],170)}" /></RoundRectangle>
    </PartDraw>
    {raised_text(96, 34, 258, 28, 24, "outfit_bold", '%s<Parameter expression="[DAY_OF_WEEK_F]" />', day_color, depth_color)}
    {raised_text(76, 61, 298, 36, 30, "outfit_bold", '%s %s<Parameter expression="[DAY]" /><Parameter expression="[MONTH_F]" />', date_color, depth_color)}'''
    # Telemetry capsule: Battery on left, Heart rate on right
    telemetry_bar = f'''<!-- Telemetry capsule: Battery on left, Heart rate on right -->
    <PartDraw x="113" y="224" width="224" height="26">
        {HIDE}
        <RoundRectangle x="0" y="0" width="224" height="26" cornerRadius="13">
            <Fill color="#f006080e" />
            <Stroke color="#88ffffff" thickness="1" />
        </RoundRectangle>
        <RoundRectangle x="111" y="5" width="1" height="16" cornerRadius="0.5"><Fill color="#55ffffff" /></RoundRectangle>
        <!-- Battery micro icon -->
        <RoundRectangle x="20" y="8" width="15" height="10" cornerRadius="2"><Stroke color="#ffffffff" thickness="1" /></RoundRectangle>
        <RoundRectangle x="35" y="11" width="2" height="4" cornerRadius="1"><Fill color="#ffffffff" /></RoundRectangle>
        <RoundRectangle x="22" y="10" width="11" height="6" cornerRadius="1">
            <Transform target="width" value="clamp([BATTERY_PERCENT] * 0.11, 1, 11)" />
            <Fill color="{argb(t['battery'])}" />
        </RoundRectangle>
        <!-- Outer tick wings -->
        <RoundRectangle x="-18" y="12" width="12" height="2" cornerRadius="1"><Fill color="{argb(t['left'])}" /></RoundRectangle>
        <RoundRectangle x="230" y="12" width="12" height="2" cornerRadius="1"><Fill color="{argb(t['right'])}" /></RoundRectangle>
    </PartDraw>
    <!-- Battery percentage text -->
    <PartText x="154" y="226" width="56" height="22">
        {HIDE}
        <Text align="START"><Font family="outfit_bold" size="15" color="#ffffffff"><Template>%s%%<Parameter expression="[BATTERY_PERCENT]" /></Template></Font></Text>
    </PartText>
    <!-- Heart icon next to heart rate -->
    <PartText x="237" y="226" width="16" height="22">
        {HIDE}
        <Text align="CENTER"><Font family="outfit_bold" size="14" color="{argb((219, 0, 7) if t['name'] != 'space' else (255, 75, 175))}"><Template>♥</Template></Font></Text>
    </PartText>'''
    return f'''<!-- {t["name"].title()} design {style + 1} -->
<Group x="0" y="0" width="450" height="450">
    <PartImage x="0" y="0" width="450" height="450">{HIDE}<Image resource="overlay_{t["name"]}_{style}" /></PartImage>
    {accent_line}
    {date}
    {telemetry_bar}
    {clock_layout(t, style)}
    {ind(dial(120, t["battery"], ""), 4)}
    {ind(dial(225, t["steps"], ""), 4)}
    {ind(dial(330, t["kcal"], ""), 4)}
</Group>'''


def light(rgb, k=0.45):
    """[rgb] mixed towards white: the day's colour, light enough to read on the smoky band."""
    return tuple(round(v + (255 - v) * k) for v in rgb)


# (this watch has no max(): with it the whole expression failed and the lights stayed dark)
PCT = "clamp([COMPLICATION.RANGED_VALUE_VALUE] / [COMPLICATION.RANGED_VALUE_MAX] * 100, 0, 100)"
STEPS_BOX = (90, 8, 270, 342)  # the steps slot: the lights round the top and the middle dial


def by_theme(make, tag):
    """[make](k, t) for today's theme, chosen inside a widget slot ([DAY_OF_YEAR] % 3, as everywhere else)."""
    exprs = "\n".join('<Expression name="%s%d"><![CDATA[[DAY_OF_YEAR] %% 3 == %d]]></Expression>' % (tag, k, k) for k in range(3))
    comps = "\n".join(f'<Compare expression="{tag}{k}">\n{ind(make(k, t), 4)}\n</Compare>' for k, t in enumerate(THEMES))
    return f'''<Condition>
    <Expressions>
{ind(exprs, 8)}
    </Expressions>
{ind(comps, 4)}
    <Default>
{ind(make(8, THEMES[1]), 8)}
    </Default>
</Condition>'''


def sports_plate():
    """The sports pill: a solid plate whose border takes the day's accent colour."""
    def make(k, t):
        return f'''<PartDraw x="0" y="0" width="340" height="32">
    <RoundRectangle x="0" y="0" width="340" height="32" cornerRadius="16">
        <Fill color="#f8000000" />
        <Stroke color="{argb(t["major"], 0xaa)}" thickness="1.5" />
    </RoundRectangle>
</PartDraw>'''
    return by_theme(make, "sp")


def sports_icon():
    """The widget's sport icon (cricket ball, football, chequered flag), tinted the day's accent. At x 93-113 on
    the screen: the pill runs past the round edge there (visible from x ~78 at its middle, ~90 at its foot)."""
    def make(k, t):
        return f'''<PartImage x="37" y="5" width="22" height="22" tintColor="{argb(t["major"])}">
    <Image resource="[COMPLICATION.MONOCHROMATIC_IMAGE]" />
</PartImage>'''
    return by_theme(make, "si")


RAIL_X, RAIL_Y, RAIL_W, RAIL_H = 120, 378, 210, 10   # on the screen: just inside the top of the sports pill


def rail_slot():
    """A thin rail of lit dashes along the top of the sports pill, from the sports app's RailComplication
    (RANGED_VALUE, never online): 0-100 = how near the next start is over its last day (ten dashes, like the
    step lights), 101-105 = a live Test, 100 + its day (five long dashes). Nothing is drawn when it has no data.
    Each dash is a dim one with a lit one over it, faded in by arithmetic on the value (a Condition can't read
    a widget on this watch); the two modes fade each other out the same way."""
    V = "[COMPLICATION.RANGED_VALUE_VALUE]"
    fuse_on = f"clamp((100.5 - {V}) * 255, 0, 255)"
    test_on = f"clamp(({V} - 100.5) * 255, 0, 255)"

    def dashes(n, gap, lit_expr, mode_on, t):
        w = (RAIL_W - gap * (n - 1)) / n
        out = []
        for i in range(n):
            x = i * (w + gap)
            out.append(f'''<PartDraw x="0" y="0" width="{RAIL_W}" height="{RAIL_H}" alpha="0">
    <Transform target="alpha" value="{mode_on}" />
    <RoundRectangle x="{x:.1f}" y="4.5" width="{w:.1f}" height="3" cornerRadius="1.5"><Fill color="#40ffffff" /></RoundRectangle>
</PartDraw>
<PartDraw x="0" y="0" width="{RAIL_W}" height="{RAIL_H}" alpha="0">
    <Transform target="alpha" value="clamp(({lit_expr(i + 1)} + 1) * 255, 0, 255) * clamp((({mode_on}) / 255), 0, 1)" />
    <RoundRectangle x="{x:.1f}" y="4" width="{w:.1f}" height="4" cornerRadius="2"><Fill color="{argb(t["major"])}" /></RoundRectangle>
</PartDraw>''')
        return "\n".join(out)

    def make(k, t):
        return (dashes(10, 4, lambda i: f"{V} - {i * 10}", fuse_on, t) + "\n"
                + dashes(5, 6, lambda i: f"{V} - {100 + i}", test_on, t))
    return f'''<!-- Sports rail (watch-sports RailComplication): how near the next start is, or a live Test's day -->
<ComplicationSlot slotId="5" x="{RAIL_X}" y="{RAIL_Y}" width="{RAIL_W}" height="{RAIL_H}" supportedTypes="RANGED_VALUE" isCustomizable="TRUE">
    <Variant mode="AMBIENT" target="alpha" value="0" />
    <DefaultProviderPolicy defaultSystemProvider="EMPTY"
        primaryProvider="com.balaji.watchsports/com.balaji.watchsports.RailComplication"
        primaryProviderType="RANGED_VALUE" />
    <BoundingBox x="0" y="0" width="{RAIL_W}" height="{RAIL_H}" />
    <Complication type="RANGED_VALUE">
{ind(by_theme(make, "rl"), 8)}
    </Complication>
</ComplicationSlot>'''


def fpl_slot():
    ox, oy = 120 - 45, 260
    def make(k, t):
        return dial(120, t["battery"], PCT,
            '%s<Parameter expression="[COMPLICATION.TEXT]" />', ox, oy, track=False, font_size=20)
    return f'''<!-- FPL Deadline (watch-sports): the left-hand dial shows countdown to next Gameweek deadline -->
<ComplicationSlot slotId="4" x="{ox}" y="{oy}" width="90" height="90" supportedTypes="RANGED_VALUE SHORT_TEXT" isCustomizable="TRUE">
    <Variant mode="AMBIENT" target="alpha" value="0" />
    <DefaultProviderPolicy defaultSystemProvider="EMPTY"
        primaryProvider="com.balaji.watchsports/com.balaji.watchsports.FplDeadlineComplication"
        primaryProviderType="RANGED_VALUE" />
    <BoundingBox x="0" y="0" width="90" height="90" />
    <Complication type="RANGED_VALUE">
{ind(by_theme(make, "fk"), 8)}
    </Complication>
    <Complication type="SHORT_TEXT">
        <PartText x="3" y="31" width="86" height="32">
            <Text align="CENTER"><Font family="outfit_bold" size="20" color="#cc000000"><Template>%s<Parameter expression="[COMPLICATION.TEXT]" /></Template></Font></Text>
        </PartText>
        <PartText x="2" y="30" width="86" height="32">
            <Text align="CENTER"><Font family="outfit_bold" size="20" color="#ffffffff"><Template>%s<Parameter expression="[COMPLICATION.TEXT]" /></Template></Font></Text>
        </PartText>
    </Complication>
</ComplicationSlot>'''


def steps_slot():
    ox, oy, w, h = STEPS_BOX
    def make(k, t):
        return leds(t, "s%d" % k, PCT, ox, oy) + "\n" + dial(225, t["steps"], PCT,
            '%s<Parameter expression="floor([COMPLICATION.RANGED_VALUE_VALUE])" />', ox, oy, track=False)
    return f'''<!-- Steps (OnePlus Health): the 11 lights round the top and the middle dial -->
<ComplicationSlot slotId="3" x="{ox}" y="{oy}" width="{w}" height="{h}" supportedTypes="RANGED_VALUE" isCustomizable="FALSE">
    <Variant mode="AMBIENT" target="alpha" value="0" />
    <DefaultProviderPolicy defaultSystemProvider="EMPTY"
        primaryProvider="com.heytap.wearable.health/com.heytap.wearable.health.complication.wearos.StepComplicationService"
        primaryProviderType="RANGED_VALUE" />
    <BoundingBox x="0" y="0" width="{w}" height="{h}" />
    <Complication type="RANGED_VALUE">
{ind(by_theme(make, "sk"), 8)}
    </Complication>
</ComplicationSlot>'''


def kcal_slot():
    ox, oy = 330 - 45, 260
    def make(k, t):
        return dial(330, t["kcal"], PCT, '%s<Parameter expression="floor([COMPLICATION.RANGED_VALUE_VALUE])" />', ox, oy, track=False)
    return f'''<!-- Calories (OnePlus Health): the right-hand dial fills towards its calorie goal -->
<ComplicationSlot slotId="0" x="{ox}" y="{oy}" width="90" height="90" supportedTypes="RANGED_VALUE SHORT_TEXT" isCustomizable="TRUE">
    <Variant mode="AMBIENT" target="alpha" value="0" />
    <DefaultProviderPolicy defaultSystemProvider="EMPTY"
        primaryProvider="com.heytap.wearable.health/com.heytap.wearable.health.complication.wearos.CaloriesComplicationService"
        primaryProviderType="RANGED_VALUE" />
    <BoundingBox x="0" y="0" width="90" height="90" />
    <Complication type="RANGED_VALUE">
{ind(by_theme(make, "kk"), 8)}
    </Complication>
    <Complication type="SHORT_TEXT">
        <PartText x="2" y="30" width="86" height="32">
            <Text align="CENTER"><Font family="outfit_bold" size="28" color="#ffffffff"><Template>%s<Parameter expression="[COMPLICATION.TEXT]" /></Template></Font></Text>
        </PartText>
    </Complication>
</ComplicationSlot>'''


def theme_variants(k, t):
    formula = "floor(([DAY_OF_YEAR] % 24) / 3)"
    exprs = "\n".join('<Expression name="dv%d%d"><![CDATA[%s == %d]]></Expression>' % (k, i, formula, i) for i in range(8))
    comps = "\n".join(f'<Compare expression="dv{k}{i}">\n{ind(theme(k, t, i), 4)}\n</Compare>' for i in range(8))
    return f'''<Condition>
    <Expressions>
{ind(exprs, 8)}
    </Expressions>
{ind(comps, 4)}
    <Default>
{ind(theme(k, t, 0), 8)}
    </Default>
</Condition>'''


def themes():
    exprs = "\n".join('<Expression name="th%d"><![CDATA[[DAY_OF_YEAR] %% 3 == %d]]></Expression>' % (k, k) for k in range(3))
    comps = "\n".join(f'<Compare expression="th{k}">\n{ind(theme_variants(k, t), 4)}\n</Compare>' for k, t in enumerate(THEMES))
    return f'''<!-- Today's theme, matching the photo's category -->
<Condition>
    <Expressions>
{ind(exprs, 8)}
    </Expressions>
{ind(comps, 4)}
    <Default>
{ind(theme_variants(1, THEMES[1]), 8)}
    </Default>
</Condition>'''


def labels():
    out = []
    for cx, name in ((120, "FPL"), (225, "STEPS"), (330, "KCAL")):
        out.append(f'''<Group x="{cx - 37}" y="354" width="74" height="18">
    {HIDE}
    <PartDraw x="0" y="0" width="74" height="18">
        <RoundRectangle x="18" y="0" width="38" height="2" cornerRadius="1"><Fill color="#bbffffff" /></RoundRectangle>
    </PartDraw>
    <PartText x="1" y="1" width="74" height="18">
        <Text align="CENTER"><Font family="outfit_bold" size="14" color="#cc000000"><Template>{name}</Template></Font></Text>
    </PartText>
    <PartText x="0" y="0" width="74" height="18">
        <Text align="CENTER"><Font family="outfit_bold" size="14" color="#ffffffff"><Template>{name}</Template></Font></Text>
    </PartText>
</Group>''')
    return "\n".join(out)


SLOTS = '''<!-- Heart rate (OnePlus Health) inside right half of telemetry capsule -->
<ComplicationSlot slotId="1" x="256" y="224" width="76" height="26" supportedTypes="SHORT_TEXT" isCustomizable="TRUE">
    <Variant mode="AMBIENT" target="alpha" value="0" />
    <DefaultProviderPolicy defaultSystemProvider="EMPTY"
        primaryProvider="com.heytap.wearable.health/com.heytap.wearable.health.complication.wearos.HeartRateComplicationService"
        primaryProviderType="SHORT_TEXT" />
    <BoundingBox x="0" y="0" width="76" height="26" />
    <Complication type="SHORT_TEXT">
        <PartText x="1" y="1" width="76" height="26">
            <Text align="START"><Font family="outfit_bold" size="15" color="#cc000000"><Template>%s BPM<Parameter expression="[COMPLICATION.TEXT]" /></Template></Font></Text>
        </PartText>
        <PartText x="0" y="0" width="76" height="26">
            <Text align="START"><Font family="outfit_bold" size="15" color="#ffffffff"><Template>%s BPM<Parameter expression="[COMPLICATION.TEXT]" /></Template></Font></Text>
        </PartText>
    </Complication>
</ComplicationSlot>

{FPL}

{KCAL}

{STEPS}

<!-- Sports pill backing plate: solid, its border in the day's accent (drawn once; no cost) -->
<Group x="55" y="379" width="340" height="32">
    <Variant mode="AMBIENT" target="alpha" value="0" />
{SPORTS_PLATE}
</Group>

<!-- Sports widget, expanded across the bottom with raised bold text -->
<ComplicationSlot slotId="2" x="55" y="379" width="340" height="32" supportedTypes="LONG_TEXT SHORT_TEXT" isCustomizable="TRUE">
    <Variant mode="AMBIENT" target="alpha" value="0" />
    <DefaultProviderPolicy defaultSystemProvider="EMPTY"
        primaryProvider="com.balaji.watchsports/com.balaji.watchsports.NextUpComplication"
        primaryProviderType="LONG_TEXT"
        secondaryProvider="com.google.android.calendar/com.google.android.apps.calendar.wear.complication.NextEventComplicationService"
        secondaryProviderType="SHORT_TEXT" />
    <BoundingBox x="0" y="0" width="340" height="32" />
    <Complication type="LONG_TEXT">
{SPORTS_ICON}
        <PartText x="64" y="1" width="248" height="32">
            <Text align="CENTER" ellipsis="TRUE"><Font family="outfit_bold" size="17" color="#cc000000"><Template>%s<Parameter expression="[COMPLICATION.TEXT]" /></Template></Font></Text>
        </PartText>
        <PartText x="63" y="0" width="248" height="32">
            <Text align="CENTER" ellipsis="TRUE"><Font family="outfit_bold" size="17" color="#ffffffff"><Template>%s<Parameter expression="[COMPLICATION.TEXT]" /></Template></Font></Text>
        </PartText>
    </Complication>
    <Complication type="SHORT_TEXT">
        <PartText x="1" y="1" width="340" height="32">
            <Text align="CENTER" ellipsis="TRUE"><Font family="outfit_bold" size="18" color="#cc000000"><Template>%s %s<Parameter expression="[COMPLICATION.TITLE]" /><Parameter expression="[COMPLICATION.TEXT]" /></Template></Font></Text>
        </PartText>
        <PartText x="0" y="0" width="340" height="32">
            <Text align="CENTER" ellipsis="TRUE"><Font family="outfit_bold" size="18" color="#ffffffff"><Template>%s %s<Parameter expression="[COMPLICATION.TITLE]" /><Parameter expression="[COMPLICATION.TEXT]" /></Template></Font></Text>
        </PartText>
    </Complication>
</ComplicationSlot>'''

def build():
    xml = f'''<?xml version="1.0" encoding="utf-8"?>
    <!-- Generated by art/make_face.py (edit that, not this). Twenty-four daily designs: eight each for Arsenal,
         Ferrari, and space, paired with their photos. No seconds are shown; ambient keeps only time and date. -->
    <WatchFace width="450" height="450">
        <Metadata key="CLOCK_TYPE" value="DIGITAL" />
        <Metadata key="PREVIEW_TIME" value="10:08:32" />
        <Scene backgroundColor="#ff000000">
    {ind(wallpaper(), 8)}

    {ind(monthly_photo(), 8)}

    {ind(themes(), 8)}

    {ind(labels(), 8)}

    {ind(SLOTS.replace("{SPORTS_PLATE}", ind(sports_plate(), 4)).replace("{SPORTS_ICON}", ind(sports_icon(), 8)).replace("{FPL}", fpl_slot()).replace("{KCAL}", kcal_slot()).replace("{STEPS}", steps_slot()), 8)}

    {ind(rail_slot(), 8)}
        </Scene>
    </WatchFace>
    '''
    open(sys.argv[1], "w").write(xml)
    print("watchface.xml:", len(xml), "bytes")


if __name__ == "__main__":
    build()
