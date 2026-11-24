"""One-time preparation of the Winlator Cmod tree the app is built on (see BUILDING.md).

    java -jar apktool.jar d Winlator-Cmod-v13.1.1.apk -o dec
    python prepare_winlator.py

Does, in dec/:
  1. renames the app to com.gauntlet.odin (same length as com.winlator.cmod, because the packed system files carry
     hard-wired /data/data/<id> paths): manifest, apktool.yml, smali strings, libevshim.so, and the packed
     imagefs.txz / proton-9.0-arm64ec.txz / graphics_driver/extra_libs.tzst (via patch_assets.py);
     Java package names stay com.winlator.cmod;
  2. hides Winlator's own UI pieces: the "Starting up" dialog, the mouse pointer, the side menu on Back;
  3. removes Winlator's launcher entry (the app's own start screen, com.gauntlet.sa.Start, is added by prep_sa.py).
Components added by hand (see BUILDING.md): box64 0.4.2 (assets/box86_64/box64-0.4.2.tzst),
DXVK 2.7.1 (assets/dxwrapper/dxvk-2.7.1.tzst), Turnip T26 driver (assets_public/driver/).
"""
import io, os, re, subprocess, sys

HERE = os.path.dirname(os.path.abspath(__file__))
D = os.path.join(HERE, "dec")
OLD, NEW = "com.winlator.cmod", "com.gauntlet.odin"
assert len(OLD) == len(NEW)


def rd(p): return io.open(p, encoding="utf-8").read()
def wr(p, s): io.open(p, "w", encoding="utf-8", newline="\n").write(s)


def find(name):
    for root, _, files in os.walk(D):
        if name in files and "smali" in root:
            return os.path.join(root, name)
    raise SystemExit("not found: " + name)


# 1. app id
y = rd(os.path.join(D, "apktool.yml"))
if "renameManifestPackage: " + NEW not in y:
    y = re.sub(r"renameManifestPackage: .*", "renameManifestPackage: " + NEW, y) if "renameManifestPackage" in y else y.replace("packageInfo:", "packageInfo:\n  renameManifestPackage: " + NEW)
    wr(os.path.join(D, "apktool.yml"), y)
m = rd(os.path.join(D, "AndroidManifest.xml"))
m = re.sub(r'android:authorities="com\.winlator\.cmod', 'android:authorities="' + NEW, m)
m = m.replace('<category android:name="android.intent.category.LAUNCHER"/>', "")
wr(os.path.join(D, "AndroidManifest.xml"), m)
for root, _, files in os.walk(D):
    if "smali" not in root:
        continue
    for f in files:
        p = os.path.join(root, f); s = rd(p)
        t = s.replace('"' + OLD + '"', '"' + NEW + '"').replace("/data/data/" + OLD, "/data/data/" + NEW).replace("/data/user/0/" + OLD, "/data/user/0/" + NEW)
        if t != s:
            wr(p, t)
so = os.path.join(D, "lib", "arm64-v8a", "libevshim.so")
b = open(so, "rb").read()
open(so, "wb").write(b.replace(OLD.encode(), NEW.encode()))
packed = [os.path.join(D, "assets", n) for n in ("imagefs.txz", "proton-9.0-arm64ec.txz", os.path.join("graphics_driver", "extra_libs.tzst"))]
subprocess.check_call([sys.executable, os.path.join(HERE, "patch_assets.py"), OLD, NEW] + packed)

# 2. Winlator's own UI pieces that must never show (markers keep this re-runnable)
p = find("PreloaderDialog.smali"); s = rd(p)
if "popfs: never shown" not in s:
    s, n = re.subn(r'(\.method public declared-synchronized show\(I\)V\n    \.locals \d+\n)', r'\1    # popfs: never shown\n    return-void\n', s); assert n == 1
    wr(p, s)
p = find("GLRenderer.smali"); s = rd(p)
if "popfs: no pointer" not in s:
    s, n = re.subn(r'(\.method public setCursorVisible\(Z\)V\n    \.locals \d+\n)', r'\1    # popfs: no pointer\n    const/4 p1, 0x0\n', s); assert n == 1
    wr(p, s)
p = find("XServerDisplayActivity.smali"); s = rd(p)
if "popfs: no menu" not in s:
    s, n = re.subn(r'(\.method public onBackPressed\(\)V\n    \.locals \d+\n)', r'\1    # popfs: no menu\n    return-void\n', s); assert n == 1
    wr(p, s)
print("Winlator tree prepared. Remaining app ids:")
subprocess.call([sys.executable, os.path.join(HERE, "scan_assets.py"), OLD])
