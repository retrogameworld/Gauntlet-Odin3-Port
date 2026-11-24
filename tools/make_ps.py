"""PlayStation button pictures for Gauntlet: replaces the DDS payloads of hud_button_a/b/x/y, lb/rb/left_trigger/right_trigger
in GUI bundle ec181385e7000cf1 (same sizes, so nothing else in the bundle moves) and writes, per changed 64 KB chunk, the
new compressed chunk: out_ps/ec181385e7000cf1.chunks = u32 count, then per chunk u32 index, u32 len, bytes.
The app rebuilds the bundle from the original + these chunks (PlayStation) or restores the original chunks (Xbox/Odin):
out_ps/ec181385e7000cf1.orig_chunks has the original ones in the same format."""
import struct, zlib, os, io, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import bdl, mm, ps_buttons as pb
SRC = os.path.join(os.environ.get("GAUNTLET_GAME", "C:/Games/Gauntlet"), "contents", "ec181385e7000cf1")
OUT = os.path.join(os.environ.get("GAUNTLET_BUILD", os.path.join(os.path.dirname(os.path.abspath(__file__)), "build")), "ps_textures")
src = open(SRC, 'rb').read()
raw = bytearray(bdl.unpack(SRC))
TEX = mm.murmur64('texture'); n = struct.unpack_from('<I', raw, 0)[0]; dstart = 260 + n * 16
spans = []
for s in list(pb.FACE) + list(pb.SHOULDER):
    i = raw.find(struct.pack('<I', mm.murmur64('hud_button_' + s) >> 32)); assert i > 0
    assert raw[i + 20:i + 24] == bytes.fromhex('7eb8a83a')
    th = struct.unpack_from('<Q', raw, i + 24)[0]
    k = raw.find(struct.pack('<QQ', TEX, th), dstart); assert k > 0
    size = struct.unpack_from('<I', raw, k + 28)[0]; a = k + 36
    assert raw[a:a + 4] == b'DDS '
    img = pb.face(*pb.FACE[s]) if s in pb.FACE else pb.shoulder(pb.SHOULDER[s])
    new = pb.build_dds(bytes(raw[a:a + size]), img)
    raw[a:a + size] = new; spans.append((a, a + size))
# original chunk table
offs = []; o = 12
while o < len(src):
    l = struct.unpack_from('<I', src, o)[0]; offs.append((o, l)); o += 4 + l
idx = sorted(set(j for a, b in spans for j in range(a // 65536, (b - 1) // 65536 + 1)))
def pack(chunks):
    return struct.pack('<I', len(chunks)) + b''.join(struct.pack('<II', j, len(c)) + c for j, c in chunks)
newc = []; oldc = []
for j in idx:
    c = bytes(raw[j * 65536:(j + 1) * 65536]); z = zlib.compress(c, 9)
    assert len(z) < 65536
    newc.append((j, z)); o, l = offs[j]; oldc.append((j, src[o + 4:o + 4 + l]))
os.makedirs(OUT, exist_ok=True)
open(os.path.join(OUT, 'chunks'), 'wb').write(pack(newc))
open(os.path.join(OUT, 'orig_chunks'), 'wb').write(pack(oldc))
# self-check: rebuild like the app and compare
def rebuild(chunks):
    rep = dict(chunks); out = bytearray(src[:12])
    for j, (o, l) in enumerate(offs):
        c = rep.get(j, src[o + 4:o + 4 + l]); out += struct.pack('<I', len(c)) + c
    return bytes(out)
t = rebuild(newc); open(os.path.join(OUT, 'test_bundle'), 'wb').write(t)
assert bdl.unpack(os.path.join(OUT, 'test_bundle')) == bytes(raw)
assert rebuild(oldc) == src
os.remove(os.path.join(OUT, 'test_bundle'))
print('chunks', idx, 'sizes', sum(len(c) for _, c in newc), sum(len(c) for _, c in oldc))
