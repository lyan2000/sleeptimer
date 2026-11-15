package com.tauslop.sleeptimer;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.view.View;
import android.widget.Button;
import android.widget.Chronometer;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.TextView;

public final class MainActivity extends Activity {
    private static final int[] PRESETS = {1, 3, 5, 10, 15, 30, 45, 60, 90};
    private static final int COLUMNS = 3;
    private static final int MAX_MINUTES = 240;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = this::refresh;

    private View idlePanel, runPanel, exactHint;
    private Chronometer chrono;
    private TextView endsAt;
    private NumberPicker picker;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        idlePanel = findViewById(R.id.idle_panel);
        runPanel = findViewById(R.id.run_panel);
        exactHint = findViewById(R.id.exact_hint);
        chrono = findViewById(R.id.chrono);
        endsAt = findViewById(R.id.ends_at);
        picker = findViewById(R.id.picker);

        chrono.setCountDown(true);
        picker.setMinValue(1);
        picker.setMaxValue(MAX_MINUTES);
        picker.setWrapSelectorWheel(false);
        picker.setValue(Math.min(Timer.lastMinutes(this), MAX_MINUTES));

        buildPresetGrid(findViewById(R.id.presets));

        findViewById(R.id.start).setOnClickListener(v -> start(picker.getValue()));
        findViewById(R.id.extend).setOnClickListener(v -> {
            Timer.extend(this);
            refresh();
        });
        findViewById(R.id.cancel).setOnClickListener(v -> {
            Timer.cancel(this);
            refresh();
        });
        exactHint.setOnClickListener(v -> startActivity(new Intent(
                Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                Uri.parse("package:" + getPackageName()))));
    }

    /** 3-column grid of tiles; tapping one starts the timer immediately. */
    private void buildPresetGrid(LinearLayout grid) {
        int gap = Math.round(12 * getResources().getDisplayMetrics().density);
        int height = Math.round(72 * getResources().getDisplayMetrics().density);
        String unit = getString(R.string.min);
        LinearLayout row = null;
        for (int i = 0; i < PRESETS.length; i++) {
            if (i % COLUMNS == 0) {
                row = new LinearLayout(this);
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                if (i > 0) rp.topMargin = gap;
                grid.addView(row, rp);
            }
            int m = PRESETS[i];
            String num = String.valueOf(m);
            SpannableString label = new SpannableString(num + "\n" + unit);
            label.setSpan(new RelativeSizeSpan(0.55f), num.length(), label.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

            Button b = new Button(this, null, 0, R.style.Tile);
            b.setText(label);
            b.setTextSize(24);
            b.setIncludeFontPadding(false);
            b.setOnClickListener(v -> start(m));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, height, 1f);
            if (i % COLUMNS != 0) lp.setMarginStart(gap);
            row.addView(b, lp);
        }
    }

    private void start(int minutes) {
        requestNotificationPermission();
        Timer.start(this, minutes);
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Timer.restoreVolume(this);
        Timer.rearm(this); // upgrades to an exact alarm if permission was just granted
        refresh();
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(refresh);
        chrono.stop();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        // Returning from the notification shade (+10 / Cancel) doesn't call onResume.
        if (hasFocus) refresh();
    }

    private void refresh() {
        handler.removeCallbacks(refresh);
        long target = Timer.target(this);
        boolean running = target != 0;
        runPanel.setVisibility(running ? View.VISIBLE : View.GONE);
        idlePanel.setVisibility(running ? View.GONE : View.VISIBLE);
        exactHint.setVisibility(Timer.canExact(this) ? View.GONE : View.VISIBLE);
        if (running) {
            long remaining = target - System.currentTimeMillis();
            chrono.setBase(SystemClock.elapsedRealtime() + remaining);
            chrono.start();
            endsAt.setText(getString(R.string.pauses_at, Timer.formatTime(this, target)));
            // Only while visible: flip back to idle once the timer fires.
            handler.postDelayed(refresh, remaining + 500);
        } else {
            chrono.stop();
        }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        // Timer already started; post its notification now that we're allowed to.
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            long target = Timer.target(this);
            if (target != 0) Timer.showNotification(this, target);
        }
    }
}
