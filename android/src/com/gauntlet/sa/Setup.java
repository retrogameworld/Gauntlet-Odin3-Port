package com.gauntlet.sa;

import android.content.Context;
import android.graphics.Point;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.WindowManager;

import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.contents.ContentsManager;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.TarCompressorUtils;
import com.winlator.cmod.xenvironment.ImageFs;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Everything the app sets up by itself, without any of the Windows layer's own screens:
 * the system files (first start), the game's environment, and the game files from Gauntlet.pak.
 */
final class Setup {
    interface Progress { void at(String what, int percent); }

    static final int SYSTEM_VERSION = 24;              // the version the Windows layer expects of its system files
    static final String WINE = "proton-9.0-x86_64";
    static final int ENV_VERSION = 1;                  // raise to rebuild the environment settings on an app update

    /** A picture of ours by name (the resources keep the Windows layer's original package name). */
    static int drawable(Context c, String name) {
        int id = c.getResources().getIdentifier(name, "drawable", "com.winlator.cmod");
        return id != 0 ? id : c.getResources().getIdentifier(name, "drawable", c.getPackageName());
    }

    static File gameDir(Context c) { return new File(c.getDataDir(), "storage/gauntlet"); }
    static File containerDir(Context c) { return new File(ImageFs.find(c).getRootDir(), "home/xuser-1"); }
    static File shortcutFile(Context c) { return new File(containerDir(c), ".wine/drive_c/users/xuser/Desktop/Gauntlet.desktop"); }

    // ---------------------------------------------------------------- system files
    static boolean systemReady(Context c) {
        ImageFs fs = ImageFs.find(c);
        return fs.isValid() && fs.getVersion() >= SYSTEM_VERSION && new File(fs.getRootDir(), "opt/" + WINE + "/bin").isDirectory();
    }

    static boolean installSystem(Context c, Progress p) {
        ImageFs fs = ImageFs.find(c);
        File root = fs.getRootDir();
        if (root.isDirectory()) {
            File[] files = root.listFiles();
            if (files != null) for (File f : files) if (!(f.isDirectory() && f.getName().equals("home"))) FileUtils.delete(f);
        } else root.mkdirs();
        final long total = (long) (FileUtils.getSize(c, "imagefs.txz") * (100.0f / 24));
        final AtomicLong done = new AtomicLong();
        boolean ok = TarCompressorUtils.extract(TarCompressorUtils.Type.XZ, c, "imagefs.txz", root, (file, size) -> {
            if (size > 0) p.at("Preparing (first start only)", (int) Math.min(90, done.addAndGet(size) * 90 / Math.max(1, total)));
            return file;
        });
        if (!ok) return false;
        p.at("Preparing (first start only)", 92);
        File wine = new File(root, "opt/" + WINE);
        wine.mkdirs();
        if (!TarCompressorUtils.extract(TarCompressorUtils.Type.XZ, c, WINE + ".txz", wine)) return false;
        fs.createImgVersionFile(SYSTEM_VERSION);
        p.at("Preparing (first start only)", 100);
        return true;
    }

    // ---------------------------------------------------------------- the game's environment
    static boolean containerReady(Context c) {
        return new File(containerDir(c), ".container").isFile() && shortcutFile(c).isFile()
                && new File(containerDir(c), ".gauntlet_env_" + ENV_VERSION).isFile();
    }

    /** Screen size for the game: the device's own shape, at most 1080 lines high. */
    static int[] gameSize(Context c) {
        Display d = ((WindowManager) c.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay();
        Point s = new Point(); d.getRealSize(s);
        int w = Math.max(s.x, s.y), h = Math.min(s.x, s.y);
        if (Math.abs(w * 9 - h * 16) * 100 <= h * 16 * 3 || w * 9 > h * 16) { h = Math.min(h, 1080); w = h * 16 / 9; }   // 16:9 (wider screens get bars)
        else { int nh = Math.min(h, 1080); w = w * nh / h; h = nh; }
        // 120 frames a second: the GPU manages that at 720 lines, not at 1080
        if (Device.frameCap(c) >= 120 && h > 720) { w = w * 720 / h; h = 720; }
        return new int[] {w & ~1, h & ~1};
    }

    static String asset(Context c, String name) throws Exception {
        try (InputStream in = c.getAssets().open("gauntlet/" + name)) {
            ByteArrayOutputStream o = new ByteArrayOutputStream(); byte[] b = new byte[8192]; int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n);
            return new String(o.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    static JSONObject containerData(Context c) throws Exception {
        JSONObject j = new JSONObject(asset(c, "container.json"));
        int[] size = gameSize(c);
        int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
        StringBuilder cpus = new StringBuilder();
        for (int i = 0; i < cores; i++) cpus.append(i == 0 ? "" : ",").append(i);
        j.put("name", "Gauntlet");
        j.put("screenSize", size[0] + "x" + size[1]);
        j.put("cpuList", cpus.toString());
        j.put("drives", "D:" + c.getDataDir().getPath() + "/storage" + "E:/data/data/" + c.getPackageName() + "/storage");
        j.put("wineVersion", WINE);
        j.put("extraData", new JSONObject());
        Device.apply(j);
        // for testing: extra settings from Android/data/<app>/files/env.txt, one line of NAME=value pairs
        File ext = c.getExternalFilesDir(null);
        File extra = ext == null ? null : new File(ext, "env.txt");
        if (extra != null && extra.isFile()) { String e = FileUtils.readString(extra); if (e != null && !e.trim().isEmpty()) j.put("envVars", j.getString("envVars") + " " + e.trim()); }
        // for testing: environment settings from Android/data/<app>/files/container.json replace the built-in ones
        File over = ext == null ? null : new File(ext, "container.json");
        if (over != null && over.isFile()) {
            String t = FileUtils.readString(over);
            if (t != null) { JSONObject o = new JSONObject(t); for (java.util.Iterator<String> it = o.keys(); it.hasNext();) { String k = it.next(); j.put(k, o.get(k)); } }
        }
        return j;
    }

    static boolean createContainer(Context c) throws Exception {
        installDriver(c);
        File dir = containerDir(c);
        if (!new File(dir, ".container").isFile()) {
            FileUtils.delete(dir);
            final JSONObject data = containerData(c);
            final CountDownLatch latch = new CountDownLatch(1);
            final Container[] made = new Container[1];
            new Handler(Looper.getMainLooper()).post(() ->
                    new ContainerManager(c).createContainerAsync(data, new ContentsManager(c), (Container k) -> { made[0] = k; latch.countDown(); }));
            latch.await();
            if (made[0] == null || !new File(dir, ".container").isFile()) return false;
            File cf = new File(dir, ".container");
            JSONObject saved = new JSONObject(FileUtils.readString(cf));
            saved.put("envVars", data.getString("envVars"));
            FileUtils.writeString(cf, saved.toString());
        } else {
            // app update: refresh the environment's settings, keep the Windows files and the saves
            File f = new File(dir, ".container");
            JSONObject old = new JSONObject(FileUtils.readString(f)), data = containerData(c);
            data.put("id", old.optInt("id", 1));
            if (old.has("extraData")) data.put("extraData", old.get("extraData"));
            FileUtils.writeString(f, data.toString());
        }
        noTaskbar(dir);
        File sc = shortcutFile(c);
        sc.getParentFile().mkdirs();
        FileUtils.writeString(sc, asset(c, "shortcut.desktop").replace("@PKG@", c.getPackageName()));
        new File(dir, ".gauntlet_env_" + ENV_VERSION).createNewFile();
        return true;
    }

    /**
     * The Windows desktop ("shell") keeps a taskbar that stays visible as a one-pixel white line along the bottom
     * edge over the full-screen game. Wine's explorer leaves the taskbar out when the desktop's EnableShell is 0.
     */
    static void noTaskbar(File dir) {
        File userReg = new File(dir, ".wine/user.reg");
        String cur = FileUtils.readString(userReg);
        if (cur == null || cur.contains("[Software\\\\Wine\\\\Explorer\\\\Desktops\\\\shell]")) return;
        FileUtils.writeString(userReg, cur + (cur.endsWith("\n") ? "" : "\n")
                + "\n[Software\\\\Wine\\\\Explorer\\\\Desktops\\\\shell] 1795400000\n\"EnableShell\"=dword:00000000\n");
    }

    /** The game itself in borderless full screen (a window covering the screen) rather than exclusive full screen. */
    static void applyGameSettings(Context c) {
        File f = new File(containerDir(c), ".wine/drive_c/users/xuser/AppData/Roaming/Arrowhead/Gauntlet/user_settings.config");
        String s = FileUtils.readString(f);
        if (s == null) return;
        int[] size = gameSize(c);
        String t = s.replaceAll("(?m)^borderless_fullscreen = \\w+", "borderless_fullscreen = true")
                // the game's own "120 Hz" option must match the app's frame rate, or it draws 120 on a 60 Hz screen (uneven)
                .replaceAll("(?m)^throttle_120 = \\w+", "throttle_120 = " + (Device.frameCap(c) >= 120));
        // the resolution is the player's choice in the game's own Settings (the Windows screen offers all of them);
        // only the 120 mode sets its 720 lines
        if (Device.frameCap(c) >= 120) t = t.replaceAll("(?s)screen_resolution = \\[.*?\\]", "screen_resolution = [\n\t" + size[0] + "\n\t" + size[1] + "\n]");
        if (!t.equals(s)) FileUtils.writeString(f, t);
    }

    // ---------------------------------------------------------------- controller
    /**
     * The Windows layer only passes a controller on that was assigned to "player 1" in its own settings
     * screen, which this app does not have. So the assignment is made here: the device's built-in controls
     * if there are any, otherwise the first connected controller. A still valid earlier choice is kept.
     */
    static String assignController(Context c) {
        android.content.SharedPreferences sp = android.preference.PreferenceManager.getDefaultSharedPreferences(c);
        sp.edit().putBoolean("vibrate_slot_0", !testRun(c)).commit();          // rumble, but never in test runs
        String cur = sp.getString("controller_slot_0", null), pick = null, pickName = null, curName = null;
        int pickScore = -1, curScore = -1;
        for (int id : android.view.InputDevice.getDeviceIds()) {
            android.view.InputDevice d = android.view.InputDevice.getDevice(id);
            if (d == null || d.isVirtual() || !com.winlator.cmod.inputcontrols.ControllerManager.isGameController(d)) continue;
            String ident = com.winlator.cmod.inputcontrols.ControllerManager.getDeviceIdentifier(d);
            if (ident == null) continue;
            // a real pad has sticks; some handhelds also list a few extra keys as a "controller" of their own
            boolean sticks = d.getMotionRange(android.view.MotionEvent.AXIS_X, android.view.InputDevice.SOURCE_JOYSTICK) != null
                    && d.getMotionRange(android.view.MotionEvent.AXIS_Z, android.view.InputDevice.SOURCE_JOYSTICK) != null;
            int score = (sticks ? 10 : 0) + ((d.getSources() & android.view.InputDevice.SOURCE_GAMEPAD) == android.view.InputDevice.SOURCE_GAMEPAD ? 2 : 0);
            if (ident.equals(cur)) { curScore = score; curName = d.getName(); }
            if (score > pickScore) { pick = ident; pickName = d.getName(); pickScore = score; }
        }
        if (curScore >= 10 || (curScore >= 0 && curScore >= pickScore)) return curName;   // the saved choice is connected and good
        if (pick == null) return null;
        sp.edit().putString("controller_slot_0", pick).putBoolean("enabled_slot_0", true).commit();
        return pickName;
    }

    // ---------------------------------------------------------------- graphics driver
    /** Puts the graphics driver that comes with the app for this device where the Windows layer looks for drivers. */
    static void installDriver(Context c) throws Exception {
        String drv = Device.bundledDriver();
        if (drv == null) return;
        File dir = new File(c.getFilesDir(), "imagefs/contents/adrenotools/" + drv);
        File[] others = dir.getParentFile().listFiles();                 // drivers of earlier app versions
        if (others != null) for (File o : others) if (o.isDirectory() && !o.getName().equals(drv) && !o.getName().equals("temp")) FileUtils.delete(o);
        String[] files = c.getAssets().list("gauntlet/driver");
        if (files == null) return;
        dir.mkdirs();
        for (String name : files) {
            File dst = new File(dir, name);
            long want;
            try (android.content.res.AssetFileDescriptor fd = c.getAssets().openFd("gauntlet/driver/" + name)) { want = fd.getLength(); }
            catch (Exception e) { want = -1; }                      // stored compressed: size not known this way
            if (dst.isFile() && (want < 0 ? dst.length() > 0 : dst.length() == want)) continue;
            File tmp = new File(dir, name + ".new");
            try (InputStream in = c.getAssets().open("gauntlet/driver/" + name); OutputStream o = new FileOutputStream(tmp)) {
                byte[] b = new byte[1 << 16]; int n;
                while ((n = in.read(b)) > 0) o.write(b, 0, n);
            }
            dst.delete();
            if (!tmp.renameTo(dst)) throw new java.io.IOException("could not place " + name);
        }
    }

    // ---------------------------------------------------------------- frame rate, test runs
    /** Test runs (Android/data/<app>/files/test.txt exists): no controller rumble. */
    static boolean testRun(Context c) {
        File ext = c.getExternalFilesDir(null);
        return ext != null && new File(ext, "test.txt").isFile();
    }

    static final String EXE = "binaries_x86/gauntlet.exe";
    /** Where the GOG gauntlet.exe (32-bit) reads the frame rate of its "throttle" time-step policy from the game's
     *  script (a call returning the script's number, 60): replaced by "mov eax, cap" for other limits. */
    static final int THROTTLE_AT = 0xBADFE;
    static final byte[] THROTTLE_ORIG = {(byte) 0xE8, 0x7D, (byte) 0xCA, 0x0C, 0x00};

    /**
     * The game's frame rate limit. The game limits itself to 60 (its own throttle); for other limits the number is
     * patched into the exe. Also removes the test controller file outside test runs.
     */
    static void applyFrameCap(Context c) throws Exception {
        File f = new File(containerDir(c), ".container");
        if (f.isFile()) {                                       // earlier builds limited through DXVK: not needed
            JSONObject j = new JSONObject(FileUtils.readString(f));
            String env = j.optString("envVars", "").replaceAll("(^| )DXVK_FRAME_RATE=[^ ]*", "")
                    .replaceAll("(^| )DXVK_HUD=[^ ]*", "").trim();
            env = env + " DXVK_HUD=" + (Device.showFps(c) || Device.perfOverlay(c) ? "fps" : "0");       // the frame counter (icon menu)
            if (!env.equals(j.optString("envVars", ""))) { j.put("envVars", env); FileUtils.writeString(f, j.toString()); }
        }
        // GOG starts the game from its own folder (binaries_x86/gauntlet.exe -bundle-dir contents -ini settings, with the
        // game folder as working folder); the Windows layer would start it in binaries_x86, so a small batch file does it
        if (gameDir(c).isDirectory()) FileUtils.writeString(new File(gameDir(c), "gauntlet.bat"),
                "@echo off\r\ncd /d E:\\gauntlet\r\nE:\\gauntlet\\binaries_x86\\gauntlet.exe -bundle-dir contents -ini settings\r\n");
        if (!testRun(c)) new File(gameDir(c), "binaries_x86/xinput1_3.dll").delete();
        int cap = Device.frameCap(c);
        byte[] want = cap == 60 ? THROTTLE_ORIG : new byte[] {(byte) 0xB8, (byte) cap, (byte) (cap >> 8), 0, 0};
        try (java.io.RandomAccessFile r = new java.io.RandomAccessFile(new File(gameDir(c), EXE), "rw")) {
            if (r.length() < THROTTLE_AT + 5) return;
            byte[] have = new byte[5]; r.seek(THROTTLE_AT); r.readFully(have);
            boolean known = java.util.Arrays.equals(have, THROTTLE_ORIG) || (have[0] == (byte) 0xB8 && have[3] == 0 && have[4] == 0);
            if (known && !java.util.Arrays.equals(have, want)) { r.seek(THROTTLE_AT); r.write(want); }
        }
    }

    /**
     * Puts the chosen button-letter version of the two patched game bundles in place (from the app: odin_buttons/ or
     * xbox_buttons/; both also carry the main menu without the online entries). Only copies when the choice changed.
     */
    /**
     * The patched game files (button sets, menu, PlayStation pictures) are made from the player's own game copy by the
     * PC tool and arrive in the pak under extras/; builds that carry them in the app itself use those instead.
     */
    static InputStream variant(Context c, String rel) throws Exception {
        File f = new File(gameDir(c), "extras/" + rel);
        return f.isFile() ? new java.io.FileInputStream(f) : c.getAssets().open("gauntlet/" + rel);
    }
    static String[] variantList(Context c, String dir) throws Exception {
        File d = new File(gameDir(c), "extras/" + dir);
        if (d.isDirectory()) return d.list();
        try { return c.getAssets().list("gauntlet/" + dir); } catch (Exception e) { return null; }
    }

    static void applyButtonLetters(Context c) throws Exception {
        if (variantList(c, "odin_buttons") == null || variantList(c, "odin_buttons").length == 0) return;   // a pak without extras
        String buttons = Device.buttons(c);
        String set = buttons.equals("odin") ? "odin_buttons" : "xbox_buttons";
        File mark = new File(gameDir(c), ".button_letters");
        String want = buttons + "-" + asset(c, "version.txt").trim();
        if (mark.isFile() && want.equals(FileUtils.readString(mark).trim())) return;
        String[] files = variantList(c, set);
        if (files == null) return;
        for (String name : files) {
            File dst = new File(gameDir(c), "contents/" + name), tmp = new File(dst.getPath() + ".new");
            try (InputStream in = variant(c, set + "/" + name); OutputStream o = new FileOutputStream(tmp)) {
                byte[] b = new byte[1 << 16]; int n;
                while ((n = in.read(b)) > 0) o.write(b, 0, n);
            }
            dst.delete();
            if (!tmp.renameTo(dst)) throw new java.io.IOException("could not place " + name);
        }
        // the button pictures: PlayStation ones drawn for this app, or the game's own (Xbox) ones
        replaceChunks(new File(gameDir(c), "contents/ec181385e7000cf1"),
                "ps_textures/" + (buttons.equals("ps") ? "chunks" : "orig_chunks"), c);
        FileUtils.writeString(mark, want);
    }

    /**
     * A game bundle is a 12-byte header and then 64 KB pieces, each "u32 length, bytes". The chunk file in the app
     * (u32 count, then per piece u32 index, u32 length, bytes) replaces those pieces; the rest is copied as it is.
     */
    static void replaceChunks(File bundle, String asset, Context c) throws Exception {
        java.util.Map<Integer, byte[]> rep = new java.util.HashMap<>();
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(variant(c, asset)))) {
            int n = Integer.reverseBytes(in.readInt());
            for (int i = 0; i < n; i++) {
                int idx = Integer.reverseBytes(in.readInt()), len = Integer.reverseBytes(in.readInt());
                byte[] b = new byte[len]; in.readFully(b); rep.put(idx, b);
            }
        }
        File tmp = new File(bundle.getPath() + ".new");
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new java.io.FileInputStream(bundle), 1 << 20));
             java.io.DataOutputStream o = new java.io.DataOutputStream(new BufferedOutputStream(new FileOutputStream(tmp), 1 << 20))) {
            byte[] head = new byte[12]; in.readFully(head); o.write(head);
            byte[] buf = new byte[1 << 17];
            for (int idx = 0; ; idx++) {
                int len;
                try { len = Integer.reverseBytes(in.readInt()); } catch (java.io.EOFException e) { break; }
                in.readFully(buf, 0, len);
                byte[] r = rep.get(idx);
                if (r != null) { o.writeInt(Integer.reverseBytes(r.length)); o.write(r); }
                else { o.writeInt(Integer.reverseBytes(len)); o.write(buf, 0, len); }
            }
        }
        bundle.delete();
        if (!tmp.renameTo(bundle)) throw new java.io.IOException("could not place " + bundle.getName());
    }

    // ---------------------------------------------------------------- the game files (Gauntlet.pak)
    static boolean gameReady(Context c) { return new File(gameDir(c), ".pak_ok").isFile() && new File(gameDir(c), EXE).isFile(); }

    /**
     * Unpacks a Gauntlet.pak: "GNTLT1\0\0", uint32 count, then per file uint16 name length, name, uint64 size, bytes; "END!".
     * Returns null when it worked, otherwise what went wrong.
     */
    static String importPak(Context c, InputStream raw, long totalBytes, Progress p) {
        File dir = gameDir(c);
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(raw, 1 << 20))) {
            byte[] magic = new byte[8]; in.readFully(magic);
            if (!new String(magic, StandardCharsets.US_ASCII).equals("GNTLT1\0\0")) return "This is not a Gauntlet.pak file.";
            int count = Integer.reverseBytes(in.readInt());
            if (count <= 0 || count > 100000) return "The pak file is damaged.";
            long need = totalBytes > 0 ? totalBytes : 1700L * 1000 * 1000;
            if (c.getDataDir().getUsableSpace() < need + (300L << 20)) return "Not enough free space (about " + (need + (300L << 20)) / 1000000000L + " GB needed).";
            dir.mkdirs();
            boolean game = false;                        // this pak holds the game itself (not only add-on files such as extra files)
            byte[] buf = new byte[1 << 20]; long done = 0;
            for (int i = 0; i < count; i++) {
                int nl = Short.reverseBytes(in.readShort()) & 0xFFFF;
                byte[] nb = new byte[nl]; in.readFully(nb);
                String name = new String(nb, StandardCharsets.UTF_8);
                long size = Long.reverseBytes(in.readLong());
                if (name.contains("..") || name.startsWith("/") || name.contains("\\") || size < 0) return "The pak file is damaged.";
                if (name.equals(EXE) && !game) {                // the game itself: replaces any earlier copy completely
                    game = true; FileUtils.delete(dir); dir.mkdirs();
                }
                File out = new File(dir, name);
                File parent = out.getParentFile(); if (parent != null) parent.mkdirs();
                try (OutputStream o = new BufferedOutputStream(new FileOutputStream(out), 1 << 20)) {
                    long left = size;
                    while (left > 0) {
                        int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                        if (n < 0) return "The pak file is incomplete.";
                        o.write(buf, 0, n); left -= n; done += n;
                        if (totalBytes > 0) p.at("Importing", (int) (done * 100 / totalBytes));
                    }
                }
            }
            byte[] end = new byte[4]; in.readFully(end);
            if (!new String(end, StandardCharsets.US_ASCII).equals("END!")) return "The pak file is incomplete.";
            if (game) new File(dir, ".pak_ok").createNewFile();
            return null;
        } catch (Exception e) {
            return "Import failed: " + e.getMessage();
        }
    }
}
