# Gauntlet Slayer Edition: AYN Odin 3 port

An unofficial, one-icon Android app that runs the **GOG PC version of Gauntlet Slayer Edition** on the
**AYN Odin 3** (Snapdragon 8 Elite). No setup screens, no Windows desktop: tap the icon and the game starts,
with the Odin's built-in controls.

> **You need your own copy of the game from GOG.** This project contains no game files. The PC tool below reads
> your installed GOG game and turns it into a pak file for the Odin.

## What you get

- Starts straight into the game, 1080p, steady 60 fps (120 fps mode available, see below)
- Odin controls work out of the box (the built-in pad is assigned automatically)
- Main menu cleaned up for offline play: the online entries (online Start Game, Quick Join, Leaderboards) are
  removed, **Local Game** is renamed **Start Game** and selected first
- Button prompts that match your pad, switchable at any time:
  - **Odin** - the letters printed on the Odin (A right, B bottom, X top, Y left)
  - **PlayStation** - ✕ ○ □ △ and L1/R1/L2/R2 (drawn for this project)
  - **Xbox** - the game's own prompts
- Graphics fixes: newer DXVK (2.7.1) fixes broken floor lighting in later levels; Turnip GPU driver
- FPS counter and a performance overlay (CPU / GPU load, clock, temperatures)

## Install

**On your PC** (Windows, Python 3.10+):

1. Install the game from GOG (Gauntlet Slayer Edition). Default folder: `C:\Games\Gauntlet`.
2. Install Python and Pillow: `pip install pillow`
3. Build the pak from your game folder:
   ```
   cd tools
   python make_pak.py "C:\Games\Gauntlet"
   ```
   This creates `Gauntlet.pak` (about 1.9 GB) in the current folder. Your game folder is not changed.

**On the Odin 3:**

4. Download `Gauntlet-Odin3.apk` from the [Releases](../../releases) page and install it.
5. Open the app once, then copy `Gauntlet.pak` to `Android/data/com.gauntlet.odin/files/` on the Odin
   (or open the app and pick the pak with the **Choose Gauntlet.pak** button).
6. Start the app. The first start prepares the Windows layer and imports the pak (a few minutes); the pak is
   deleted afterwards. From then on the icon goes straight into the game.

Your saves stay inside the app. Installing a newer APK over the old one keeps the game and the saves.

## Options

Press and hold the Gauntlet icon:

| Entry | What it does |
|---|---|
| **Buttons: Odin / PlayStation / Xbox** | each tap switches to the next prompt style |
| **Frame rate: 60 / 120** | 120 fps (drawn at 720p) — see the note on heat below |
| **FPS / performance overlay** | each tap: FPS counter → FPS + CPU/GPU/temperatures → off |

A changed option applies from the next start of the game.

**Heat and smoothness.** The game is translated from x86/DirectX to ARM/Vulkan, which keeps roughly two CPU cores
busy. In *High Performance* mode the Odin holds every core at full clock, which gets hot; *Balanced* holds 60 fps
almost perfectly and runs cooler. At 120 fps the Odin reaches Android's heat limit after a minute or two; from then
on Android only allows 60, and the app switches to an even 60 instead of an uneven 40.

## How it works

The app is built on **[Winlator Cmod](https://github.com/coffincolors/winlator) 13.1.1** (MIT), with its own start
screen in front of it:

- **Windows layer:** Proton 9.0 (Wine) with **box64 0.4.2** running the 32-bit game (WoW64), **DXVK 2.7.1**
  translating Direct3D 11 to Vulkan, and the **Turnip T26** open-source Adreno driver.
- **App shell** (`android/src/com/gauntlet/sa`): first-start setup without any Winlator screens, pak import,
  controller assignment, a cover picture until the game draws, quitting cleanly when the game exits, edge-to-edge
  picture, the icon menu options, and the performance overlay.
- **Frame pacing:** the app tells Android the game's exact frame rate (`Surface.setFrameRate`) and keeps the screen
  mode matched to it. Without this Android guessed a lower rate and showed uneven frames, especially when hot.
- **Game patches** (`tools/`, applied to copies of three of your game's data files when you build the pak):
  - The game's scripts are LuaJIT bytecode inside Bitsquid bundles (64 KB zlib chunks). `bdl.py` unpacks bundles,
    `ljdis.py` reads LuaJIT 2.0 bytecode, and `patch_menu.py` rewrites `lua/menu/screen_main_menu.lua`. It turns the
    online buttons' `table.insert` calls into no-ops, makes Local Game the default selection, and points its label at
    the game's own "Start Game" text.
  - Button prompts come from `get_visual_for_button` in `lua/components/interactor_component.lua`. For the Odin set,
    a PS4-only code path there is replaced by a small lookup that swaps a↔b and x↔y (only the pictures change, not
    what the buttons do).
  - The PlayStation set: the `hud_button_*` pictures are small DXT5 textures in a GUI bundle. `ps_buttons.py` draws
    replacements of the same size, and `make_ps.py` stores only the changed 64 KB chunks, so the app can switch sets
    by swapping chunks.
- **Pak format:** `GNTLT1\0\0`, file count, then name/size/bytes per file, `END!`. The app unpacks it into its own
  storage, and the extras (`extras/odin_buttons`, `xbox_buttons`, `ps_textures`) feed the button-style switch.

Building the APK yourself: see [BUILDING.md](BUILDING.md).

## Credits and licences

- This project's own code and the PlayStation button art: MIT (see `LICENSE`).
- Winlator / Winlator Cmod — MIT. Wine / Proton — LGPL 2.1 (sources: winehq.org, github.com/ValveSoftware/Proton).
  box64 — MIT. DXVK — zlib. Mesa Turnip — MIT. See `THIRD_PARTY.md`.
- Gauntlet is © Warner Bros. Interactive Entertainment; developed by Arrowhead Game Studios. This project is not
  affiliated with or endorsed by them, and contains none of their files — you need your own GOG copy.

Tested on an AYN Odin 3 (Android 15) with the GOG release of Gauntlet Slayer Edition.
