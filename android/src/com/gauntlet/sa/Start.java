package com.gauntlet.sa;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;

/**
 * The app's only screen of its own: the game's title picture. Behind it the first start prepares the system
 * files, the game is imported from Gauntlet.pak (once), and then the game is started.
 */
public class Start extends Activity {
    private static final int PICK = 7;
    private TextView status; private ProgressBar bar; private Button pick; private TextView hint;
    private boolean working, launched;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        if (android.os.Build.VERSION.SDK_INT >= 28) getWindow().getAttributes().layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        ImageView img = new ImageView(this);
        int id = Setup.drawable(this, "gauntlet_splash");
        if (id != 0) { img.setImageResource(id); img.setScaleType(ImageView.ScaleType.CENTER_CROP); }
        root.addView(img, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL); box.setGravity(Gravity.CENTER_HORIZONTAL);
        status = new TextView(this); status.setTextColor(0xFFE8EEF6); status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16); status.setShadowLayer(6, 0, 1, 0xC0000000); status.setGravity(Gravity.CENTER);
        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); bar.setMax(100); bar.setVisibility(View.GONE);
        pick = new Button(this); pick.setText("Choose Gauntlet.pak"); pick.setVisibility(View.GONE); pick.setOnClickListener(v -> choosePak());
        hint = new TextView(this); hint.setTextColor(0xFFC8D4E4); hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13); hint.setShadowLayer(6, 0, 1, 0xC0000000); hint.setGravity(Gravity.CENTER); hint.setVisibility(View.GONE);
        int pad = (int) (getResources().getDisplayMetrics().density * 10);
        box.addView(status, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams lpBar = new LinearLayout.LayoutParams((int) (getResources().getDisplayMetrics().density * 360), -2); lpBar.topMargin = pad;
        box.addView(bar, lpBar);
        LinearLayout.LayoutParams lpPick = new LinearLayout.LayoutParams(-2, -2); lpPick.topMargin = pad;
        box.addView(pick, lpPick);
        LinearLayout.LayoutParams lpHint = new LinearLayout.LayoutParams(-2, -2); lpHint.topMargin = pad;
        box.addView(hint, lpHint);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM); lp.bottomMargin = (int) (getResources().getDisplayMetrics().density * 56);
        root.addView(box, lp);
        setContentView(root);
        hideBars();
        letterShortcut(getIntent());
        launched = b != null && b.getBoolean("launched");
        restored = launched;
        if (!launched && Session.get(getApplication()).running()) { Session.get(getApplication()).backToGame(this); return; }   // the game is already up
        if (!launched) go();
    }

    /** The icon's long-press menu: "Odin button letters" / "Xbox button letters". */
    private void letterShortcut(Intent i) {
        if (i != null && i.hasExtra("perf")) {
            boolean on = !Device.perfOverlay(this);
            Device.setPerfOverlay(this, on);
            android.widget.Toast.makeText(this, "Performance overlay " + (on ? "on" : "off") + (Session.get(getApplication()).running() ? " from the next start" : ""), android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        if (i != null && i.hasExtra("fps")) {
            // one entry (the icon menu shows only four): off -> FPS -> FPS + performance -> off
            int state = Device.perfOverlay(this) ? 2 : Device.showFps(this) ? 1 : 0, next = (state + 1) % 3;
            Device.setShowFps(this, next == 1); Device.setPerfOverlay(this, next == 2);
            String[] names = {"Overlay off", "FPS counter on", "FPS + CPU/GPU/temperatures on"};
            android.widget.Toast.makeText(this, names[next] + (Session.get(getApplication()).running() ? " from the next start" : ""), android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        if (i != null && i.hasExtra("res")) {
            boolean full = !Device.fullPicture(this); Device.setFullPicture(this, full);
            android.widget.Toast.makeText(this, (full ? "Picture 1080p (sharper, warmer)" : "Picture 900p (cooler)") + (Session.get(getApplication()).running() ? " from the next start" : ""), android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        if (i != null && i.hasExtra("rate")) {
            // 60 <-> 120 (120 only where the chip and the screen setting allow it)
            boolean to120 = Device.frameCap(this) < 120;
            Device.setFrameCap(this, to120 ? 120 : 60);
            String text = Device.frameCap(this) >= 120 ? "120 frames per second (lighter picture)" : to120 ? "120 needs the screen set to 120 Hz" : "60 frames per second (full picture)";
            android.widget.Toast.makeText(this, text + (Session.get(getApplication()).running() ? " from the next start" : ""), android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        if (i == null || !(i.hasExtra("buttons") || i.hasExtra("letters"))) return;
        String b = i.hasExtra("buttons") ? i.getStringExtra("buttons") : i.getStringExtra("letters");
        if ("next".equals(b)) { String cur = Device.buttons(this); b = cur.equals("odin") ? "ps" : cur.equals("ps") ? "xbox" : "odin"; }
        Device.setButtons(this, b);
        String text = ("ps".equals(b) ? "PlayStation" : "odin".equals(b) ? "Odin" : "Xbox") + " buttons" + (Session.get(getApplication()).running() ? " from the next start" : "");
        android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_SHORT).show();
    }
    @Override protected void onNewIntent(Intent i) { super.onNewIntent(i); letterShortcut(i); }

    @Override protected void onSaveInstanceState(Bundle b) { super.onSaveInstanceState(b); b.putBoolean("launched", launched); }
    @Override public void onWindowFocusChanged(boolean f) { super.onWindowFocusChanged(f); if (f) hideBars(); }
    private void hideBars() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }
    @Override public void onBackPressed() { if (!working) super.onBackPressed(); }

    private boolean leftAfterLaunch;
    @Override protected void onPause() { super.onPause(); if (launched) leftAfterLaunch = true; }
    @Override protected void onResume() {
        super.onResume();
        // shown again although the game was started: return to the game (or start it again if it is gone)
        if (launched && (leftAfterLaunch || restored)) { leftAfterLaunch = false; restored = false; Session.get(getApplication()).returned(this); }
    }
    private boolean restored;

    private void show(final String text, final int percent) {
        runOnUiThread(() -> {
            status.setText(percent >= 0 ? text + "  " + percent + "%" : text);
            bar.setVisibility(percent >= 0 ? View.VISIBLE : View.GONE);
            if (percent >= 0) bar.setProgress(percent);
        });
    }

    /** The steps up to the running game; stops at the first one that needs the user (choosing the pak). */
    private void go() {
        if (working) return;
        working = true;
        runOnUiThread(() -> { pick.setVisibility(View.GONE); hint.setVisibility(View.GONE); });
        new Thread(() -> {
            try {
                if (!Setup.systemReady(this)) {
                    show("Preparing (first start only)", 0);
                    if (!Setup.installSystem(this, this::show)) { fail("Could not prepare the system files. Is there enough free space?"); return; }
                }
                if (!Setup.containerReady(this)) show("Preparing (first start only)", -1);
                if (!Setup.createContainer(this)) { fail("Could not prepare the game's environment."); return; }   // also refreshes the settings on every start
                if (!Setup.gameReady(this)) {
                    File dropped = getExternalFilesDir(null) == null ? null : new File(getExternalFilesDir(null), "Gauntlet.pak");
                    if (dropped != null && dropped.isFile()) {
                        String err;
                        try (InputStream in = new FileInputStream(dropped)) { err = Setup.importPak(this, in, dropped.length(), this::show); }
                        if (err != null) { fail(err); return; }
                        dropped.delete();
                    } else {
                        working = false;
                        runOnUiThread(() -> {
                            status.setText("The game files are needed once.");
                            bar.setVisibility(View.GONE); pick.setVisibility(View.VISIBLE);
                            hint.setText("Copy Gauntlet.pak to this device, then choose it here."); hint.setVisibility(View.VISIBLE);
                            pick.requestFocus();
                        });
                        return;
                    }
                }
                // add-on paks dropped into the app's folder (the HD videos): imported once, then removed
                File[] extra = getExternalFilesDir(null) == null ? null : getExternalFilesDir(null).listFiles();
                if (extra != null) for (File f : extra) {
                    if (!f.isFile() || !f.getName().toLowerCase().endsWith(".pak")) continue;
                    String err;
                    try (InputStream in = new FileInputStream(f)) { err = Setup.importPak(this, in, f.length(), this::show); }
                    if (err != null) { fail(f.getName() + ": " + err); return; }
                    f.delete();
                }
                show("", -1);
                working = false;
                runOnUiThread(this::play);            // straight into the game: no menu of our own
            } catch (Exception e) {
                Log.e(Session.TAG, "start", e);
                fail("Something went wrong: " + e);
            }
        }, "gauntlet-start").start();
    }

    // ---- the little menu on the title picture: Play, frame rate, picture quality
    private LinearLayout menu; private TextView mPlay, mFps, mQuality;
    private static final String[] QUALITY = {"Low", "Medium", "High"};

    private TextView menuItem(String text) {
        final TextView t = new TextView(this);
        float dp = getResources().getDisplayMetrics().density;
        t.setText(text); t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17); t.setTextColor(0xFFF2F6FB); t.setGravity(Gravity.CENTER);
        t.setPadding((int) (22 * dp), (int) (10 * dp), (int) (22 * dp), (int) (10 * dp));
        t.setFocusable(true); t.setFocusableInTouchMode(false); t.setClickable(true);
        final android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(8 * dp); bg.setColor(0x80101820); bg.setStroke((int) (1.5f * dp), 0x60FFFFFF);
        t.setBackground(bg);
        t.setOnFocusChangeListener((v, has) -> { bg.setColor(has ? 0xE02A5A8C : 0x80101820); bg.setStroke((int) ((has ? 2.5f : 1.5f) * dp), has ? 0xFFFFFFFF : 0x60FFFFFF); });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2); lp.leftMargin = lp.rightMargin = (int) (8 * dp);
        t.setLayoutParams(lp);
        return t;
    }

    private void refreshMenu() {
        mFps.setText("Frame rate: " + Device.frameCap(this));
        mQuality.setText("Quality: " + QUALITY[Device.quality(this)]);
    }

    private void showMenu() {
        if (menu == null) {
            menu = new LinearLayout(this); menu.setOrientation(LinearLayout.HORIZONTAL); menu.setGravity(Gravity.CENTER);
            mPlay = menuItem("Play"); mFps = menuItem(""); mQuality = menuItem("");
            mPlay.setOnClickListener(v -> play());
            mFps.setOnClickListener(v -> { Device.setFrameCap(this, Device.frameCap(this) >= 120 ? 60 : 120); refreshMenu(); });
            mQuality.setOnClickListener(v -> { Device.setQuality(this, (Device.quality(this) + 2) % 3); refreshMenu(); });
            menu.addView(mPlay); if (Device.canDo120(this)) menu.addView(mFps); menu.addView(mQuality);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
            lp.bottomMargin = (int) (getResources().getDisplayMetrics().density * 64);
            ((FrameLayout) status.getParent().getParent()).addView(menu, lp);
        }
        refreshMenu();
        status.setText(""); bar.setVisibility(View.GONE); hint.setVisibility(View.GONE);
        menu.setVisibility(View.VISIBLE);
        mPlay.requestFocus();
    }

    /** On a controller every face button and Start confirm (the Odin's buttons are printed differently from an Xbox pad's). */
    @Override public boolean dispatchKeyEvent(android.view.KeyEvent e) {
        if (menu != null && menu.getVisibility() == View.VISIBLE && !working) {
            int k = e.getKeyCode();
            boolean confirm = k == android.view.KeyEvent.KEYCODE_BUTTON_A || k == android.view.KeyEvent.KEYCODE_BUTTON_B || k == android.view.KeyEvent.KEYCODE_BUTTON_X
                    || k == android.view.KeyEvent.KEYCODE_BUTTON_Y || k == android.view.KeyEvent.KEYCODE_BUTTON_START;
            if (confirm) {
                if (e.getAction() == android.view.KeyEvent.ACTION_UP) {
                    View f = k == android.view.KeyEvent.KEYCODE_BUTTON_START ? mPlay : getCurrentFocus();
                    if (f == null) f = mPlay;
                    f.performClick();
                }
                return true;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    private void play() {
        if (working || launched) return;
        working = true;
        if (menu != null) menu.setVisibility(View.GONE);
        new Thread(() -> {
            try {
                Setup.applyFrameCap(this);
                Setup.applyButtonLetters(this);
                Setup.applyGameSettings(this);
                Log.i(Session.TAG, "controller for player 1: " + Setup.assignController(this) + ", frame rate " + Device.frameCap(this) + ", " + Device.buttons(this) + " buttons, fps counter " + Device.showFps(this) + (Setup.testRun(this) ? ", test run" : ""));
                working = false; launched = true;
                runOnUiThread(() -> Session.get(getApplication()).launch(this));
            } catch (Exception e) {
                Log.e(Session.TAG, "play", e);
                fail("Something went wrong: " + e);
            }
        }, "gauntlet-play").start();
    }

    private void fail(final String text) {
        working = false;
        runOnUiThread(() -> { status.setText(text); bar.setVisibility(View.GONE); });
    }

    private void choosePak() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("*/*");
        try { startActivityForResult(i, PICK); } catch (Exception e) { fail("No file chooser on this device. Put Gauntlet.pak into Android/data/" + getPackageName() + "/files instead."); }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != PICK || res != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        working = true;
        pick.setVisibility(View.GONE); hint.setVisibility(View.GONE);
        new Thread(() -> {
            long size = -1;
            try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
                if (c != null && c.moveToFirst()) { int k = c.getColumnIndex(OpenableColumns.SIZE); if (k >= 0 && !c.isNull(k)) size = c.getLong(k); }
            } catch (Exception ignored) { }
            String err;
            try (InputStream in = getContentResolver().openInputStream(uri)) { err = Setup.importPak(this, in, size, this::show); }
            catch (Exception e) { err = "Could not read the file: " + e.getMessage(); }
            working = false;
            if (err != null) { final String m = err; runOnUiThread(() -> { status.setText(m); bar.setVisibility(View.GONE); pick.setVisibility(View.VISIBLE); }); return; }
            runOnUiThread(() -> { hint.setText("Imported. You can delete Gauntlet.pak from the device now."); hint.setVisibility(View.VISIBLE); });
            go();
        }, "gauntlet-import").start();
    }
}
