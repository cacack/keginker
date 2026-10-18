"""Regenerates the fixture photos in this directory (needs Pillow).

Each photo isolates one promise the platform loaders make; the golden view
beside it (`<name>.pbm`) is the expected `renderView` output for the 2.7"
panel (176 x 264). The loader tests write a missing golden and fail, so
after regenerating a photo, delete its golden, run the Android loader test,
check the new golden by eye and commit it.

Edges sit on 8-pixel boundaries and greys are neutral (r = g = b), so the
JPEG decodes exactly on every platform.
"""

from pathlib import Path

from PIL import Image, ImageDraw

HERE = Path(__file__).parent
BLACK, WHITE = (0, 0, 0), (255, 255, 255)


def exif_rotated():
    """Upright: a black block top left, a dark grey band at the bottom. Stored
    sideways with EXIF orientation 6 (rotate 90 degrees clockwise to view)."""
    upright = Image.new("RGB", (352, 528), WHITE)
    draw = ImageDraw.Draw(upright)
    draw.rectangle((0, 0, 175, 175), fill=BLACK)
    draw.rectangle((0, 448, 351, 527), fill=(64, 64, 64))
    stored = upright.transpose(Image.Transpose.ROTATE_90)
    exif = Image.Exif()
    exif[0x0112] = 6
    stored.save(HERE / "exif-rotated.jpg", quality=100, subsampling=0, exif=exif)


def transparent():
    """Transparent black background, an opaque black square and a half-
    transparent black band: white, black and mid grey once over white."""
    image = Image.new("RGBA", (176, 264), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    draw.rectangle((48, 48, 127, 127), fill=(0, 0, 0, 255))
    draw.rectangle((0, 176, 175, 231), fill=(0, 0, 0, 128))
    image.save(HERE / "transparent.png")


def saturated():
    """Eight bands of pure colour, whose greys show the luma weights."""
    colours = [(255, 0, 0), (0, 255, 0), (0, 0, 255), (255, 255, 0),
               (0, 255, 255), (255, 0, 255), WHITE, BLACK]
    image = Image.new("RGB", (176, 264))
    draw = ImageDraw.Draw(image)
    for i, colour in enumerate(colours):
        draw.rectangle((0, i * 33, 175, i * 33 + 32), fill=colour)
    image.save(HERE / "saturated.png")


def wide():
    """1,000 x 150, black but for the 100 x 150 centre crop: white with a
    black bar across it."""
    image = Image.new("RGB", (1000, 150), BLACK)
    draw = ImageDraw.Draw(image)
    draw.rectangle((450, 0, 549, 149), fill=WHITE)
    draw.rectangle((450, 64, 549, 87), fill=BLACK)
    image.save(HERE / "wide.png")


def tall():
    """100 x 1,000, black but for the 100 x 150 centre crop: white with a
    black bar down it."""
    image = Image.new("RGB", (100, 1000), BLACK)
    draw = ImageDraw.Draw(image)
    draw.rectangle((0, 425, 99, 574), fill=WHITE)
    draw.rectangle((40, 425, 63, 574), fill=BLACK)
    image.save(HERE / "tall.png")


def tiny():
    """2 x 3 pixels of different greys, scaled up 88 times."""
    image = Image.new("L", (2, 3))
    image.putdata([0, 255, 255, 0, 85, 170])
    image.convert("RGB").save(HERE / "tiny.png")


if __name__ == "__main__":
    for make in (exif_rotated, transparent, saturated, wide, tall, tiny):
        make()
