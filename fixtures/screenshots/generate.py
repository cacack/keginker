"""Regenerates the sample label the README screenshots show (needs Pillow
10.1 or later, for its built-in scalable font).

The label is drawn at the 2.7" panel's view size (176 x 264) in pure black
and white, so every dither renders it unchanged. After regenerating it,
refresh the screenshots (see CONTRIBUTING.md).
"""

from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).parent
WIDTH, HEIGHT = 176, 264
BLACK, WHITE = 0, 255


def font(size):
    return ImageFont.load_default(size)


def centered(draw, y, text, size, fill=BLACK, bold=False):
    stroke = 1 if bold else 0
    left, _, right, _ = draw.textbbox((0, 0), text, font=font(size), stroke_width=stroke)
    draw.text(((WIDTH - (right - left)) // 2 - left, y), text, font=font(size), fill=fill,
              stroke_width=stroke, stroke_fill=fill)


def hop(draw, cx, top):
    """A hop cone: rows of overlapping circles under a stem."""
    draw.line((cx, top, cx + 4, top - 8), fill=BLACK, width=2)
    for row, count in enumerate((2, 3, 2, 1)):
        y = top + 4 + row * 9
        for i in range(count):
            x = cx + (i - (count - 1) / 2) * 11
            draw.ellipse((x - 6, y - 6, x + 6, y + 6), fill=WHITE, outline=BLACK, width=2)


def sample_label():
    image = Image.new("L", (WIDTH, HEIGHT), WHITE)
    draw = ImageDraw.Draw(image)
    draw.rectangle((2, 2, WIDTH - 3, HEIGHT - 3), outline=BLACK, width=4)
    draw.rectangle((9, 9, WIDTH - 10, HEIGHT - 10), outline=BLACK, width=1)
    draw.rectangle((9, 9, WIDTH - 10, 34), fill=BLACK)
    centered(draw, 13, "TAP 3", 16, fill=WHITE, bold=True)
    hop(draw, WIDTH // 2, 50)
    centered(draw, 94, "HOP", 30, bold=True)
    centered(draw, 128, "LANTERN", 25, bold=True)
    draw.line((28, 166, WIDTH - 29, 166), fill=BLACK, width=2)
    centered(draw, 176, "Hazy IPA", 18)
    centered(draw, 204, "6.4% ABV  ·  45 IBU", 13)
    centered(draw, 226, "Citra · Mosaic", 12)
    # Bold text is antialiased; snap it to black and white.
    image.point(lambda v: WHITE if v >= 128 else BLACK).save(HERE / "sample-label.png")


if __name__ == "__main__":
    sample_label()
