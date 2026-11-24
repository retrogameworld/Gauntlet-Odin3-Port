"""Builds Gauntlet.pak for the Odin app from YOUR OWN GOG copy of Gauntlet Slayer Edition.

    python make_pak.py "C:\\Games\\Gauntlet" [output folder]

Nothing from the game is downloaded or shipped with this project: this script reads your installed GOG game,
applies the port's patches to copies of three of its data files, and packs everything into one file to copy to
the Odin. Your game folder is not changed.

What the patches do (see README.md):
  * main menu: the online entries (Start Game online, Quick Join, Leaderboards) are removed, Local Game is renamed
    "Start Game" and selected first;
  * button prompts: three sets, switched from the app's icon menu - Odin letters (A right, B bottom, X top, Y left),
    PlayStation symbols (drawn by this project), and the game's own Xbox letters.

Pak format: "GNTLT1\\0\\0", uint32 file count, then per file: uint16 name length, name (UTF-8, '/'), uint64 size,
bytes; "END!". The exe comes first (the app replaces any earlier game copy when it sees it).
"""
import os, sys, struct, subprocess, shutil

HERE = os.path.dirname(os.path.abspath(__file__))
GAME = sys.argv[1] if len(sys.argv) > 1 else r"C:\Games\Gauntlet"
OUTDIR = sys.argv[2] if len(sys.argv) > 2 else os.getcwd()
BUILD = os.path.join(HERE, "build")
EXE = "binaries_x86/gauntlet.exe"
NEEDED = ["binaries_x86/gauntlet.exe", "contents/4f2d313d15f129af", "contents/e4e607d799ef7acc", "contents/ec181385e7000cf1"]


def tree(src, dst):
    for root, _, files in os.walk(src):
        for f in sorted(files):
            p = os.path.join(root, f)
            yield dst + os.path.relpath(p, src).replace("\\", "/"), p


def main():
    for n in NEEDED:
        if not os.path.isfile(os.path.join(GAME, n)):
            sys.exit("Not a GOG Gauntlet Slayer Edition folder (missing %s): %s" % (n, GAME))
    print("Game:", GAME)
    shutil.rmtree(BUILD, ignore_errors=True)
    env = dict(os.environ, GAUNTLET_GAME=GAME, GAUNTLET_BUILD=BUILD)
    print("Patching the main menu and the button prompts ...")
    subprocess.check_call([sys.executable, os.path.join(HERE, "patch_menu.py")], env=env)
    print("Drawing the PlayStation button pictures ...")
    subprocess.check_call([sys.executable, os.path.join(HERE, "make_ps.py")], env=env)
    # the Xbox set uses the game's own join prompt
    shutil.copy(os.path.join(GAME, "contents", "e4e607d799ef7acc"), os.path.join(BUILD, "xbox_buttons", "e4e607d799ef7acc"))

    items = list(tree(os.path.join(GAME, "binaries_x86"), "binaries_x86/"))
    for n, p in tree(os.path.join(GAME, "contents"), "contents/"):
        alt = os.path.join(BUILD, "xbox_buttons", n[len("contents/"):])           # menu patch by default
        items.append((n, alt if os.path.isfile(alt) else p))
    items += [(n, os.path.join(GAME, n)) for n in os.listdir(GAME) if n.startswith("goggame-") and n.endswith(".info")]
    for sub in ("odin_buttons", "xbox_buttons", "ps_textures"):
        items += list(tree(os.path.join(BUILD, sub), "extras/" + sub + "/"))
    items = [(n, p) for n, p in items if os.path.isfile(p) and not n.endswith(".mdmp")]
    items.sort(key=lambda it: it[0] != EXE)

    out = os.path.join(OUTDIR, "Gauntlet.pak")
    total = sum(os.path.getsize(p) for _, p in items)
    print("Packing %d files, %.2f GB -> %s" % (len(items), total / 1e9, out))
    done = 0
    with open(out + ".tmp", "wb") as o:
        o.write(b"GNTLT1\0\0" + struct.pack("<I", len(items)))
        for name, p in items:
            nb = name.encode("utf-8")
            o.write(struct.pack("<H", len(nb)) + nb + struct.pack("<Q", os.path.getsize(p)))
            with open(p, "rb") as f:
                while True:
                    b = f.read(1 << 22)
                    if not b:
                        break
                    o.write(b); done += len(b)
            print("\r  %3d%%" % (done * 100 // max(1, total)), end="", flush=True)
        o.write(b"END!")
    os.replace(out + ".tmp", out)
    shutil.rmtree(BUILD, ignore_errors=True)
    print("\nDone: %s (%.2f GB). Copy it to the Odin (see README.md)." % (out, os.path.getsize(out) / 1e9))


if __name__ == "__main__":
    main()
