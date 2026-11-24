"""Draws PlayStation-style replacements for Gauntlet's hud_button_* textures (same size, DXT5 with all mip levels).
Writes btn/ps_<name>.dds and a preview sheet. Face buttons: dark round button, coloured symbol; shoulders: L1/R1/L2/R2."""
import io, struct, math
from PIL import Image, ImageDraw, ImageFont, ImageFilter
import mm

SS = 8
FACE = {'a': ('cross', (124, 178, 232)), 'b': ('circle', (255, 102, 102)), 'x': ('square', (230, 150, 210)), 'y': ('triangle', (64, 224, 176))}
SHOULDER = {'lb': 'L1', 'rb': 'R1', 'left_trigger': 'L2', 'right_trigger': 'R2'}

def face(sym, col, w=36, h=36):
    W, H = w * SS, h * SS
    im = Image.new('RGBA', (W, H), (0, 0, 0, 0)); d = ImageDraw.Draw(im)
    cx, cy, r = W / 2, H / 2, W * 0.47
    d.ellipse((cx - r, cy - r, cx + r, cy + r), fill=(20, 20, 22, 255))                       # outer dark ring
    r2 = r * 0.86
    d.ellipse((cx - r2, cy - r2, cx + r2, cy + r2), fill=(175, 175, 180, 255))                # light rim
    r3 = r * 0.76
    d.ellipse((cx - r3, cy - r3, cx + r3, cy + r3), fill=(38, 38, 44, 255))                   # button face
    # soft top highlight
    hl = Image.new('RGBA', (W, H), (0, 0, 0, 0)); hd = ImageDraw.Draw(hl)
    hd.ellipse((cx - r3 * 0.8, cy - r3 * 0.95, cx + r3 * 0.8, cy - r3 * 0.05), fill=(255, 255, 255, 38))
    im.alpha_composite(hl.filter(ImageFilter.GaussianBlur(SS * 2)))
    s = r3 * 0.6; lw = int(SS * 3.8)
    if sym == 'cross':
        d.line((cx - s, cy - s, cx + s, cy + s), fill=col, width=lw); d.line((cx - s, cy + s, cx + s, cy - s), fill=col, width=lw)
    elif sym == 'circle':
        d.ellipse((cx - s, cy - s, cx + s, cy + s), outline=col, width=lw)
    elif sym == 'square':
        q = s * 0.86; d.rectangle((cx - q, cy - q, cx + q, cy + q), outline=col, width=lw)
    else:
        t = s * 1.08
        pts = [(cx, cy - t), (cx + t * 0.95, cy + t * 0.62), (cx - t * 0.95, cy + t * 0.62)]
        d.line(pts + [pts[0]], fill=col, width=lw, joint='curve')
    return im.resize((w, h), Image.LANCZOS)

def shoulder(text, w=44, h=36):
    W, H = w * SS, h * SS
    im = Image.new('RGBA', (W, H), (0, 0, 0, 0)); d = ImageDraw.Draw(im)
    m = SS * 1
    d.rounded_rectangle((m, m + SS * 2, W - m, H - m - SS * 2), radius=SS * 8, fill=(20, 20, 22, 255))
    d.rounded_rectangle((m + SS * 2, m + SS * 4, W - m - SS * 2, H - m - SS * 4), radius=SS * 6, fill=(150, 150, 156, 255))
    d.rounded_rectangle((m + SS * 4, m + SS * 6, W - m - SS * 4, H - m - SS * 6), radius=SS * 5, fill=(52, 52, 58, 255))
    try: f = ImageFont.truetype('arialbd.ttf', SS * 17)
    except Exception: f = ImageFont.load_default()
    bb = d.textbbox((0, 0), text, font=f)
    d.text(((W - (bb[2] - bb[0])) / 2 - bb[0], (H - (bb[3] - bb[1])) / 2 - bb[1]), text, font=f, fill=(245, 245, 245, 255))
    return im.resize((w, h), Image.LANCZOS)

def dxt5_level(img):
    b = io.BytesIO(); img.save(b, format='DDS', pixel_format='DXT5'); d = b.getvalue()
    return d[128:]

def build_dds(orig, img):
    """original DDS (header + mips + trailer) -> same layout, new pixels"""
    h, w = struct.unpack_from('<II', orig, 12)
    mips = struct.unpack_from('<I', orig, 28)[0]
    data = b''; lw, lh = w, h; level = img
    for i in range(mips):
        lvl = img.resize((lw, lh), Image.LANCZOS) if i else img
        data += dxt5_level(lvl)
        lw, lh = max(1, lw // 2), max(1, lh // 2)
    out = orig[:128] + data + orig[128 + len(data):]
    assert len(out) == len(orig), (len(out), len(orig))
    return out
