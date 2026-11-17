"""Generates the dragon egg sprite for the locator bar, the pack icon and a preview sheet.

The shape is the egg's side profile in game: stacked layers that get wider towards the bottom
(4, 6, 8, 10, 12, 14 pixels), the widest part in the lower half and a flat, slightly narrower base.
Almost black with purple specks, the top stays plain. The upper edge of every layer that sticks out
is a little lighter, so the steps stay readable. An optional purple rim keeps the silhouette visible
on the dark locator bar.

usage: make_sprite.py <sprite.png> <pack.png> <preview.png> [--no-rim] [--flat-top]
(the shipped pack uses --no-rim)
"""
import random
import sys
from pathlib import Path
from PIL import Image

# Row widths from top to bottom, centered.
# 16 rows: the side profile of the block model. 9 rows: the same profile scaled to the size of a locator bar dot.
SHAPE_16 = [4, 6, 8, 10, 10, 12, 12, 12, 14, 14, 14, 14, 14, 12, 12, 10]
SHAPE_9 = [3, 5, 7, 7, 9, 9, 9, 9, 7]
# Same without the top layer (row 0 stays empty so the egg keeps its position on the bar)
SHAPE_9_FLAT_TOP = [0, 5, 7, 7, 9, 9, 9, 9, 7]

BODY = (22, 19, 27)
SHADE = (14, 12, 18)
LEDGE = (52, 45, 62)
RIM = (134, 52, 196)
SPECK_DARK = (58, 18, 78)
SPECK = (96, 32, 130)
SPECK_BRIGHT = (142, 60, 186)
GRAIN = (-3, 0, 0, 3)

# Hand-placed specks for the small sprite (x, y, color): spread out, none on the top two rows, none on the edge
SPECKS_9 = [
    (5, 2, SPECK_DARK), (2, 3, SPECK), (6, 4, SPECK), (3, 5, SPECK_BRIGHT),
    (1, 6, SPECK_DARK), (7, 6, SPECK_DARK), (5, 7, SPECK),
]


def draw(shape, specks=None, speck_count=0, seed=0, rim=True):
    size = len(shape)
    width = max(shape)
    image = Image.new("RGBA", (width, size), (0, 0, 0, 0))
    pixels = []
    for y, row_width in enumerate(shape):
        start = (width - row_width) // 2
        above = shape[y - 1] if y > 0 else 0
        above_start = (width - above) // 2
        for x in range(start, start + row_width):
            # Top surface of a layer: not covered by the narrower layer above
            ledge = y == 0 or not (above_start <= x < above_start + above)
            # The right third is a little darker, like the shaded side of a block
            base = LEDGE if ledge else (SHADE if x >= start + row_width * 2 // 3 else BODY)
            shift = GRAIN[(x * 7 + y * 13) % len(GRAIN)]
            image.putpixel((x, y), tuple(max(0, c + shift) for c in base) + (255,))
            pixels.append((x, y, ledge))

    if specks is None:
        rng = random.Random(seed)
        speckable = [(x, y) for x, y, ledge in pixels if 3 <= y < size - 1 and not ledge]
        used = set()
        specks = []
        while len(specks) < speck_count:
            x, y = rng.choice(speckable)
            if any(abs(x - ux) <= 1 and abs(y - uy) <= 1 for ux, uy in used):
                continue
            used.add((x, y))
            roll = rng.random()
            color = SPECK_BRIGHT if roll < 0.2 else SPECK if roll < 0.6 else SPECK_DARK
            specks.append((x, y, color))
    for x, y, color in specks:
        if image.getpixel((x, y))[3] == 255:
            image.putpixel((x, y), color + (255,))

    if rim:
        edge = []
        for y in range(size):
            for x in range(width):
                if image.getpixel((x, y))[3] != 255:
                    continue
                if any(not (0 <= x + dx < width and 0 <= y + dy < size) or image.getpixel((x + dx, y + dy))[3] == 0
                       for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                    edge.append((x, y))
        for x, y in edge:
            image.putpixel((x, y), RIM + (255,))
    return image


def large(rim=True):
    """16 rows, 14 wide, centered on a 16x16 canvas."""
    egg = draw(SHAPE_16, speck_count=22, seed=7, rim=rim)
    canvas = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    canvas.paste(egg, (1, 0), egg)
    return canvas


def small(rim=True, flat_top=False):
    return draw(SHAPE_9_FLAT_TOP if flat_top else SHAPE_9, specks=SPECKS_9, rim=rim)


def scaled(image, factor):
    return image.resize((image.width * factor, image.height * factor), Image.NEAREST)


def main():
    sprite_path, pack_icon_path, preview_path = map(Path, sys.argv[1:4])
    rim = "--no-rim" not in sys.argv
    sprite = small(rim, flat_top="--flat-top" in sys.argv)
    icon = large(rim)

    sprite_path.parent.mkdir(parents=True, exist_ok=True)
    sprite.save(sprite_path)
    scaled(icon, 8).save(pack_icon_path)

    # Preview: large and small on white, then the 9x9 sprite at GUI scale 3 on a dark bar
    sheet = Image.new("RGBA", (16 * 16 + 9 * 16 + 90, 16 * 16 + 110), (255, 255, 255, 255))
    sheet.paste(scaled(icon, 16), (30, 30), scaled(icon, 16))
    sheet.paste(scaled(sprite, 16), (16 * 16 + 60, 30), scaled(sprite, 16))
    bar_y = 16 * 16 + 50
    for x in range(30, sheet.width - 30):
        for y in range(bar_y, bar_y + 33):
            sheet.putpixel((x, y), (60, 60, 60, 255))
    sheet.paste(scaled(sprite, 3), (sheet.width // 2 - 14, bar_y + 3), scaled(sprite, 3))
    sheet.save(preview_path)


if __name__ == "__main__":
    main()
