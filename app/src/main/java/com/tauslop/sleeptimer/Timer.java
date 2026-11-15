package com.tauslop.sleeptimer;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.format.DateFormat;
import android.view.KeyEvent;

import java.util.Date;

/**
 * All timer logic. Stateless apart from one SharedPreferences file; no service,
 * no background thread. Between start and fire the only thing pending is one alarm.
 */
final class Timer {
    static final String ACTION_FIRE = "com.tauslop.sleeptimer.FIRE";
    static final String ACTION_EXTEND = "com.tauslop.sleeptimer.EXTEND";
    static final String ACTION_CANCEL = "com.tauslop.sleeptimer.CANCEL";

    static final int EXTEND_MINUTES = 10;

    private static final String PREFS = "timer";
    private static final String KEY_TARGET = "target";
    private static final String KEY_LAST_MINUTES = "last";
    private static final String KEY_RESTORE_VOLUME = "restore";
    private static final long FADE_MS = 6000;
    private static final long RESTORE_DELAY_MS = 1000;
    private static final String CHANNEL = "timer";
    private static final int NOTIFICATION_ID = 1;

    private Timer() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Wall-clock time the timer fires, or 0 when idle (also after a reboot). */
    static long target(Context c) {
        long t = prefs(c).getLong(KEY_TARGET, 0);
        return t > System.currentTimeMillis() ? t : 0;
    }

    static int lastMinutes(Context c) {
        return prefs(c).getInt(KEY_LAST_MINUTES, 30);
    }

    static boolean canExact(Context c) {
        return Build.VERSION.SDK_INT < 31
                || c.getSystemService(AlarmManager.class).canScheduleExactAlarms();
    }

    static void start(Context c, int minutes) {
        restoreVolume(c);
        prefs(c).edit().putInt(KEY_LAST_MINUTES, minutes).apply();
        schedule(c, System.currentTimeMillis() + minutes * 60_000L);
    }

    static void extend(Context c) {
        long t = target(c);
        if (t != 0) schedule(c, t + EXTEND_MINUTES * 60_000L);
    }

    /** Re-arm the current timer (picks up a newly granted exact-alarm permission). */
    static void rearm(Context c) {
        long t = target(c);
        if (t != 0) schedule(c, t);
    }

    static void cancel(Context c) {
        c.getSystemService(AlarmManager.class).cancel(broadcast(c, ACTION_FIRE));
        clear(c);
    }

    /**
     * Fade the media volume to 0 over FADE_MS, pause, then restore the volume.
     * Kept under ~7 s total so it fits the broadcast receiver's time limit
     * (the alarm's wakelock is held until {@code done} runs).
     */
    static void fire(Context c, Runnable done) {
        clear(c);
        AudioManager am = c.getSystemService(AudioManager.class);
        int startVol = am.getStreamVolume(AudioManager.STREAM_MUSIC);
        if (startVol == 0 || !am.isMusicActive()) {
            pauseMedia(c);
            done.run();
            return;
        }
        Handler h = new Handler(Looper.getMainLooper());
        long stepMs = FADE_MS / startVol;
        h.post(new Runnable() {
            int vol = startVol;

            @Override
            public void run() {
                if (vol > 0) {
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, --vol, 0);
                    h.postDelayed(this, stepMs);
                    return;
                }
                pauseMedia(c);
                h.postDelayed(() -> {
                    if (am.isMusicActive()) {
                        // Player ignored the pause: stay silent, restore later.
                        prefs(c).edit().putInt(KEY_RESTORE_VOLUME, startVol).apply();
                    } else {
                        am.setStreamVolume(AudioManager.STREAM_MUSIC, startVol, 0);
                    }
                    done.run();
                }, RESTORE_DELAY_MS);
            }
        });
    }

    /** Restore a volume left at 0 by a fade whose pause was ignored, if still untouched. */
    static void restoreVolume(Context c) {
        int vol = prefs(c).getInt(KEY_RESTORE_VOLUME, -1);
        if (vol < 0) return;
        AudioManager am = c.getSystemService(AudioManager.class);
        if (am.getStreamVolume(AudioManager.STREAM_MUSIC) == 0 && !am.isMusicActive()) {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, vol, 0);
        }
        prefs(c).edit().remove(KEY_RESTORE_VOLUME).apply();
    }

    private static void schedule(Context c, long at) {
        prefs(c).edit().putLong(KEY_TARGET, at).apply();
        AlarmManager am = c.getSystemService(AlarmManager.class);
        PendingIntent pi = broadcast(c, ACTION_FIRE);
        if (canExact(c)) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        }
        showNotification(c, at);
    }

    private static void clear(Context c) {
        prefs(c).edit().remove(KEY_TARGET).apply();
        c.getSystemService(NotificationManager.class).cancel(NOTIFICATION_ID);
    }

    private static PendingIntent broadcast(Context c, String action) {
        Intent i = new Intent(action, null, c, TimerReceiver.class);
        return PendingIntent.getBroadcast(c, 0, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static String formatTime(Context c, long at) {
        return DateFormat.getTimeFormat(c).format(new Date(at));
    }

    /** Countdown is rendered by SystemUI (chronometer), so the app never wakes to update it. */
    static void showNotification(Context c, long at) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL,
                    c.getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        PendingIntent open = PendingIntent.getActivity(c, 0,
                new Intent(c, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(c.getString(R.string.notif_title))
                .setContentText(c.getString(R.string.pauses_at, formatTime(c, at)))
                .setWhen(at)
                .setShowWhen(true)
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_STATUS)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null,
                        c.getString(R.string.extend), broadcast(c, ACTION_EXTEND)).build())
                .addAction(new Notification.Action.Builder(null,
                        c.getString(R.string.cancel), broadcast(c, ACTION_CANCEL)).build())
                .build();
        nm.notify(NOTIFICATION_ID, n);
    }

    /**
     * 1) Media "pause" key goes to the active media session (YouTube, Spotify, ...).
     * 2) Taking permanent audio focus makes any remaining focus-aware player pause.
     */
    private static void pauseMedia(Context c) {
        AudioManager am = c.getSystemService(AudioManager.class);
        long now = SystemClock.uptimeMillis();
        am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_MEDIA_PAUSE, 0));
        am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP,
                KeyEvent.KEYCODE_MEDIA_PAUSE, 0));

        AudioFocusRequest req = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                .build();
        if (am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            am.abandonAudioFocusRequest(req);
        }
    }
}
