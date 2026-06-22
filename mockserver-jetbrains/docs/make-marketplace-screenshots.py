#!/usr/bin/env python3
"""Frame raw IDE screenshots for the JetBrains Marketplace gallery.

Bakes in what the website gets from CSS (.ui_image): equal padding, a dark
rounded card, rounded screenshot corners, and a subtle border. Source = the
full-res captures; the committed website images are left untouched.
"""
import sys
from pathlib import Path
from PIL import Image, ImageDraw

CARD_BG = (20, 22, 26, 255)      # dark card behind the screenshot
BORDER = (43, 45, 49, 255)        # subtle 1px edge
PAD = 56                          # equal space on all sides
INNER_RADIUS = 12                 # screenshot corner rounding
OUTER_RADIUS = 26                 # card corner rounding

def rounded_alpha(size, radius):
    mask = Image.new("L", size, 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, size[0] - 1, size[1] - 1], radius=radius, fill=255)
    return mask

def frame(src: Path, dst: Path):
    shot = Image.open(src).convert("RGBA")
    shot.putalpha(rounded_alpha(shot.size, INNER_RADIUS))
    w, h = shot.size
    canvas = Image.new("RGBA", (w + 2 * PAD, h + 2 * PAD), (0, 0, 0, 0))
    card = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(card)
    d.rounded_rectangle([0, 0, canvas.size[0] - 1, canvas.size[1] - 1],
                        radius=OUTER_RADIUS, fill=CARD_BG, outline=BORDER, width=1)
    canvas = Image.alpha_composite(canvas, card)
    canvas.paste(shot, (PAD, PAD), shot)
    dst.parent.mkdir(parents=True, exist_ok=True)
    canvas.save(dst)
    print(f"  {src.name}  {w}x{h}  ->  {dst}  {canvas.size[0]}x{canvas.size[1]}")

def main():
    src_dir, dst_dir = Path(sys.argv[1]), Path(sys.argv[2])
    for src in sorted(src_dir.glob("*.png")):
        frame(src, dst_dir / src.name)

if __name__ == "__main__":
    main()
