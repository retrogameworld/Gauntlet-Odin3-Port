#!/bin/bash
# Builds Gauntlet-Odin3.apk from the prepared Winlator tree (see BUILDING.md). Run from Git Bash on Windows or any bash.
#   BT       = Android build-tools 34 folder (lib/d8.jar, zipalign, lib/apksigner.jar)
#   JAR      = platforms/android-34/android.jar
#   APKTOOL  = apktool.jar (2.11+)
#   KEYSTORE / KEYSTORE_PASS = your own signing key
#   "quick" as first argument skips the resource rebuild (code-only changes)
set -e
cd "$(dirname "$0")"
: "${BT:?set BT}" "${JAR:?set JAR}" "${APKTOOL:?set APKTOOL}" "${KEYSTORE:?set KEYSTORE}" "${KEYSTORE_PASS:?set KEYSTORE_PASS}"
python prep_sa.py
rm -rf out_cls out_dex && mkdir -p out_cls out_dex
CP="$JAR;$(ls jar/*.jar | tr '\n' ';')"          # jar/ = the Winlator classes (dex2jar of the decoded APK), for compiling only
javac -source 8 -target 8 -nowarn -cp "$CP" -d out_cls src/com/gauntlet/sa/*.java
java -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 --lib "$JAR" --classpath jar/classes10.jar --classpath jar/classes14.jar \
     --min-api 26 --output out_dex $(find out_cls -name '*.class')
if [ "$1" != "quick" ] || [ ! -f out_unsigned.apk ]; then java -jar "$APKTOOL" b dec -o out_unsigned.apk > build_log.txt 2>&1; fi
cp out_unsigned.apk out_work.apk
python - <<'PY'
import zipfile
z = zipfile.ZipFile("out_work.apk", "a", zipfile.ZIP_DEFLATED)
names = [n for n in z.namelist() if n.startswith("classes") and n.endswith(".dex")]
nxt = "classes%d.dex" % (len(names) + 1)
z.write("out_dex/classes.dex", nxt); z.close(); print("added", nxt)
PY
"$BT/zipalign" -f -p 4 out_work.apk out_aligned.apk
java -jar "$BT/lib/apksigner.jar" sign --ks "$KEYSTORE" --ks-pass "pass:$KEYSTORE_PASS" --out Gauntlet-Odin3.apk out_aligned.apk
rm -f out_work.apk out_aligned.apk
ls -la Gauntlet-Odin3.apk
