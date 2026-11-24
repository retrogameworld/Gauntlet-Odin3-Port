"""Turns the decoded Winlator tree (dec/, prepared by prepare_winlator.py as com.gauntlet.odin)
into Gauntlet's own app. Safe to run again."""
import io, os, re, shutil, json

HERE = os.path.dirname(os.path.abspath(__file__))
D = os.path.join(HERE, "dec")
A = os.path.join(HERE, "assets_public")


def rd(p): return io.open(p, encoding="utf-8").read()
def wr(p, s): io.open(p, "w", encoding="utf-8", newline="\n").write(s)


# ---- our assets: environment template, shortcut, graphics driver
G = os.path.join(D, "assets", "gauntlet")
shutil.rmtree(G, ignore_errors=True)
os.makedirs(G)
c = json.loads(rd(os.path.join(A, "container_odin.json")))
c["extraData"] = {}
c.pop("id", None)
wr(os.path.join(G, "container.json"), json.dumps(c, separators=(",", ":")))
wr(os.path.join(G, "shortcut.desktop"), rd(os.path.join(A, "shortcut_odin.desktop")).replace("com.winlator.cmod", "@PKG@"))
shutil.copytree(os.path.join(A, "driver"), os.path.join(G, "driver"))
# the two button-letter versions of the patched game bundles (bundles/patch_menu.py)
if os.path.isdir(os.path.join(A, "odin_buttons")): shutil.copytree(os.path.join(A, "odin_buttons"), os.path.join(G, "odin_buttons"))
if os.path.isdir(os.path.join(A, "xbox_buttons")): shutil.copytree(os.path.join(A, "xbox_buttons"), os.path.join(G, "xbox_buttons"))
if os.path.isdir(os.path.join(A, "ps_textures")): shutil.copytree(os.path.join(A, "ps_textures"), os.path.join(G, "ps_textures"))

# ---- version: counts up with every build, so a new APK installs as an update
vp = os.path.join(HERE, "build_number.txt")
n = (int(open(vp).read()) if os.path.exists(vp) else 0) + 1
open(vp, "w").write(str(n))
y = rd(os.path.join(D, "apktool.yml"))
y = re.sub(r"versionCode: \d+", "versionCode: %d" % (100 + n), y)
y = re.sub(r"versionName: .*", "versionName: 1.0.%d" % n, y)
wr(os.path.join(D, "apktool.yml"), y)
wr(os.path.join(G, "version.txt"), str(n))

# ---- pictures
nd = os.path.join(D, "res", "drawable-nodpi")
for f in os.listdir(nd):
    if f.startswith("popfs_"): os.remove(os.path.join(nd, f))
for f in ("gauntlet_splash.jpg", "gauntlet_icon.png", "gauntlet_icon_layer.png"):
    shutil.copy(os.path.join(A, f), os.path.join(nd, f))
mm = os.path.join(D, "res", "mipmap-anydpi-v26")
if os.path.exists(os.path.join(mm, "popfs_launcher.xml")): os.remove(os.path.join(mm, "popfs_launcher.xml"))
wr(os.path.join(mm, "gauntlet_launcher.xml"), '''<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/gauntlet_icon_layer"/>
    <foreground android:drawable="@android:color/transparent"/>
</adaptive-icon>
''')

NL = chr(10)
# ---- manifest: our start screen is the only launcher entry; name and icon of the game
p = os.path.join(D, "AndroidManifest.xml"); m = rd(p)
if "com.gauntlet.sa.Start" not in m:
    act = ('<activity android:name="com.gauntlet.sa.Start" android:exported="true" android:screenOrientation="sensorLandscape" '
           'android:theme="@android:style/Theme.Black.NoTitleBar.Fullscreen" '
           'android:configChanges="density|keyboard|keyboardHidden|navigation|orientation|screenLayout|screenSize|smallestScreenSize|uiMode">'
           '<intent-filter><action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LAUNCHER"/></intent-filter></activity>')
    m = m.replace("</application>", "    " + act + NL + "    </application>")
m = re.sub(r'android:icon="[^"]*"', 'android:icon="@mipmap/gauntlet_launcher"', m, count=1)
m = re.sub(r'android:roundIcon="[^"]*"', 'android:roundIcon="@mipmap/gauntlet_launcher"', m, count=1)
m = re.sub(r'(<application[^>]*?)android:label="[^"]*"', lambda mm: mm.group(1) + 'android:label="Gauntlet"', m, count=1)
assert "com.gauntlet.sa.Start" in m and 'android:label="Gauntlet"' in m
wr(p, m)

# ---- long-press menu on the icon: which letters the game's button pictures show
NL = "\n"
def shortcut(sid, label, val):
    return ('  <shortcut android:shortcutId="' + sid + '" android:enabled="true" android:icon="@mipmap/gauntlet_launcher"'
            ' android:shortcutShortLabel="@string/' + label + '">' + NL +
            '    <intent android:action="android.intent.action.VIEW" android:targetPackage="com.gauntlet.odin"'
            ' android:targetClass="com.gauntlet.sa.Start">' + NL +
            '      <extra android:name="letters" android:value="' + val + '"/>' + NL +
            '    </intent>' + NL + '  </shortcut>' + NL)
os.makedirs(os.path.join(D, "res", "xml"), exist_ok=True)
wr(os.path.join(D, "res", "xml", "gauntlet_shortcuts.xml"),
   '<?xml version="1.0" encoding="utf-8"?>' + NL + '<shortcuts xmlns:android="http://schemas.android.com/apk/res/android">' + NL +
   shortcut("buttons_cycle", "gauntlet_buttons_cycle", "next").replace('android:name="letters"', 'android:name="buttons"') +
   shortcut("frame_rate", "gauntlet_frame_rate", "toggle").replace('android:name="letters"', 'android:name="rate"') +
   shortcut("fps_counter", "gauntlet_fps_counter", "toggle").replace('android:name="letters"', 'android:name="fps"') + '</shortcuts>' + NL)
p = os.path.join(D, "AndroidManifest.xml"); m = rd(p)
if "gauntlet_shortcuts" not in m:
    i = m.index('android:name="com.gauntlet.sa.Start"'); j = m.index("</intent-filter>", i) + len("</intent-filter>")
    m = m[:j] + '<meta-data android:name="android.app.shortcuts" android:resource="@xml/gauntlet_shortcuts"/>' + m[j:]
    wr(p, m)

p = os.path.join(D, "res", "values", "strings.xml"); s = rd(p)
s = re.sub(r'(<string name="app_name">)[^<]*(</string>)', r'\1Gauntlet\2', s)
s = s.replace("FPS counter on/off</string>", "FPS / performance overlay</string>")
if "gauntlet_perf_overlay" not in s:
    s = s.replace("</resources>", '    <string name="gauntlet_perf_overlay">Performance overlay on/off</string>' + NL + '</resources>')
if "gauntlet_fps_counter" not in s:
    s = s.replace("</resources>", '    <string name="gauntlet_fps_counter">FPS counter on/off</string>' + NL + '</resources>')
s = s.replace("Odin button letters</string>", "Odin buttons</string>").replace("Xbox button letters</string>", "Xbox buttons</string>")
if "gauntlet_picture" not in s:
    s = s.replace("</resources>", '    <string name="gauntlet_picture">Picture: 1080p / 900p</string>' + NL + '</resources>')
if "gauntlet_buttons_cycle" not in s:
    s = s.replace("</resources>", '    <string name="gauntlet_buttons_cycle">Buttons: Odin / PlayStation / Xbox</string>' + NL +
                  '    <string name="gauntlet_frame_rate">Frame rate: 60 / 120</string>' + NL + '</resources>')
if "gauntlet_ps_buttons" not in s:
    s = s.replace("</resources>", '    <string name="gauntlet_ps_buttons">PlayStation buttons</string>' + NL + '</resources>')
if "gauntlet_odin_letters" not in s:
    s = s.replace("</resources>", '    <string name="gauntlet_odin_letters">Odin button letters</string>' + NL +
                  '    <string name="gauntlet_xbox_letters">Xbox button letters</string>' + NL + '</resources>')
wr(p, s)

# ---- the Windows layer's own bits that must never show were patched by prepare_winlator.py:
for name, mark in (("PreloaderDialog.smali", "popfs: never shown"), ("GLRenderer.smali", "popfs: no pointer"), ("XServerDisplayActivity.smali", "popfs: no menu")):
    hit = [os.path.join(r, name) for r, _, f in os.walk(D) if name in f and "smali" in r]
    assert hit and mark in rd(hit[0]), name
print("prepared", n)
