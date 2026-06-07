"""Render the README hero banner: the blue lightning bolt + wordmark on a dark
gradient, matching the app's launcher icon. Output: docs/hero.png
"""
import os
from PIL import Image, ImageDraw, ImageFilter, ImageFont

OUT = os.path.join(os.path.dirname(__file__), "..", "docs", "hero.png")
SS = 2  # supersample

W, H = 1600, 520

BOLT = [
    (0.52, 0.00), (0.16, 0.56), (0.42, 0.56), (0.30, 1.00),
    (0.84, 0.40), (0.56, 0.40), (0.70, 0.00),
]
TOP = (121, 184, 255)      # #79B8FF
BOTTOM = (37, 99, 235)      # #2563EB
GLOW = (59, 130, 246)       # #3B82F6
DARK_TOP = (21, 23, 28)     # #15171C
DARK_BOTTOM = (10, 11, 13)  # #0A0B0D
WHITE = (240, 243, 248)
GREY = (150, 158, 170)
BLUE_TXT = (122, 162, 247)


def lerp(a, b, t):
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(3))


def font(size):
    for p in (
        r"C:\Windows\Fonts\segoeuib.ttf", r"C:\Windows\Fonts\arialbd.ttf",
        r"C:\Windows\Fonts\seguisb.ttf",
    ):
        if os.path.exists(p):
            return ImageFont.truetype(p, size)
    return ImageFont.load_default()


def font_regular(size):
    for p in (r"C:\Windows\Fonts\segoeui.ttf", r"C:\Windows\Fonts\arial.ttf"):
        if os.path.exists(p):
            return ImageFont.truetype(p, size)
    return ImageFont.load_default()


def render_bolt(box):
    """RGBA square of side `box` with the gradient bolt + glow."""
    big = box * SS
    off, length = 0.10, 0.80
    pts = [(big * (off + x * length), big * (off + y * length)) for (x, y) in BOLT]
    ys = [p[1] for p in pts]
    ymin, ymax = min(ys), max(ys)

    mask = Image.new("L", (big, big), 0)
    ImageDraw.Draw(mask).polygon(pts, fill=255)

    grad = Image.new("RGB", (big, big), BOTTOM)
    gd = ImageDraw.Draw(grad)
    span = max(1.0, ymax - ymin)
    for y in range(big):
        t = min(1.0, max(0.0, (y - ymin) / span))
        gd.line([(0, y), (big, y)], fill=lerp(TOP, BOTTOM, t))
    bolt = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    bolt.paste(grad, (0, 0), mask)

    glow_a = mask.filter(ImageFilter.GaussianBlur(big * 0.035)).point(lambda a: int(a * 0.6))
    glow = Image.new("RGBA", (big, big), GLOW + (0,))
    glow.putalpha(glow_a)

    layer = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    layer.alpha_composite(glow)
    layer.alpha_composite(bolt)
    return layer.resize((box, box), Image.LANCZOS)


def main():
    big_w, big_h = W * SS, H * SS
    img = Image.new("RGB", (big_w, big_h), DARK_BOTTOM)
    d = ImageDraw.Draw(img)
    for y in range(big_h):
        d.line([(0, y), (big_w, y)], fill=lerp(DARK_TOP, DARK_BOTTOM, y / big_h))
    img = img.convert("RGBA")

    # Ambient blue glow behind the bolt.
    bolt_box = int(big_h * 0.62)
    bolt_cx = int(big_w * 0.205)
    bolt_cy = big_h // 2
    glow = Image.new("RGBA", (big_w, big_h), (0, 0, 0, 0))
    gr = int(bolt_box * 0.75)
    ImageDraw.Draw(glow).ellipse(
        [bolt_cx - gr, bolt_cy - gr, bolt_cx + gr, bolt_cy + gr], fill=GLOW + (90,)
    )
    img.alpha_composite(glow.filter(ImageFilter.GaussianBlur(big_h * 0.07)))

    # Bolt.
    bolt = render_bolt(bolt_box)
    img.alpha_composite(bolt, (bolt_cx - bolt_box // 2, bolt_cy - bolt_box // 2))

    # Text.
    tx = int(big_w * 0.40)
    title_f = font(int(big_h * 0.20))
    tag_f = font_regular(int(big_h * 0.072))
    chip_f = font_regular(int(big_h * 0.055))

    d = ImageDraw.Draw(img)
    title = "Supertonic TTS"
    tb = d.textbbox((0, 0), title, font=title_f)
    th = tb[3] - tb[1]
    tagline = "Lightning-fast, on-device text-to-speech for Android"
    chips = "Multilingual   •   Output-device routing   •   WAV / AAC / Opus / MP3"

    block_h = th + int(big_h * 0.16) + int(big_h * 0.10)
    y = bolt_cy - block_h // 2
    d.text((tx, y - tb[1]), title, font=title_f, fill=WHITE)
    y += th + int(big_h * 0.07)
    d.text((tx, y), tagline, font=tag_f, fill=GREY)
    y += int(big_h * 0.115)
    d.text((tx, y), chips, font=chip_f, fill=BLUE_TXT)

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    img.convert("RGB").resize((W, H), Image.LANCZOS).save(OUT)
    print("Banner written to", os.path.normpath(OUT))


main()
