package ua.svitlo.ping;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/** Лише для автоматичних тестів через adb: налаштування без натискання кнопок. Захищено правом DUMP (його мають тільки система й adb). */
public class TestReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        SharedPreferences.Editor e = Cfg.p(c).edit();
        if (i.hasExtra("server")) e.putString("server", Cfg.normServer(i.getStringExtra("server")));
        if (i.hasExtra("queue")) e.putString("queue", Cfg.normQueue(i.getStringExtra("queue")));
        if (i.hasExtra("board")) e.putString("board", "esp2".equals(i.getStringExtra("board")) ? "esp2" : "esp1");
        if (i.hasExtra("extra")) e.putString("extra", i.getStringExtra("extra"));
        if (i.hasExtra("enabled")) e.putBoolean("enabled", i.getBooleanExtra("enabled", false));
        e.commit();
        if (Cfg.enabled(c) && Cfg.configured(c)) PingService.start(c, i.getBooleanExtra("ping_now", false) ? PingService.ACT_PING_NOW : PingService.ACT_START);
        else PingService.stop(c);
    }
}
