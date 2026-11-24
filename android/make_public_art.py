"""Own artwork for the public build (no game screenshots): splash 1920x1080 and launcher icon -> assets_public/."""
import math, os
from PIL import Image, ImageDraw, ImageFont, ImageFilter

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "assets_public")
W, H = 1920, 1080


def font(size, bold=True):
    for n in (["georgiab.ttf", "timesbd.ttf"] if bold else ["georgia.ttf", "times.ttf"]):
        try:
            return ImageFont.truetype(n, size)
        except Exception:
            pass
    return ImageFont.load_default()


def main():
    im = Image.new("RGB", (W, H))
    px = im.load()
    for y in range(H):
        for x in range(0, W, 2):
            dx = (x - W / 2) / (W / 2); dy = (y - H * 0.42) / (H / 2)
            r = min(1.0, math.sqrt(dx * dx + dy * dy))
            c = (int(48 * (1 - r) + 8), int(27 * (1 - r) + 6), int(14 * (1 - r) + 6))
            px[x, y] = c
            if x + 1 < W:
                px[x + 1, y] = c
    d = ImageDraw.Draw(im)

    def ctext(y, t, f, fill):
        bb = d.textbbox((0, 0), t, font=f)
        d.text(((W - (bb[2] - bb[0])) / 2 - bb[0], y), t, font=f, fill=fill)

    # crossed swords (own drawing)
    cx, cy = W // 2, 300
    for ang in (-38, 38):
        a = math.radians(ang)
        x1, y1 = cx - math.sin(a) * 200, cy + 190
        x2, y2 = cx + math.sin(a) * 200, cy - 170
        d.line((x1, y1, x2, y2), fill=(205, 175, 115), width=14)
        gx, gy = x1 + (x2 - x1) * 0.22, y1 + (y2 - y1) * 0.22
        nx, ny = (y2 - y1), -(x2 - x1)
        n = math.hypot(nx, ny)
        d.line((gx - nx / n * 55, gy - ny / n * 55, gx + nx / n * 55, gy + ny / n * 55), fill=(160, 120, 60), width=16)
    ctext(540, "GAUNTLET", font(150), (226, 196, 132))
    ctext(715, "Slayer Edition  -  AYN Odin 3", font(54, False), (200, 180, 150))
    ctext(985, "Unofficial port - uses your own GOG copy of the game", font(30, False), (140, 120, 100))
    im = im.filter(ImageFilter.GaussianBlur(0.6))
    os.makedirs(OUT, exist_ok=True)
    im.save(os.path.join(OUT, "gauntlet_splash.jpg"), quality=92)
    icon = im.crop((cx - 250, cy - 230, cx + 250, cy + 270)).resize((432, 432), Image.LANCZOS)
    icon.save(os.path.join(OUT, "gauntlet_icon_layer.png"))
    icon.resize((512, 512), Image.LANCZOS).save(os.path.join(OUT, "gauntlet_icon.png"))


if __name__ == "__main__":
    main()
