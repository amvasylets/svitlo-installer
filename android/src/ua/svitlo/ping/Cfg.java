package ua.svitlo.ping;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Налаштування і стан (спільні для екрана і фонової служби). */
final class Cfg {
    static final String DEFAULT_SERVER = "https://svitlo51.a-m-vasylets.workers.dev";
    static final long PING_EVERY = 60_000L;      // раз на хвилину, як плата
    static final long EXTRA_SHIFT = 30_000L;     // додаткова адреса — через 30 с після основної

    private Cfg() {}

    static SharedPreferences p(Context c) { return c.getSharedPreferences("svitlo", Context.MODE_PRIVATE); }

    static String server(Context c) { String s = p(c).getString("server", ""); return s.isEmpty() ? DEFAULT_SERVER : s; }
    static String queue(Context c) { return p(c).getString("queue", ""); }
    static String board(Context c) { String b = p(c).getString("board", "esp1"); return validBoard(b) ? b : "esp1"; }
    static boolean validBoard(String b) { return b != null && b.matches("esp[1-6]"); }
    static String extra(Context c) { return p(c).getString("extra", ""); }
    static boolean enabled(Context c) { return p(c).getBoolean("enabled", false); }
    static boolean configured(Context c) { return !queue(c).isEmpty(); }

    /** «3/2», «3,2», «3-2», « 3.2 » → «3.2». Порожньо, якщо не схоже на номер черги. */
    static String normQueue(String s) {
        if (s == null) return "";
        String t = s.trim().replace('/', '.').replace(',', '.').replace('-', '.').replace(' ', '.');
        while (t.contains("..")) t = t.replace("..", ".");
        return t.matches("\\d{1,2}\\.\\d{1,2}") ? t : "";
    }

    /** Адреса сервера: додаємо https://, прибираємо зайві / в кінці. */
    static String normServer(String s) {
        String t = s == null ? "" : s.trim();
        if (t.isEmpty()) return "";
        if (!t.startsWith("http://") && !t.startsWith("https://")) t = "https://" + t;
        while (t.endsWith("/")) t = t.substring(0, t.length() - 1);
        return t;
    }

    static boolean charging(Context c) {
        android.content.Intent b = c.registerReceiver(null, new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED));
        return b != null && b.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) != 0;
    }

    static String pingUrl(Context c) { return server(c) + "/p/" + queue(c) + "/" + board(c); }

    static String hms(long t) { return new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(t)); }
    static String dhm(long t) { return new SimpleDateFormat("dd.MM HH:mm", Locale.US).format(new Date(t)); }

    /** Історія подій (останні 40) — як «Історія перезапусків» у платі. */
    static synchronized void log(Context c, String line) {
        SharedPreferences sp = p(c);
        String h = sp.getString("hist", "");
        String n = dhm(System.currentTimeMillis()) + "  " + line + (h.isEmpty() ? "" : "\n" + h);
        String[] parts = n.split("\n");
        if (parts.length > 40) {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 40; i++) { if (i > 0) b.append('\n'); b.append(parts[i]); }
            n = b.toString();
        }
        sp.edit().putString("hist", n).apply();
    }
}
