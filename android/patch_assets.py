"""Rewrites packed assets so every hard-wired app name becomes the new (same length) one."""
import sys, lzma, zstandard, tarfile, io, os
OLD = sys.argv[1].encode(); NEW = sys.argv[2].encode(); assert len(OLD) == len(NEW)
def patch(path):
    with open(path, "rb") as f: head = f.read(6)
    xz = head[:6] == b"\xfd7zXZ\x00"
    src = lzma.open(path, "rb") if xz else zstandard.ZstdDecompressor().stream_reader(open(path, "rb"))
    tmp = path + ".new"
    raw = open(tmp, "wb")
    dst = lzma.open(raw, "wb", preset=2) if xz else zstandard.ZstdCompressor(level=10, threads=-1).stream_writer(raw)
    tin = tarfile.open(fileobj=src, mode="r|"); tout = tarfile.open(fileobj=dst, mode="w|", format=tin.format or tarfile.GNU_FORMAT)
    files = hits = 0
    for m in tin:
        if OLD.decode() in m.name: m.name = m.name.replace(OLD.decode(), NEW.decode()); hits += 1
        if m.linkname and OLD.decode() in m.linkname: m.linkname = m.linkname.replace(OLD.decode(), NEW.decode()); hits += 1
        m.pax_headers = {k: (v.replace(OLD.decode(), NEW.decode()) if isinstance(v, str) else v) for k, v in m.pax_headers.items()}
        if m.isreg():
            data = tin.extractfile(m).read()
            if OLD in data: data = data.replace(OLD, NEW); hits += 1
            tout.addfile(m, io.BytesIO(data))
        else: tout.addfile(m)
        files += 1
    tout.close(); dst.close(); raw.close(); src.close()
    os.replace(tmp, path)
    print(path, files, "entries,", hits, "patched", flush=True)
for p in sys.argv[3:]: patch(p)
