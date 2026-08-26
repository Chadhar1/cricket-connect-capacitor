#!/usr/bin/env python3
"""
Generates every CricketConnect brand asset for the CAPACITOR app from the one
official source logo.

    python3 scripts/generate_brand_assets.py        (needs: pip install pillow)

WHY THIS EXISTS
---------------
The assets it writes are real files committed into the project, so you do NOT
need to run this to build the app. It exists so the assets are *reproducible*:
if the logo is ever revised, drop the new file in as
native-src/assets/brand/source/cricketconnect-logo-source.png, re-run, and
every icon/splash/mark regenerates consistently instead of being hand-edited.

WHAT IT TOUCHES  (all inside cricket-connect-capacitor/ — nothing else)
-----------------------------------------------------------------------
  native-src/assets/brand/*.png          web-layer marks + lockup
  android/app/src/main/res/mipmap-*/     launcher icons (overwrites Capacitor defaults)
  android/app/src/main/res/drawable*/    splash screens (overwrites Capacitor defaults)

It never reads or writes legacy-app/ or cricket-connect-android/ (the TWA).

SOURCE GEOMETRY
---------------
ICON_BOX below is the pixel box of the rounded-square app-icon artwork inside
the source lockup image. It was found by detecting the icon's bright blue
border (dense-blue rows 31-571, cols 70-700), then tightened by eye to
115,45,650,555 to drop the stadium bleed at the corners. If the source logo is
ever replaced at a different size/crop, re-check this box first — everything
else is derived from it.
"""

import os
import sys

try:
    from PIL import Image, ImageDraw, ImageFilter
except ImportError:
    sys.exit("Pillow is required:  pip install pillow")

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
BRAND = os.path.join(ROOT, "native-src", "assets", "brand")
SOURCE = os.path.join(BRAND, "source", "cricketconnect-logo-source.png")
RES = os.path.join(ROOT, "android", "app", "src", "main", "res")

# Pixel box of the app-icon artwork within the source image — see docstring.
ICON_BOX = (115, 45, 650, 555)
# Pixel box of the wordmark block ("CRICKET / CONNECT / PLAY-SHARE-GROW / tagline").
LOCKUP_BOX = (30, 600, 725, 890)

# Brand colours sampled from the source logo itself (not invented) —
# see THEME.md for the full derivation table.
NAVY_DEEP = (2, 12, 27)      # #020C1B  stadium sky
NAVY = (4, 18, 33)           # #041221  dominant field
BLUE = (46, 107, 255)        # #2E6BFF  electric blue
CYAN = (89, 195, 248)        # #59C3F8  icon border glow
LIME = (164, 248, 81)        # #A4F851  CONNECT wordmark green


def need_source():
    if not os.path.exists(SOURCE):
        sys.exit(f"Source logo not found: {SOURCE}")
    return Image.open(SOURCE).convert("RGBA")


def rounded_mask(size, radius_frac=0.225):
    """Square alpha mask with rounded corners, matching the logo's own icon shape."""
    m = Image.new("L", (size, size), 0)
    ImageDraw.Draw(m).rounded_rectangle(
        (0, 0, size - 1, size - 1), radius=int(size * radius_frac), fill=255
    )
    return m


def circle_mask(size):
    m = Image.new("L", (size, size), 0)
    ImageDraw.Draw(m).ellipse((0, 0, size - 1, size - 1), fill=255)
    return m


def icon_art(src, size):
    """The app-icon artwork, cropped square and resized. Corners left as-is."""
    art = src.crop(ICON_BOX)
    # ICON_BOX is 535x510 — pad the short axis so nothing is squashed.
    w, h = art.size
    s = max(w, h)
    sq = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    sq.paste(art, ((s - w) // 2, (s - h) // 2))
    return sq.resize((size, size), Image.LANCZOS)


def radial_glow(size, colour, strength=0.55):
    """Soft circular glow used behind the logo on splash screens."""
    w, h = size
    g = Image.new("L", (w, h), 0)
    d = ImageDraw.Draw(g)
    r = int(min(w, h) * 0.42)
    d.ellipse((w // 2 - r, h // 2 - r, w // 2 + r, h // 2 + r), fill=int(255 * strength))
    g = g.filter(ImageFilter.GaussianBlur(radius=min(w, h) * 0.16))
    layer = Image.new("RGBA", (w, h), colour + (0,))
    layer.putalpha(g)
    return layer


# --------------------------------------------------------------------------
# 1. Web-layer brand marks (used by native-src/brand-native.js in the WebView)
# --------------------------------------------------------------------------
def build_web_marks(src):
    os.makedirs(BRAND, exist_ok=True)
    for px in (512, 192, 96, 64):
        art = icon_art(src, px)
        art.putalpha(rounded_mask(px))
        art.save(os.path.join(BRAND, f"cc-mark-{px}.png"))
        print(f"  native-src/assets/brand/cc-mark-{px}.png")

    # Full lockup (icon + wordmark) on a TRANSPARENT background, for splash
    # and login use.
    #
    # The wordmark can't just be cropped out of the source: it sits on the
    # source's own dark stadium photo, so a plain crop composites onto the
    # splash as an obvious dark rectangle around the text. Instead the
    # background is keyed out by luminance — the wordmark is bright white and
    # bright lime on near-black navy, so luminance separates text from
    # backdrop almost perfectly, and the result drops onto any background
    # cleanly.
    icon = icon_art(src, 620)
    icon.putalpha(rounded_mask(620))

    word = src.crop(LOCKUP_BOX).convert("RGBA")
    lw, lh = word.size
    word = word.resize((620, int(lh * (620 / lw))), Image.LANCZOS)
    lum = word.convert("L")
    # Anything below FLOOR is treated as pure background, anything above
    # CEIL as fully opaque glyph; between the two it ramps, which keeps the
    # anti-aliased edges of the type soft instead of jagged.
    FLOOR, CEIL = 42, 130
    word.putalpha(
        lum.point(lambda v: 0 if v <= FLOOR else (255 if v >= CEIL else int((v - FLOOR) * 255 / (CEIL - FLOOR))))
    )

    gap = 30
    total_h = 620 + gap + word.size[1]
    lock = Image.new("RGBA", (620, total_h), (0, 0, 0, 0))
    lock.alpha_composite(icon, (0, 0))
    lock.alpha_composite(word, (0, 620 + gap))
    lock.save(os.path.join(BRAND, "cc-lockup.png"))
    print("  native-src/assets/brand/cc-lockup.png")


# --------------------------------------------------------------------------
# 2. Android launcher icons
# --------------------------------------------------------------------------
LAUNCHER = {  # dir: (legacy ic_launcher px, adaptive foreground px)
    "mipmap-mdpi": (48, 108),
    "mipmap-hdpi": (72, 162),
    "mipmap-xhdpi": (96, 216),
    "mipmap-xxhdpi": (144, 324),
    "mipmap-xxxhdpi": (192, 432),
}


def build_launcher(src):
    for d, (legacy_px, fg_px) in LAUNCHER.items():
        out = os.path.join(RES, d)
        os.makedirs(out, exist_ok=True)

        # Legacy square icon — full-bleed art with the logo's own rounded corners.
        sq = icon_art(src, legacy_px)
        sq.putalpha(rounded_mask(legacy_px))
        sq.save(os.path.join(out, "ic_launcher.png"))

        # Legacy round icon — same art, circular mask.
        rd = icon_art(src, legacy_px)
        rd.putalpha(circle_mask(legacy_px))
        rd.save(os.path.join(out, "ic_launcher_round.png"))

        # Adaptive foreground. Android crops adaptive icons hard: only the
        # centre 66% of the 108dp canvas (72dp of 108dp) is guaranteed visible
        # on every launcher shape. 0.72 deliberately overshoots that a little:
        # the art is a rounded SQUARE, so under a circular mask it is only the
        # four corners — empty navy — that get clipped, while the C, the
        # batsman and the ball all sit well inside the safe circle. Rendered
        # at exactly 0.62 the icon reads as a small stamp floating in padding.
        fg = Image.new("RGBA", (fg_px, fg_px), (0, 0, 0, 0))
        inner = int(fg_px * 0.72)
        art = icon_art(src, inner)
        art.putalpha(rounded_mask(inner))
        off = (fg_px - inner) // 2
        fg.paste(art, (off, off), art)
        fg.save(os.path.join(out, "ic_launcher_foreground.png"))
        print(f"  {d}/ic_launcher.png + _round + _foreground")

    # Adaptive background: brand navy, not Capacitor's default white.
    bg_xml = os.path.join(RES, "values", "ic_launcher_background.xml")
    with open(bg_xml, "w", encoding="utf-8") as f:
        f.write(
            '<?xml version="1.0" encoding="utf-8"?>\n'
            "<resources>\n"
            '    <color name="ic_launcher_background">#041221</color>\n'
            "</resources>\n"
        )
    print("  values/ic_launcher_background.xml -> #041221")


# --------------------------------------------------------------------------
# 3. Splash screens
# --------------------------------------------------------------------------
SPLASH = {
    "drawable": (480, 320),
    "drawable-port-mdpi": (320, 480),
    "drawable-port-hdpi": (480, 800),
    "drawable-port-xhdpi": (720, 1280),
    "drawable-port-xxhdpi": (960, 1600),
    "drawable-port-xxxhdpi": (1280, 1920),
    "drawable-land-mdpi": (480, 320),
    "drawable-land-hdpi": (800, 480),
    "drawable-land-xhdpi": (1280, 720),
    "drawable-land-xxhdpi": (1600, 960),
    "drawable-land-xxxhdpi": (1920, 1280),
}


def build_splash(src):
    lock = Image.open(os.path.join(BRAND, "cc-lockup.png")).convert("RGBA")
    for d, (w, h) in SPLASH.items():
        out = os.path.join(RES, d)
        os.makedirs(out, exist_ok=True)

        canvas = Image.new("RGBA", (w, h), NAVY_DEEP + (255,))
        # Stadium-light atmosphere: cool blue wash from above, faint lime from
        # below. Kept very low-alpha — this must read as "premium dark", not
        # as a neon gaming screen.
        canvas.alpha_composite(radial_glow((w, h), BLUE, 0.30))
        low = radial_glow((w, h), LIME, 0.12)
        canvas.alpha_composite(low, (0, int(h * 0.22)))

        # Logo sized off the SHORTER axis so portrait and landscape both work
        # without the lockup ever running off the edge.
        target = int(min(w, h) * (0.62 if h >= w else 0.42))
        lw, lh = lock.size
        s = target / lw
        art = lock.resize((target, max(1, int(lh * s))), Image.LANCZOS)
        canvas.alpha_composite(art, ((w - art.size[0]) // 2, (h - art.size[1]) // 2))

        canvas.convert("RGB").save(os.path.join(out, "splash.png"), optimize=True)
        print(f"  {d}/splash.png  {w}x{h}")


if __name__ == "__main__":
    src = need_source()
    print("Source:", SOURCE, src.size)
    print("\nWeb brand marks:")
    build_web_marks(src)
    print("\nAndroid launcher icons:")
    build_launcher(src)
    print("\nSplash screens:")
    build_splash(src)
    print("\nDone. legacy-app/ and cricket-connect-android/ were not touched.")
