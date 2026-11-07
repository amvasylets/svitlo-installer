package ua.svitlo.ping;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Після перезавантаження телефона або оновлення застосунку — запускаємо пінг знову (якщо його не зупиняли вручну). */
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        if (!Cfg.enabled(c) || !Cfg.configured(c)) return;
        String a = i == null ? "" : String.valueOf(i.getAction());
        Cfg.log(c, Intent.ACTION_MY_PACKAGE_REPLACED.equals(a) ? "⬆️ Застосунок оновлено — пінг перезапущено" : "🔄 Телефон увімкнено — пінг запущено автоматично");
        PingService.start(c, PingService.ACT_START);
    }
}
