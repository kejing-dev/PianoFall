"""Piano Fall icon: the PICO 3D icon layers and the 512 export, drawn from the same shapes as the adaptive icon
(`res/drawable/ic_launcher_foreground.xml` on `@color/ic_launcher_background`, design `palette.icon`).

    python tools/make_icon.py [export.png]

Layer 0: the piano-lacquer tile (#1C1F26). Layer 1: three ivory keys and a black key, a teal and an amber light bar
falling, a teal landing light on the middle key. Coordinates are the vector's 108-unit viewport.
"""
import os
import sys
from PIL import Image, ImageDraw

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
RES = os.path.join(ROOT, "app", "src", "main", "res")
S = 1024
K = S / 108.0
LACQUER = (28, 31, 38, 255)
IVORY = (243, 238, 223, 255)
TEAL = (46, 196, 182, 255)
AMBER = (255, 176, 32, 255)


def box(d, x0, y0, x1, y1, fill, r=0.0):
    d.rounded_rectangle((x0 * K, y0 * K, x1 * K, y1 * K), radius=r * K, fill=fill)


def tile():
    im = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    ImageDraw.Draw(im).rounded_rectangle((40, 40, S - 40, S - 40), radius=220, fill=LACQUER)
    return im


def front():
    im = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    box(d, 40, 27, 46, 47, TEAL, 3)
    box(d, 60, 37, 66, 49, AMBER, 3)
    for x in (25, 45, 65):
        box(d, x, 54, x + 16, 83, IVORY, 3)
    box(d, 57.5, 54, 66.5, 71, LACQUER, 2)
    box(d, 45, 52, 57, 56, TEAL)
    return im


def alpha_white(im):
    w = Image.new("RGBA", im.size, (255, 255, 255, 0))
    w.putalpha(im.split()[3])
    return w


def main():
    l0, l1 = tile(), front()
    draw = os.path.join(RES, "drawable")
    l0.save(os.path.join(draw, "icon_3d_layer_0.png"))
    l1.save(os.path.join(draw, "icon_3d_layer_1.png"))
    alpha_white(l0).save(os.path.join(draw, "icon_3d_sdf_0.png"))
    alpha_white(l1).save(os.path.join(draw, "icon_3d_sdf_1.png"))
    if len(sys.argv) > 1:
        Image.alpha_composite(l0, l1).resize((512, 512), Image.LANCZOS).save(sys.argv[1])
    print("icon layers written")


if __name__ == "__main__":
    main()
