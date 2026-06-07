"""Generate the Supertonic TTS launcher icon: a blue lightning bolt.

Outputs:
  - mipmap-*/ic_launcher_foreground.png  (adaptive-icon foreground, transparent)
  - mipmap-*/ic_launcher.png             (legacy square icon)
  - mipmap-*/ic_launcher_round.png       (legacy round icon)

Background + monochrome are provided as vector drawables (see res/drawable).
"""
import os
from PIL import Image, ImageDraw, ImageFilter

RES = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res")
SS = 4  # supersampling factor for smooth edges

# Normalized lightning-bolt polygon (x, y in 0..1, y down).
BOLT = [
    (0.52, 0.00), (0.16, 0.56), (0.42, 0.56), (0.30, 1.00),
    (0.84, 0.40), (0.56, 0.40), (0.70, 0.00),
]

TOP = (121, 184, 255)     # #79B8FF
BOTTOM = (37, 99, 235)     # #2563EB
GLOW = (59, 130, 246)      # #3B82F6
DARK_TOP = (21, 23, 28)    # #15171C
DARK_BOTTOM = (10, 11, 13) # #0A0B0D

FOREGROUND = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}
LEGACY = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}


def lerp(a, b, t):
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(3))


def bolt_points(big, off, length):
    return [(big * (off + x * length), big * (off + y * length)) for (x, y) in BOLT]


def draw_bolt(big, off, length):
    """Return an RGBA layer (size big) with the gradient bolt + soft glow."""
    pts = bolt_points(big, off, length)
    ys = [p[1] for p in pts]
    ymin, ymax = min(ys), max(ys)

    # Shape mask.
    mask = Image.new("L", (big, big), 0)
    ImageDraw.Draw(mask).polygon(pts, fill=255)

    # Vertical gradient over the bolt's vertical extent.
    grad = Image.new("RGB", (big, big), BOTTOM)
    gd = ImageDraw.Draw(grad)
    span = max(1.0, ymax - ymin)
    for y in range(big):
        t = min(1.0, max(0.0, (y - ymin) / span))
        gd.line([(0, y), (big, y)], fill=lerp(TOP, BOTTOM, t))
    bolt = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    bolt.paste(grad, (0, 0), mask)

    # Soft outer glow.
    glow_alpha = mask.filter(ImageFilter.GaussianBlur(big * 0.030))
    glow_alpha = glow_alpha.point(lambda a: int(a * 0.55))
    glow = Image.new("RGBA", (big, big), GLOW + (0,))
    glow.putalpha(glow_alpha)

    layer = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    layer.alpha_composite(glow)
    layer.alpha_composite(bolt)
    return layer


def make_foreground(size):
    big = size * SS
    # Keep the bolt inside the circular safe zone (central ~64 of 108).
    layer = draw_bolt(big, off=22.0 / 108.0, length=64.0 / 108.0)
    return layer.resize((size, size), Image.LANCZOS)


def dark_bg(big, round_):
    bg = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    grad = Image.new("RGB", (big, big), DARK_BOTTOM)
    gd = ImageDraw.Draw(grad)
    for y in range(big):
        gd.line([(0, y), (big, y)], fill=lerp(DARK_TOP, DARK_BOTTOM, y / big))
    if round_:
        m = Image.new("L", (big, big), 0)
        ImageDraw.Draw(m).ellipse([0, 0, big - 1, big - 1], fill=255)
        bg.paste(grad, (0, 0), m)
    else:
        bg.paste(grad, (0, 0))
    return bg


def make_legacy(size, round_):
    big = size * SS
    base = dark_bg(big, round_)
    base.alpha_composite(draw_bolt(big, off=0.22, length=0.56))
    return base.resize((size, size), Image.LANCZOS)


def save(img, density, name):
    d = os.path.join(RES, f"mipmap-{density}")
    os.makedirs(d, exist_ok=True)
    img.save(os.path.join(d, name))


for density, size in FOREGROUND.items():
    save(make_foreground(size), density, "ic_launcher_foreground.png")
for density, size in LEGACY.items():
    save(make_legacy(size, False), density, "ic_launcher.png")
    save(make_legacy(size, True), density, "ic_launcher_round.png")

print("Icons generated.")
