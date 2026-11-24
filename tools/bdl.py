"""Bitsquid bundle (Gauntlet, version 0xF0000004): header u32 version, u32 size, u32 0, then chunks u32 len + zlib (64 KB each)."""
import struct, zlib, sys, os
def unpack(path):
    d = open(path, 'rb').read()
    ver, size, _ = struct.unpack_from('<III', d, 0)
    o = 12; out = []
    while o < len(d) and sum(map(len, out)) < size:
        n = struct.unpack_from('<I', d, o)[0]; o += 4
        c = d[o:o + n]; o += n
        out.append(zlib.decompress(c) if n < 65536 else c)
    return b''.join(out)[:size]
if __name__ == '__main__':
    pats = [p.encode() for p in sys.argv[2:]]
    for f in sorted(os.listdir(sys.argv[1])):
        p = os.path.join(sys.argv[1], f)
        if len(f) != 16: continue
        try: u = unpack(p)
        except Exception as e: print(f, 'ERR', e); continue
        for q in pats:
            i = u.find(q)
            if i >= 0: print(f, len(u), q.decode(), i, u.count(q))
