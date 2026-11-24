package com.gauntlet.sa;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import java.io.File;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * One run of the game. The Windows layer's display screen runs the game; this class keeps the game's loading
 * picture over it until the game draws by itself, starts the game again if a start fails, and closes
 * everything when the game quits.
 * The game itself is watched as a process: once gauntlet.exe has run for a few seconds the picture is removed
 * (the game shows black while it loads), and when it has ended after that, everything is closed.
 */
final class Session implements Application.ActivityLifecycleCallbacks, android.view.SurfaceHolder.Callback {
    private Runnable rateSetter;
    private boolean wasHot;
    @Override public void surfaceCreated(android.view.SurfaceHolder h) { if (rateSetter != null) rateSetter.run(); }
    @Override public void surfaceChanged(android.view.SurfaceHolder h, int f, int w, int hh) { if (rateSetter != null) rateSetter.run(); }
    @Override public void surfaceDestroyed(android.view.SurfaceHolder h) { }
    static final String TAG = "Gauntlet";
    static final String DISPLAY = "com.winlator.cmod.XServerDisplayActivity";
    private static Session instance;

    private final Application app;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Start starter;
    private Activity display;
    private View cover;
    private boolean up, quitting, restarting;
    private int tries;
    private final Runnable watchdog = this::startFailed;
    private PerfOverlay perf;

    static synchronized Session get(Application app) {
        if (instance == null) { instance = new Session(app); app.registerActivityLifecycleCallbacks(instance); instance.listen(); }
        return instance;
    }
    private Session(Application app) { this.app = app; }

    void launch(Start from) {
        starter = from; up = false; seenSince = 0;
        Intent i = new Intent().setClassName(app, DISPLAY);
        i.putExtra("container_id", 1);
        i.putExtra("shortcut_path", Setup.shortcutFile(app).getPath());
        i.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        from.startActivity(i);
        from.overridePendingTransition(0, 0);
        ui.removeCallbacks(watchdog);
        ui.postDelayed(watchdog, 45000);
    }

    /**
     * The start screen came to the front again while a game was started earlier (the user returned to the app
     * and Android showed this screen instead of the game's): go back to the running game, or start it afresh
     * if its screen is gone.
     */
    void returned(Start from) {
        if (quitting || restarting) return;
        starter = from;
        if (display != null && !display.isFinishing() && !display.isDestroyed()) {
            Intent i = new Intent().setClassName(app, DISPLAY);
            i.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            try { from.startActivity(i); from.overridePendingTransition(0, 0); return; } catch (Exception ignored) { }
        }
        killChildren();
        restarting = true;
        ui.postDelayed(() -> { restarting = false; launch(from); }, 1500);
    }

    /** A game is running in this app right now. */
    boolean running() { return !quitting && display != null && !display.isFinishing() && !display.isDestroyed(); }

    /** A second start screen was opened while the game runs (the icon was tapped again): go back to the game. */
    void backToGame(Activity extra) {
        Intent i = new Intent().setClassName(app, DISPLAY);
        i.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        try { extra.startActivity(i); extra.overridePendingTransition(0, 0); } catch (Exception ignored) { }
        extra.finish();
    }

    // ---- the loading picture over the game's screen
    private void addCover(Activity a, boolean picture) {
        removeCover();
        try {
            ImageView v = new ImageView(a);
            v.setBackgroundColor(0xFF000000);
            int id = Setup.drawable(a, "gauntlet_splash");
            if (picture && id != 0) { v.setImageResource(id); v.setScaleType(ImageView.ScaleType.CENTER_CROP); }
            v.setClickable(true);
            ((ViewGroup) a.getWindow().getDecorView()).addView(v, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            v.bringToFront(); v.setElevation(1000f);
            cover = v;
        } catch (Exception e) { Log.w(TAG, "cover", e); }
    }
    private void removeCover() {
        if (cover != null && cover.getParent() instanceof ViewGroup) ((ViewGroup) cover.getParent()).removeView(cover);
        cover = null;
    }

    // ---- the game's process
    private static final String EXE = "gauntlet.exe";
    private long seenSince;          // uptime when the game's process was first seen in this run (0 = not yet)

    /** A process of this app whose command line names the game. */
    private static boolean gameProcess() {
        int uid = Process.myUid();
        File[] procs = new File("/proc").listFiles();
        if (procs == null) return false;
        for (File p : procs) {
            char c0 = p.getName().charAt(0);
            if (c0 < '0' || c0 > '9') continue;
            try {
                if (android.system.Os.stat(p.getPath()).st_uid != uid) continue;
                String comm = new String(java.nio.file.Files.readAllBytes(new File(p, "comm").toPath())).trim().toLowerCase();
                if (comm.equals(EXE)) return true;
            } catch (Exception ignored) { }
        }
        return false;
    }

    private void listen() {
        Thread t = new Thread(() -> {
            while (true) {
                try { Thread.sleep(500); } catch (InterruptedException e) { return; }
                final boolean alive = gameProcess();
                final long now = android.os.SystemClock.uptimeMillis();
                ui.post(() -> {
                    if (quitting || restarting || display == null) { if (!alive) seenSince = 0; return; }
                    if (alive) {
                        if (seenSince == 0) seenSince = now;
                        if (!up && now - seenSince > 6000) gameUp();
                    } else if (up) gameQuit();
                    else seenSince = 0;
                });
            }
        }, "gauntlet-watch");
        t.setDaemon(true); t.start();
    }

    private void gameUp() {
        Log.i(TAG, "game is drawing");
        up = true; tries = 0;
        ui.removeCallbacks(watchdog);
        final View v = cover;
        if (v != null) v.animate().alpha(0f).setDuration(180).withEndAction(() -> { if (cover == v) removeCover(); });
    }

    /** The game has quit: its Windows session closes itself now and must stay in front while it does. */
    private void gameQuit() {
        if (quitting) return;
        quitting = true;
        Log.i(TAG, "game has quit");
        ui.removeCallbacks(watchdog);
        if (display != null) addCover(display, false);
        ui.postDelayed(this::closeAll, 2500);
    }

    private void closeAll() {
        killChildren();
        try { if (starter != null) starter.finishAndRemoveTask(); } catch (Exception ignored) { }
        try { if (display != null) display.finishAndRemoveTask(); } catch (Exception ignored) { }
        ui.postDelayed(() -> Process.killProcess(Process.myPid()), 400);
    }

    /** No picture from the game in time (it closed during start): start it again, behind the loading picture. */
    private void startFailed() {
        if (up || quitting) return;
        Log.w(TAG, "the game did not come up, try " + (tries + 1));
        if (++tries > 2) { quitting = true; closeAll(); return; }
        killChildren();
        final Activity old = display;
        if (old != null) { try { old.finish(); } catch (Exception ignored) { } }
        restarting = true;
        ui.postDelayed(() -> { restarting = false; if (starter != null) launch(starter); }, 2500);
    }

    /** Ends every process of the Windows session (they all belong to this app). */
    static void killChildren() {
        int me = Process.myPid(), uid = Process.myUid();
        File[] procs = new File("/proc").listFiles();
        if (procs == null) return;
        for (File p : procs) {
            int pid;
            try { pid = Integer.parseInt(p.getName()); } catch (NumberFormatException e) { continue; }
            if (pid == me) continue;
            try {
                if (android.system.Os.stat(p.getPath()).st_uid != uid) continue;
                String cmd = new String(java.nio.file.Files.readAllBytes(new File(p, "cmdline").toPath()));
                if (cmd.startsWith(app().getPackageName())) continue;      // one of the app's own Android processes
                Process.killProcess(pid);
            } catch (Exception ignored) { }
        }
    }
    private static Application app() { return instance.app; }

    // ---- the display screen's life
    @Override public void onActivityCreated(Activity a, Bundle b) { }
    @Override public void onActivityStarted(Activity a) { }
    @Override public void onActivityResumed(Activity a) {
        if (!a.getClass().getName().equals(DISPLAY)) return;
        if (display != a) { display = a; if (!up) addCover(a, true); }
        Device.useFastestScreenMode(a);
        edgeStrips(a);
        pinFrameRate(a);
        if (Device.perfOverlay(a) && perf == null) { perf = new PerfOverlay(); perf.attach(a); }
    }
    /**
     * The Windows layer's picture of the game leaves the screen's bottom pixel row white (its drawing is one row off).
     * Two one-pixel black strips over the top and bottom rows of the game's screen hide it.
     */
    private void edgeStrips(Activity a) {
        try {
            ViewGroup root = (ViewGroup) a.getWindow().getDecorView();
            if (root.findViewWithTag("gauntlet_edge") != null) return;
            for (int gravity : new int[] {android.view.Gravity.TOP, android.view.Gravity.BOTTOM}) {
                View v = new View(a);
                v.setTag("gauntlet_edge"); v.setBackgroundColor(0xFF000000); v.setElevation(999f);
                android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1, gravity);
                root.addView(v, lp);
            }
        } catch (Exception e) { Log.w(TAG, "edge", e); }
    }

    /**
     * Tells Android the game's picture really runs at the chosen rate. Without it Android guesses a rate for the
     * game's surface from how its frames arrive, and at 120 it guessed 40 (a third), so only 40 frames were shown.
     */
    private void pinFrameRate(Activity a) {
        if (android.os.Build.VERSION.SDK_INT < 30) return;
        final int cap = Device.frameCap(a);
        final android.view.SurfaceView sv = findSurfaceView(a.getWindow().getDecorView());
        if (sv == null || sv.getTag() != null) return;
        sv.setTag("gauntlet_rate");
        final android.os.PowerManager pm = (android.os.PowerManager) a.getSystemService(android.content.Context.POWER_SERVICE);
        // When the device gets hot (thermal status "critical"), Android shows at most 60 frames a second. A game that
        // still asks for 120 then gets 40 uneven ones; asking for 60 instead gives an even 60 (every second frame).
        final Runnable set = () -> {
            try {
                boolean hot = cap > 60 && pm.getCurrentThermalStatus() >= android.os.PowerManager.THERMAL_STATUS_CRITICAL;
                float rate = hot ? 60f : cap;
                // (the 120 Hz screen mode cannot show 60 under Android's heat limit, only 40: so the 60 Hz mode is used)
                if (cap > 60 && display != null) Device.useScreenMode(display, hot ? 60 : cap);
                android.view.Surface su = sv.getHolder().getSurface();
                if (su != null && su.isValid()) su.setFrameRate(rate, android.view.Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE);
                if (hot != wasHot) { wasHot = hot; Log.i(TAG, hot ? "hot: showing 60 of the game's " + cap + " frames" : "cooled down: back to " + cap); }
            } catch (Exception e) { Log.w(TAG, "frame rate", e); }
        };
        if (cap > 60) pm.addThermalStatusListener(a.getMainExecutor(), status -> set.run());
        rateSetter = set;
        sv.getHolder().addCallback(this);
        set.run();
        ui.postDelayed(set, 3000);
    }
    private static android.view.SurfaceView findSurfaceView(View v) {
        if (v instanceof android.view.SurfaceView) return (android.view.SurfaceView) v;
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) {
            android.view.SurfaceView r = findSurfaceView(((ViewGroup) v).getChildAt(i));
            if (r != null) return r;
        }
        return null;
    }

    @Override public void onActivityPaused(Activity a) { }
    @Override public void onActivityStopped(Activity a) { }
    @Override public void onActivitySaveInstanceState(Activity a, Bundle b) { }
    @Override public void onActivityDestroyed(Activity a) {
        if (a == display) { display = null; cover = null; if (perf != null) { perf.detach(); perf = null; } }
        if (a == starter) starter = null;
    }
}
