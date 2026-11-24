# Building the APK

You only need this to change the app itself. To play, use the APK from Releases (see README.md).

## Requirements

- JDK 17+, Python 3.10+ with `zstandard` (`pip install zstandard`), Git Bash (on Windows)
- [apktool](https://apktool.org) 2.11+, Android SDK build-tools 34 and platform `android-34`
- [dex2jar](https://github.com/pxb1988/dex2jar), to make `android/jar/*.jar` from the Winlator dex files
  (compile-time classpath only)
- Winlator Cmod 13.1.1 APK (`Winlator-Cmod-v13.1.1.apk`)
- Components: box64 0.4.2 (bionic build), DXVK 2.7.1 (`system32/` + `syswow64/` tar.zst), Turnip T26 driver
  (adrenotools package: `meta.json` + `vulkan.purple.so`)

## Steps

```bash
cd android
java -jar apktool.jar d Winlator-Cmod-v13.1.1.apk -o dec
# compile-time classes for our Java code
d2j-dex2jar dec/../Winlator-Cmod-v13.1.1.apk -o jar/winlator.jar   # or one jar per classesN.dex as jar/classesN.jar
python prepare_winlator.py               # app id com.gauntlet.odin, hides Winlator's own screens
# add the components
cp box64-0.4.2.tzst dec/assets/box86_64/
cp dxvk-2.7.1.tzst  dec/assets/dxwrapper/
mkdir -p assets_public/driver && cp meta.json vulkan.purple.so assets_public/driver/
python make_public_art.py                # splash + icon (own artwork)
export BT=/path/to/build-tools/34.0.0 JAR=/path/to/platforms/android-34/android.jar APKTOOL=/path/to/apktool.jar
export KEYSTORE=/path/to/your.keystore KEYSTORE_PASS=...
bash build_sa.sh                         # -> Gauntlet-Odin3.apk
```

Notes:
- The app id must stay exactly 17 characters long (same as `com.winlator.cmod`), because Winlator's packed system
  files contain hard-wired `/data/data/<id>` paths; `patch_assets.py` rewrites them.
- Some Winlator Cmod resources are GIFs named `.png`; if aapt2 refuses them, rename those files to `.gif` (and their
  references) before `build_sa.sh`.
- `container_odin.json` is the Windows-layer configuration (Proton 9.0 x86_64, box64 0.4.2 PERFORMANCE preset,
  DXVK 2.7.1, alsa-reflector audio, Turnip). `shortcut_odin.desktop` starts the game through a small batch file
  so it runs from the game folder like GOG starts it.
- Test hooks in the app (all under `Android/data/com.gauntlet.odin/files/`): `env.txt` adds environment variables,
  `container.json` overrides container settings, `test.txt` turns rumble off.
