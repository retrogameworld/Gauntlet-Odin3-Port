package com.gauntlet.sa;

import android.app.Activity;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.io.File;
import java.nio.file.Files;

/**
 * Performance overlay (icon menu): CPU use of the game, GPU load and clock, CPU / GPU / battery temperatures,
 * in the top-right corner, once a second. The frame counter itself is the graphics layer's (top-left).
 */
final class PerfOverlay {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView view;
    private Thread thread;
    private volatile boolean running;
    private final int cores = Math.max(1, Runtime.getRuntime().availableProcessors());

    void attach(Activity a) {
        detach();
        view = new TextView(a);
        view.setTextColor(Color.WHITE);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        view.setTypeface(android.graphics.Typeface.MONOSPACE);
        view.setBackgroundColor(0x99000000);
        int pad = (int) (a.getResources().getDisplayMetrics().density * 6);
        view.setPadding(pad, pad / 2, pad, pad / 2);
        view.setElevation(998f);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        ((ViewGroup) a.getWindow().getDecorView()).addView(view, lp);
        running = true;
        thread = new Thread(this::loop, "perf-overlay");
        thread.setDaemon(true);
        thread.start();
    }

    void detach() {
        running = false;
        if (view != null && view.getParent() instanceof ViewGroup) ((ViewGroup) view.getParent()).removeView(view);
        view = null;
    }

    private void loop() {
        long lastTicks = -1, lastTime = 0;
        while (running) {
            long ticks = ownTicks(), now = System.nanoTime();
            String cpu = "--";
            if (lastTicks >= 0 && now > lastTime) {
                double secs = (now - lastTime) / 1e9;
                double pct = (ticks - lastTicks) / 100.0 / secs / cores * 100.0;   // share of all cores
                cpu = String.format("%3.0f%%", Math.max(0, pct));
            }
            lastTicks = ticks; lastTime = now;
            String gpu = read("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage").replaceAll("[^0-9]", "");
            String clk = read("/sys/class/kgsl/kgsl-3d0/gpuclk").trim();
            int mhz = 0; try { mhz = (int) (Long.parseLong(clk) / 1000000L); } catch (Exception ignored) { }
            final String text = "CPU " + cpu + "   GPU " + (gpu.isEmpty() ? "--" : gpu + "%") + (mhz > 0 ? " " + mhz + " MHz" : "") + "\n"
                    + "CPU " + temp("cpu-0-0-0") + "  GPU " + temp("gpuss-0") + (temp("battery").equals("--") ? "" : "  Batt " + temp("battery"));
            ui.post(() -> { if (view != null) view.setText(text); });
            try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
        }
    }

    /** CPU time (1/100 s) of all of this app's processes: the game and the Windows layer. */
    private static long ownTicks() {
        long sum = 0; int uid = Process.myUid();
        File[] procs = new File("/proc").listFiles();
        if (procs == null) return 0;
        for (File p : procs) {
            char c0 = p.getName().charAt(0);
            if (c0 < '0' || c0 > '9') continue;
            try {
                if (android.system.Os.stat(p.getPath()).st_uid != uid) continue;
                String s = new String(Files.readAllBytes(new File(p, "stat").toPath()));
                String[] f = s.substring(s.lastIndexOf(')') + 2).split(" ");
                sum += Long.parseLong(f[11]) + Long.parseLong(f[12]);
            } catch (Exception ignored) { }
        }
        return sum;
    }

    private static final java.util.Map<String, String> zones = new java.util.HashMap<>();
    private static String temp(String type) {
        String path = zones.get(type);
        if (path == null) {
            File[] z = new File("/sys/class/thermal").listFiles();
            if (z != null) for (File f : z) if (read(f.getPath() + "/type").trim().equals(type)) { path = f.getPath() + "/temp"; break; }
            zones.put(type, path == null ? "" : path);
        }
        if (path == null || path.isEmpty()) return "--";
        try { return Math.round(Long.parseLong(read(path).trim()) / 1000.0) + "°C"; } catch (Exception e) { return "--"; }
    }

    private static String read(String path) {
        try { return new String(Files.readAllBytes(new File(path).toPath())); } catch (Exception e) { return ""; }
    }
}
