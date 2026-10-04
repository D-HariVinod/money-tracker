package io.github.dharivinod.moneytracker;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.util.Log;

/**
 * Listens for a sustained shake while the screen is on and opens the app in quick add.
 * Android requires a visible notification for anything that keeps running like this.
 */
public class ShakeService extends Service implements SensorEventListener {
    private static final String TAG = "MoneyShake";
    private static final String PREFS = "money", KEY_ON = "shake";
    private static final String CH_SERVICE = "shake", CH_QUICK = "quick";
    private static final int ID_SERVICE = 1, ID_QUICK = 2;

    // A shake counts when there are HITS hard jolts in a row (never more than LULL_MS apart)
    // and the shaking has gone on for at least SPAN_MS. Walking stays well under JOLT_G.
    private static final float JOLT_G = 2.5f;
    private static final int HITS = 6;
    private static final long GAP_MS = 100, LULL_MS = 600, SPAN_MS = 1000, COOLDOWN_MS = 4000;

    private SensorManager sensors;
    private boolean listening, receiving;
    private int streak;
    private long streakStart, lastHit, lastFire;

    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            listen(Intent.ACTION_SCREEN_ON.equals(i.getAction())); // no point listening with the screen off
        }
    };

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    static boolean isEnabled(Context c) {
        return prefs(c).getBoolean(KEY_ON, true);
    }

    static void setEnabled(Context c, boolean on) {
        prefs(c).edit().putBoolean(KEY_ON, on).apply();
    }

    /** Starts or stops the listener to match the saved switch. */
    static void sync(Context c) {
        Intent i = new Intent(c, ShakeService.class);
        if (isEnabled(c)) c.startForegroundService(i);
        else c.stopService(i);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sensors = getSystemService(SensorManager.class);
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CH_SERVICE, getString(R.string.shake_channel), NotificationManager.IMPORTANCE_LOW));
        nm.createNotificationChannel(new NotificationChannel(CH_QUICK, getString(R.string.quick_channel), NotificationManager.IMPORTANCE_HIGH));
        Notification n = new Notification.Builder(this, CH_SERVICE)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(getString(R.string.shake_on_title))
                .setContentText(getString(R.string.shake_on_text))
                .setContentIntent(open(false))
                .setOngoing(true)
                .build();
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(ID_SERVICE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            else startForeground(ID_SERVICE, n);
        } catch (RuntimeException e) {
            Log.w(TAG, "could not start in the foreground", e);
            stopSelf();
            return;
        }
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screen, f, RECEIVER_NOT_EXPORTED);
        else registerReceiver(screen, f);
        receiving = true;
        listen(getSystemService(PowerManager.class).isInteractive());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (receiving) unregisterReceiver(screen);
        listen(false);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void listen(boolean on) {
        if (on == listening) return;
        streak = 0;
        if (on) {
            Sensor a = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            listening = a != null && sensors.registerListener(this, a, SensorManager.SENSOR_DELAY_GAME);
        } else {
            sensors.unregisterListener(this);
            listening = false;
        }
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        float x = e.values[0], y = e.values[1], z = e.values[2];
        double g = Math.sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH;
        if (g < JOLT_G) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastHit < GAP_MS) return;
        if (now - lastHit > LULL_MS) {
            streak = 0;
            streakStart = now;
        }
        lastHit = now;
        streak++;
        if (streak >= HITS && now - streakStart >= SPAN_MS && now - lastFire >= COOLDOWN_MS) {
            lastFire = now;
            streak = 0;
            fire();
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private void fire() {
        boolean direct = MainActivity.visible || Settings.canDrawOverlays(this);
        Log.i(TAG, "shake detected, opening " + (direct ? "the app" : "a notification"));
        try {
            Vibrator v = getSystemService(Vibrator.class);
            if (v != null) v.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE));
        } catch (RuntimeException e) {
            // no vibrator, or not allowed right now
        }
        if (direct) {
            try {
                startActivity(new Intent(this, MainActivity.class).putExtra(MainActivity.EXTRA_QUICK, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (RuntimeException e) {
                Log.w(TAG, "could not open the app", e);
            }
        }
        // Android does not let a background app open itself without "Display over other apps": offer a tap instead.
        Notification n = new Notification.Builder(this, CH_QUICK)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(getString(R.string.quick_title))
                .setContentText(getString(R.string.quick_text))
                .setContentIntent(open(true))
                .setAutoCancel(true)
                .setTimeoutAfter(20000)
                .build();
        getSystemService(NotificationManager.class).notify(ID_QUICK, n);
    }

    private PendingIntent open(boolean quick) {
        Intent i = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (quick) i.putExtra(MainActivity.EXTRA_QUICK, true);
        return PendingIntent.getActivity(this, quick ? 1 : 0, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
