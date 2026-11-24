package com.gauntlet.sa;

import android.app.Activity;
import android.content.Context;
import android.os.Build;
import android.view.Display;
import android.view.WindowManager;

import org.json.JSONObject;

/** Settings that differ between devices (graphics driver, frame rate, screen mode). */
final class Device {
    static String soc() {
        String s = Build.VERSION.SDK_INT >= 31 ? Build.SOC_MODEL : "";
        return ((s == null ? "" : s) + " " + Build.HARDWARE + " " + Build.BOARD + " " + Build.MODEL).toUpperCase();
    }

    /** Snapdragon 8 Gen 2 (AYN Odin 2 family): Adreno 740. */
    static boolean isGen2() {
        String s = soc();
        return s.contains("SM8550") || s.contains("QCS8550") || s.contains("KALAMA") || s.contains("ODIN2");
    }

    /** Snapdragon 8 Elite (AYN Odin 3): Adreno 830. Fast enough for 120 frames per second. */
    static boolean isElite() {
        String s = soc();
        return s.contains("SM8750") || s.contains("CQ8725") || s.contains(" SUN ") || s.contains("ODIN3");
    }

    /**
     * AYN Odin handhelds print their face buttons X top, A right, B bottom, Y left, and already send them to
     * games by position (the bottom button, printed B, is "A" for the game). So the buttons work as they should
     * without any change; only the game's on-screen prompts would show the wrong letters.
     */
    static boolean odinPrintedLayout() {
        String s = (Build.MANUFACTURER + " " + Build.MODEL + " " + Build.BRAND).toUpperCase();
        return s.contains("ODIN") || s.contains("AYN");
    }

    static float maxRefresh(Context c) {
        Display d = ((WindowManager) c.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay();
        float best = d.getRefreshRate();
        for (Display.Mode m : d.getSupportedModes()) best = Math.max(best, m.getRefreshRate());
        return best;
    }

    /**
     * The refresh rate the user has chosen for the screen (the device's own refresh rate setting / quick tile).
     * The game follows it: screen set to 60 Hz means the game runs at 60, set to 120 Hz it runs at 120.
     */
    static float chosenRefresh(Context c) {
        try {
            float peak = android.provider.Settings.System.getFloat(c.getContentResolver(), "peak_refresh_rate", 0f);
            if (peak > 1f) return Math.min(peak, maxRefresh(c));
        } catch (Exception ignored) { }
        return ((WindowManager) c.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRefreshRate();
    }

    private static android.content.SharedPreferences prefs(Context c) { return c.getSharedPreferences("gauntlet_settings", Context.MODE_PRIVATE); }

    /** 120 frames per second are on offer: fast enough chip, and the screen is set to 120 Hz. */
    static boolean canDo120(Context c) { return isElite() && chosenRefresh(c) >= 119f; }

    /** The game's frame rate limit: what the player picked on the start screen, 120 by default where possible. */
    static int frameCap(Context c) {
        if (!canDo120(c)) return 60;
        return prefs(c).getInt("fps", 60) >= 120 ? 120 : 60;   // 60: at 1080p the GPU manages ~70, and above 60 the picture shows only 40 new frames a second
    }
    /** Button pictures in the game: the letters printed on an AYN Odin (A right, B bottom, X top, Y left) or the
     *  game's own Xbox letters. Odin letters by default on Odin handhelds; switched from the icon's long-press menu. */
    static String buttons(Context c) {
        android.content.SharedPreferences p = prefs(c);
        String def = p.contains("odin_letters") ? (p.getBoolean("odin_letters", true) ? "odin" : "xbox") : (odinPrintedLayout() ? "odin" : "xbox");
        String b = p.getString("buttons", def);
        return b.equals("ps") || b.equals("odin") || b.equals("xbox") ? b : def;
    }
    /** "odin" (Odin's printed letters), "ps" (PlayStation symbols) or "xbox" (the game's own). */
    static void setButtons(Context c, String b) { prefs(c).edit().putString("buttons", b).apply(); }

    /** A small frame counter in the top-left corner of the game (DXVK's own), switched from the icon's long-press menu. */
    static boolean showFps(Context c) { return prefs(c).getBoolean("show_fps", false); }
    /** Performance overlay (icon menu): frame counter plus CPU/GPU load and temperatures. */
    static boolean perfOverlay(Context c) { return prefs(c).getBoolean("perf_overlay", false); }
    static void setPerfOverlay(Context c, boolean on) { prefs(c).edit().putBoolean("perf_overlay", on).apply(); }
    static void setShowFps(Context c, boolean on) { prefs(c).edit().putBoolean("show_fps", on).apply(); }

    /** Picture at 60 fps: full 1080 lines, or 900 lines scaled up (less GPU work and heat). Icon menu. */
    static boolean fullPicture(Context c) { return prefs(c).getBoolean("full_picture", false); }
    static void setFullPicture(Context c, boolean on) { prefs(c).edit().putBoolean("full_picture", on).apply(); }

    static void setFrameCap(Context c, int fps) { prefs(c).edit().putInt("fps", fps).apply(); }

    /** Picture quality as in the game's own settings program: 0 low, 1 medium, 2 high. */
    static int quality(Context c) { return Math.max(0, Math.min(2, prefs(c).getInt("quality", 2))); }
    static void setQuality(Context c, int q) { prefs(c).edit().putInt("quality", q).apply(); }

    /** Asks for the screen refresh rate that matches the game's frame rate while the game is shown: the highest one
     *  at 120, and a 60 Hz mode at 60. (At 60 frames on a 120 Hz screen each frame is shown for one or three screen
     *  refreshes now and then instead of two, which makes animations such as the main menu visibly uneven.) */
    static void useFastestScreenMode(Activity a) { useScreenMode(a, frameCap(a)); }

    /** Asks for the screen mode with this refresh rate (60, or the fastest one for 120). */
    static void useScreenMode(Activity a, final int cap) {
        try {
            // the screen's refresh rate follows the game's frame rate: at 60 a 120 Hz screen would show some frames for
            // one refresh and others for three (uneven motion); 60 Hz shows every frame for exactly one refresh

            Display d = a.getWindowManager().getDefaultDisplay();
            Display.Mode cur = d.getMode(), best = null;
            for (Display.Mode m : d.getSupportedModes()) {
                if (m.getPhysicalWidth() != cur.getPhysicalWidth() || m.getPhysicalHeight() != cur.getPhysicalHeight()) continue;
                if (cap >= 120) { if (best == null || m.getRefreshRate() > best.getRefreshRate() + 0.5f) best = m; }
                else if (Math.abs(m.getRefreshRate() - 60f) < 1f) best = m;
            }
            if (best == null) return;
            WindowManager.LayoutParams lp = a.getWindow().getAttributes();
            if (lp.preferredDisplayModeId != best.getModeId()) { lp.preferredDisplayModeId = best.getModeId(); a.getWindow().setAttributes(lp); }
        } catch (Exception ignored) { }
    }

    /** Name of the graphics driver that comes with the app for this device, or null to use the device's own. */
    static String bundledDriver() { return isElite() ? "turnip_t26" : null; }

    static void apply(JSONObject j) throws Exception {
        // Adreno 830 (Odin 3): the device's own driver now and then draws a frame of this game with half the
        // picture as a checkerboard. The open-source driver that comes with the app (Turnip T26) does not, and unlike
        // the first one tried (A8XX MR v20.5) it also draws all textures of normal play correctly.
        String drv = bundledDriver();
        if (drv != null) j.put("graphicsDriverConfig", "version=" + drv + ";blacklistedExtensions=;maxDeviceMemory=0;adrenotoolsTurnip=1;frameSync=Always");
    }
}
