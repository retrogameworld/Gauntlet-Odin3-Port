import os, lzma, zstandard, sys
ID = (sys.argv[1] if len(sys.argv) > 1 else "com.winlator.cmod").encode()
def opener(path):
    with open(path, "rb") as f: head = f.read(6)
    if head[:6] == b"\xfd7zXZ\x00": return lzma.open(path, "rb")
    if head[:4] == b"\x28\xb5\x2f\xfd": return zstandard.ZstdDecompressor().stream_reader(open(path, "rb"))
    return None
for root, _, files in os.walk("dec/assets"):
    for n in files:
        p = os.path.join(root, n); o = opener(p)
        if o is None:
            data = open(p, "rb").read(); c = data.count(ID)
            if c: print("RAW ", c, p)
            continue
        c = 0; tail = b""
        while True:
            b = o.read(1 << 22)
            if not b: break
            c += (tail + b).count(ID); tail = b[-len(ID) + 1:]
        if c: print("ARCH", c, p, flush=True)
