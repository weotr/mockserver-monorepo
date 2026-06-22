#!/usr/bin/env python3
"""Produce mockserver_response_verification.png in the SAME architecture style as
the "Verifying Requests" diagram (system_under_test_with_mockserver_proxy.png) by
copying that image and swapping the single label "2. Verify Requests" ->
"2. Verify Responses". This reuses PowerPoint's original rendering verbatim, so
the result is pixel-identical to its sibling apart from the one word.

If the source diagram is ever re-exported, re-run this. If you move the label,
adjust TEXT_BBOX below (the dark-text bounding box of "2. Verify Requests").

Usage:  python3 make_response_verification.py
"""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

IMAGES = Path(__file__).resolve().parent.parent
SRC = IMAGES / "system_under_test_with_mockserver_proxy.png"
DST = IMAGES / "mockserver_response_verification.png"
OLD, NEW = "2. Verify Requests", "2. Verify Responses"
TEXT_BBOX = (153, 374, 385, 402)   # dark-text bbox of OLD label in the source
COLOR = (20, 20, 20)
FONTS = ["/System/Library/Fonts/Supplemental/Arial.ttf",
         "/System/Library/Fonts/Helvetica.ttc"]

def font(px):
    for f in FONTS:
        try: return ImageFont.truetype(f, px)
        except Exception: pass
    return ImageFont.load_default()

def main():
    im = Image.open(SRC).convert("RGB")
    d = ImageDraw.Draw(im)
    x0, y0, x1, y1 = TEXT_BBOX
    target_w = x1 - x0
    # pick the font size whose rendered OLD width matches the source label width
    best = min(range(20, 60),
               key=lambda px: abs((d.textbbox((0,0), OLD, font=font(px))[2]) - target_w))
    f = font(best)
    nb = d.textbbox((0, 0), NEW, font=f)
    d.rectangle([x0-7, y0-6, x0 + nb[2] + 8, y1 + 4], fill="white")  # erase old label
    d.text((x0, y0 - nb[1]), NEW, font=f, fill=COLOR)               # draw new label
    im.save(DST)
    print(f"saved {DST.name} ({im.size}) font px={best}")

if __name__ == "__main__":
    main()
