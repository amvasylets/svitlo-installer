package ua.svitlo.ping;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;

/**
 * Фонова служба: поки телефон заряджається — раз на хвилину надсилає пінг на сервер
 * ({сервер}/p/{черга}/{esp1|esp2}), як плата ESP32. Немає зарядки (= немає світла) — мовчить.
 */
public class PingService extends Service {
    static final String ACT_START = "ua.svitlo.ping.START";
    static final String ACT_PING_NOW = "ua.svitlo.ping.PING_NOW";
    static final String CH = "ping";
    static final int NOTIF = 1;

    private HandlerThread thread;
    private Handler h;
    private PowerManager.WakeLock wake;
    private WifiManager.WifiLock wifi;
    private Boolean charging = null;         // null — ще не знаємо
    private int plugType = 0;
    private long nextPing = 0;               // elapsedRealtime наступного пінгу
    private boolean started = false;

    // ---------- запуск / зупинка ----------
    static void start(Context c, String action) {
        Intent i = new Intent(c, PingService.class).setAction(action);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
        } catch (Exception e) {
            Cfg.log(c, "⚠️ Android не дозволив запустити фонову роботу (" + e.getClass().getSimpleName() + "). Відкрийте застосунок.");
        }
    }

    static void stop(Context c) { c.stopService(new Intent(c, PingService.class)); }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        channel(this);
        thread = new HandlerThread("svitlo-ping");
        thread.start();
        h = new Handler(thread.getLooper());
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_POWER_CONNECTED);
        f.addAction(Intent.ACTION_POWER_DISCONNECTED);
        f.addAction(Intent.ACTION_BATTERY_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(power, f, null, h, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(power, f, null, h);
        Cfg.p(this).edit().putBoolean("running", true).apply();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        goForeground();
        if (!Cfg.enabled(this) || !Cfg.configured(this)) { stopSelfNow(); return START_NOT_STICKY; }
        String a = intent == null ? null : intent.getAction();
        if (!started) {
            started = true;
            if (intent == null) Cfg.log(this, "♻️ Android перезапустив фонову роботу");
            h.post(heartbeat);
            h.post(new Runnable() { @Override public void run() { checkPower(); } });
        }
        if (ACT_PING_NOW.equals(a)) h.post(new Runnable() { @Override public void run() { pingNow(true); } });
        return START_STICKY;
    }

    @Override public void onDestroy() {
        try { unregisterReceiver(power); } catch (Exception ignored) { }
        if (h != null) h.removeCallbacksAndMessages(null);
        locks(false);
        if (thread != null) thread.quit();
        Cfg.p(this).edit().putBoolean("running", false).putBoolean("charging", false).apply();
        super.onDestroy();
    }

    private void stopSelfNow() {
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE); else stopForeground(true);
        stopSelf();
    }

    // ---------- зарядка ----------
    private final BroadcastReceiver power = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { checkPower(i); }
    };

    private void checkPower() { checkPower(registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED))); }

    private void checkPower(Intent battery) {
        int plugged = battery == null ? 0 : battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        if (battery != null && Intent.ACTION_POWER_DISCONNECTED.equals(battery.getAction())) plugged = 0;
        if (battery != null && Intent.ACTION_POWER_CONNECTED.equals(battery.getAction())) {
            // точний тип прийде окремим ACTION_BATTERY_CHANGED; тут лише факт підключення
            Intent st = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            plugged = st == null ? 1 : Math.max(1, st.getIntExtra(BatteryManager.EXTRA_PLUGGED, 1));
        }
        boolean now = plugged != 0;
        if (charging != null && charging == now && plugged == plugType) return;
        boolean first = charging == null;
        charging = now;
        plugType = plugged;
        Cfg.p(this).edit().putBoolean("charging", now).putString("plug", plugName(plugged)).apply();
        h.removeCallbacks(tick);
        h.removeCallbacks(extraTick);
        if (now) {
            if (first) Cfg.log(this, "▶️ Пінг працює (зарядка є: " + plugName(plugged) + ")");
            else Cfg.log(this, "⚡ Зарядку підключено (" + plugName(plugged) + ") — пінг відновлено");
            locks(true);
            nextPing = SystemClock.elapsedRealtime();
            h.post(tick);
        } else {
            Cfg.log(this, first ? "🔌 Пінг очікує: телефон не заряджається" : "🔌 Зарядку відключено — пінг призупинено (світла немає?)");
            locks(false);
            Cfg.p(this).edit().putLong("next", 0).apply();
        }
        notifyStatus();
    }

    static String plugName(int p) {
        switch (p) {
            case BatteryManager.BATTERY_PLUGGED_AC: return "від мережі";
            case BatteryManager.BATTERY_PLUGGED_USB: return "USB";
            case 4: return "бездротова";   // BATTERY_PLUGGED_WIRELESS
            case 0: return "немає";
            default: return "є";
        }
    }

    /** Поки заряджається — не даємо телефону «заснути», щоб пінг ішов точно раз на хвилину. */
    private void locks(boolean on) {
        try {
            if (on) {
                if (wake == null) {
                    PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                    wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "svitlo:ping");
                    wake.setReferenceCounted(false);
                }
                if (!wake.isHeld()) wake.acquire();
                if (wifi == null) {
                    WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
                    if (wm != null) { wifi = wm.createWifiLock(Build.VERSION.SDK_INT >= 29 ? 3 : WifiManager.WIFI_MODE_FULL, "svitlo:wifi"); wifi.setReferenceCounted(false); }
                }
                if (wifi != null && !wifi.isHeld()) wifi.acquire();
            } else {
                if (wake != null && wake.isHeld()) wake.release();
                if (wifi != null && wifi.isHeld()) wifi.release();
            }
        } catch (Exception ignored) { }
    }

    // ---------- пінг ----------
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (charging == null || !charging) return;
            pingNow(false);
            long now = SystemClock.elapsedRealtime();
            nextPing += Cfg.PING_EVERY;                       // рівний крок, без «сповзання»
            if (nextPing <= now) nextPing = now + Cfg.PING_EVERY;
            h.postDelayed(this, nextPing - now);
            Cfg.p(PingService.this).edit().putLong("next", System.currentTimeMillis() + (nextPing - now)).apply();
            if (!Cfg.extra(PingService.this).isEmpty()) { h.removeCallbacks(extraTick); h.postDelayed(extraTick, Cfg.EXTRA_SHIFT); }
        }
    };

    private final Runnable extraTick = new Runnable() {
        @Override public void run() {
            if (charging == null || !charging) return;
            String u = Cfg.extra(PingService.this);
            if (u.isEmpty()) return;
            Net.Res r = Net.get(PingService.this, u);
            Cfg.p(PingService.this).edit().putLong("x_ts", System.currentTimeMillis()).putBoolean("x_ok", r.ok).putString("x_msg", r.msg).apply();
        }
    };

    private final Runnable heartbeat = new Runnable() {
        @Override public void run() {
            Cfg.p(PingService.this).edit().putLong("alive", System.currentTimeMillis()).apply();
            if (charging == null) checkPower();
            h.postDelayed(this, 20_000L);
        }
    };

    /** manual — натиснули «Перевірити зараз»: пінгуємо навіть без зарядки, щоб перевірити зв'язок. */
    private void pingNow(boolean manual) {
        Net.Res r;
        if (!Net.online(this)) r = new Net.Res(-1, "немає інтернету (телефон не підключений до мережі)");
        else r = Net.get(this, Cfg.pingUrl(this));
        android.content.SharedPreferences sp = Cfg.p(this);
        boolean wasOk = sp.getBoolean("last_ok", true);
        String wasMsg = sp.getString("last_msg", "");
        sp.edit().putLong("last_ts", System.currentTimeMillis()).putBoolean("last_ok", r.ok).putString("last_msg", r.msg)
                .putBoolean("last_manual", manual).putInt("sent", sp.getInt("sent", 0) + (r.ok ? 1 : 0)).apply();
        if (manual) Cfg.log(this, (r.ok ? "🔔 Перевірка: ✅ " : "🔔 Перевірка: ❌ ") + r.msg);
        else if (!r.ok && (wasOk || !r.msg.equals(wasMsg))) Cfg.log(this, "❌ " + r.msg);
        else if (r.ok && !wasOk) Cfg.log(this, "✅ Зв'язок із сервером є");
        notifyStatus();
    }

    // ---------- сповіщення (обов'язкове для фонової роботи) ----------
    static void channel(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) c.getSystemService(NOTIFICATION_SERVICE);
        if (nm.getNotificationChannel(CH) != null) return;
        NotificationChannel ch = new NotificationChannel(CH, "Робота пінгу", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Постійне сповіщення, поки телефон надсилає пінг. Без звуку.");
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    private Notification build() {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CH) : new Notification.Builder(this);
        android.content.SharedPreferences sp = Cfg.p(this);
        String title = "Svitlo Пінг · черга " + Cfg.queue(this) + " (" + Cfg.board(this) + ")";
        String text;
        if (charging == null) text = "Запуск…";
        else if (!charging) text = "🔌 Не заряджається — пінг не надсилається";
        else {
            long t = sp.getLong("last_ts", 0);
            text = t == 0 ? "⚡ Зарядка є · надсилаю перший пінг…"
                    : "⚡ Зарядка є · " + Cfg.hms(t).substring(0, 5) + " " + (sp.getBoolean("last_ok", false) ? "✅ сервер прийняв" : "❌ " + sp.getString("last_msg", ""));
        }
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int fl = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT : PendingIntent.FLAG_UPDATE_CURRENT;
        b.setContentTitle(title).setContentText(text).setSmallIcon(R.drawable.ic_notif).setOngoing(true)
                .setContentIntent(PendingIntent.getActivity(this, 0, open, fl));
        if (Build.VERSION.SDK_INT >= 17) b.setShowWhen(false);
        if (Build.VERSION.SDK_INT >= 20) b.setLocalOnly(true);
        if (Build.VERSION.SDK_INT >= 21) b.setColor(0xFFF39C12).setCategory(Notification.CATEGORY_SERVICE);
        if (Build.VERSION.SDK_INT < 26) b.setPriority(Notification.PRIORITY_LOW);
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        if (Build.VERSION.SDK_INT >= 16) b.setStyle(new Notification.BigTextStyle().bigText(text));
        return Build.VERSION.SDK_INT >= 16 ? b.build() : b.getNotification();
    }

    private void goForeground() {
        Notification n = build();
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            else startForeground(NOTIF, n);
        } catch (Exception e) {
            Cfg.log(this, "⚠️ Не вдалося показати постійне сповіщення: " + e.getClass().getSimpleName());
        }
    }

    private void notifyStatus() {
        try { ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIF, build()); } catch (Exception ignored) { }
    }
}
