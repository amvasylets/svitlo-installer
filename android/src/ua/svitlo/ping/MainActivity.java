package ua.svitlo.ping;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Один екран: стан, налаштування (як у платі), дозволи для фонової роботи, історія. */
public class MainActivity extends Activity {
    static final int BG = 0xFF121212, CARD = 0xFF1E1E1E, LINE = 0xFF2A2A2A, TEXT = 0xFFE8E8E8, MUTED = 0xFF9A9A9A,
            ACCENT = 0xFFF39C12, OK = 0xFF2ECC71, BAD = 0xFFE74C3C, BLUE = 0xFF2980B9;

    private final Handler ui = new Handler();
    private TextView tvState, tvPower, tvLast, tvNext, tvWhere, tvErr, tvBat, tvNotif, tvHist, tvExtra;
    private EditText etQueue, etServer, etExtra;
    private RadioButton rb1, rb2;
    private LinearLayout form, adv, histBox, batBox, notifBox;
    private Button btnEdit, btnToggle, btnBat, btnNotif, btnAdv, btnHist, btnPing;

    // ---------- допоміжне для побудови екрана ----------
    private int dp(float v) { return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()); }

    private GradientDrawable round(int color, float r) { GradientDrawable g = new GradientDrawable(); g.setColor(color); g.setCornerRadius(dp(r)); return g; }

    private TextView text(LinearLayout parent, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextSize(sp); t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setLineSpacing(0, 1.15f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(4);
        parent.addView(t, lp);
        return t;
    }

    private LinearLayout card(LinearLayout parent, String title) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(14), dp(16), dp(16));
        c.setBackgroundDrawable(round(CARD, 14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.bottomMargin = dp(12);
        parent.addView(c, lp);
        if (title != null) text(c, title, 17, TEXT, true);
        return c;
    }

    private Button button(LinearLayout parent, String s, int bg, int fg) {
        Button b = new Button(this);
        b.setText(s); b.setTextSize(16); b.setTextColor(fg); b.setAllCaps(false);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackgroundDrawable(round(bg, 10));
        b.setPadding(dp(12), dp(12), dp(12), dp(12));
        b.setMinHeight(dp(48));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(10);
        parent.addView(b, lp);
        return b;
    }

    private EditText input(LinearLayout parent, String label, String hint, int type) {
        text(parent, label, 14, MUTED, false).setPadding(0, dp(8), 0, 0);
        EditText e = new EditText(this);
        e.setHint(hint); e.setHintTextColor(0xFF666666); e.setTextColor(Color.WHITE); e.setTextSize(17);
        e.setSingleLine(true); e.setInputType(type);
        e.setContentDescription(label);
        e.setBackgroundDrawable(round(LINE, 10));
        e.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(4);
        parent.addView(e, lp);
        return e;
    }

    // ---------- екран ----------
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        PingService.channel(this);
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setBackgroundColor(BG);
        sv.setFitsSystemWindows(true);                 // Android 15+: не залазимо під рядок стану
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(24));
        root.setFocusableInTouchMode(true);              // щоб клавіатура не вискакувала одразу при відкритті
        root.setDescendantFocusability(LinearLayout.FOCUS_BEFORE_DESCENDANTS);
        sv.addView(root);

        TextView logo = text(root, "⚡ Svitlo Пінг", 28, ACCENT, true);
        logo.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView sub = text(root, "Телефон замість плати: поки він заряджається, щохвилини повідомляє боту, що світло є", 14, MUTED, false);
        sub.setGravity(Gravity.CENTER_HORIZONTAL);
        sub.setPadding(0, 0, 0, dp(12));

        // --- стан ---
        LinearLayout st = card(root, null);
        tvState = text(st, "", 22, TEXT, true);
        tvPower = text(st, "", 16, TEXT, false);
        tvLast = text(st, "", 16, TEXT, false);
        tvNext = text(st, "", 14, MUTED, false);
        tvExtra = text(st, "", 14, MUTED, false);
        tvWhere = text(st, "", 13, MUTED, false);
        btnPing = button(st, "🔔 Перевірити зараз", BLUE, Color.WHITE);
        btnPing.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { pingNow(); } });

        // --- налаштування ---
        LinearLayout set = card(root, "⚙️ Налаштування");
        btnEdit = button(set, "✏️ Змінити налаштування", LINE, TEXT);
        form = new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL); set.addView(form);
        etQueue = input(form, "Номер черги", "наприклад 3.2", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        text(form, "Ким цей телефон буде для бота", 14, MUTED, false).setPadding(0, dp(10), 0, 0);
        RadioGroup rg = new RadioGroup(this); rg.setOrientation(RadioGroup.HORIZONTAL);
        rb1 = new RadioButton(this); rb1.setText("esp1   "); rb1.setTextColor(TEXT); rb1.setTextSize(17); rb1.setId(View.generateViewId());
        rb2 = new RadioButton(this); rb2.setText("esp2"); rb2.setTextColor(TEXT); rb2.setTextSize(17); rb2.setId(View.generateViewId());
        rg.addView(rb1); rg.addView(rb2); form.addView(rg);
        text(form, "Якщо на цій черзі вже є плата esp1 — оберіть esp2 (і навпаки).", 13, MUTED, false);
        btnAdv = button(form, "▸ Додатково", LINE, MUTED);
        adv = new LinearLayout(this); adv.setOrientation(LinearLayout.VERTICAL); adv.setVisibility(View.GONE); form.addView(adv);
        etServer = input(adv, "Адреса сервера (бота)", Cfg.DEFAULT_SERVER, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        etExtra = input(adv, "Додаткова адреса (необов'язково) — пінг через 30 с після основного", "https://…", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        tvErr = text(form, "", 14, BAD, true);
        Button save = button(form, "💾 Зберегти і запустити", ACCENT, 0xFF111111);
        save.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { save(); } });
        btnEdit.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { showForm(true); } });
        btnAdv.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            boolean show = adv.getVisibility() != View.VISIBLE; adv.setVisibility(show ? View.VISIBLE : View.GONE); btnAdv.setText(show ? "▾ Додатково" : "▸ Додатково"); } });

        // --- робота у фоні ---
        LinearLayout bg = card(root, "🔋 Робота у фоні");
        batBox = new LinearLayout(this); batBox.setOrientation(LinearLayout.VERTICAL); bg.addView(batBox);
        tvBat = text(batBox, "", 15, TEXT, false);
        btnBat = button(batBox, "Дозволити роботу у фоні", ACCENT, 0xFF111111);
        btnBat.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { askBattery(); } });
        notifBox = new LinearLayout(this); notifBox.setOrientation(LinearLayout.VERTICAL); bg.addView(notifBox);
        tvNotif = text(notifBox, "", 15, TEXT, false);
        btnNotif = button(notifBox, "Дозволити сповіщення", LINE, TEXT);
        btnNotif.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { askNotif(); } });
        text(bg, "Xiaomi, Redmi, Huawei, Honor, Samsung, Oppo, Realme: додатково в налаштуваннях застосунку увімкніть «Автозапуск» і поставте батарею «Без обмежень». Телефон тримайте на зарядці, а в Wi-Fi роутера — живлення від тієї ж мережі.", 13, MUTED, false);
        Button app = button(bg, "Відкрити налаштування застосунку", LINE, TEXT);
        app.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { openAppSettings(); } });

        // --- історія ---
        LinearLayout hc = card(root, null);
        btnHist = button(hc, "📜 Історія подій", LINE, TEXT);
        histBox = new LinearLayout(this); histBox.setOrientation(LinearLayout.VERTICAL); histBox.setVisibility(View.GONE); hc.addView(histBox);
        tvHist = text(histBox, "", 13, MUTED, false);
        tvHist.setTypeface(Typeface.MONOSPACE);
        btnHist.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) {
            boolean show = histBox.getVisibility() != View.VISIBLE; histBox.setVisibility(show ? View.VISIBLE : View.GONE); refresh(); } });

        btnToggle = button(root, "", LINE, TEXT);
        btnToggle.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { toggle(); } });
        TextView ver = text(root, "Svitlo Пінг " + BuildInfo.VERSION + " · Android " + Build.VERSION.RELEASE, 12, 0xFF666666, false);
        ver.setGravity(Gravity.CENTER_HORIZONTAL); ver.setPadding(0, dp(12), 0, 0);

        setContentView(sv);
        root.requestFocus();
        fillForm();
        showForm(!Cfg.configured(this));
    }

    @Override protected void onResume() {
        super.onResume();
        if (Cfg.enabled(this) && Cfg.configured(this)) PingService.start(this, PingService.ACT_START);   // якщо Android «прибив» службу — піднімаємо
        ui.post(loop);
    }

    @Override protected void onPause() { super.onPause(); ui.removeCallbacks(loop); }

    private final Runnable loop = new Runnable() { @Override public void run() { refresh(); ui.postDelayed(this, 1000); } };

    private void fillForm() {
        etQueue.setText(Cfg.queue(this));
        String s = Cfg.p(this).getString("server", "");
        etServer.setText(s.equals(Cfg.DEFAULT_SERVER) ? "" : s);
        etExtra.setText(Cfg.extra(this));
        if ("esp2".equals(Cfg.board(this))) rb2.setChecked(true); else rb1.setChecked(true);
    }

    private void showForm(boolean show) {
        form.setVisibility(show ? View.VISIBLE : View.GONE);
        btnEdit.setVisibility(show ? View.GONE : View.VISIBLE);
        if (show) { fillForm(); tvErr.setText(""); }
    }

    private void save() {
        String q = Cfg.normQueue(etQueue.getText().toString());
        if (q.isEmpty()) { tvErr.setText("Вкажіть номер черги у форматі 3.2 (можна 3/2)"); return; }
        String srv = Cfg.normServer(etServer.getText().toString());
        String ex = Cfg.normServer(etExtra.getText().toString());
        String board = rb2.isChecked() ? "esp2" : "esp1";
        Cfg.p(this).edit().putString("queue", q).putString("board", board).putString("server", srv).putString("extra", ex).putBoolean("enabled", true)
                .putLong("last_ts", 0).putBoolean("last_ok", true).putString("last_msg", "").commit();
        Cfg.log(this, "💾 Налаштування: черга " + q + " · " + board + (srv.isEmpty() ? "" : " · " + srv));
        hideKeyboard();
        showForm(false);
        PingService.stop(this);                          // перезапуск з новими налаштуваннями
        PingService.start(this, Cfg.charging(this) ? PingService.ACT_START : PingService.ACT_PING_NOW);   // на зарядці перший пінг піде й так
        Toast.makeText(this, "Збережено. Пінг запущено.", Toast.LENGTH_SHORT).show();
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) askNotif();
        else if (!batteryOk()) askBattery();
    }

    private void pingNow() {
        if (!Cfg.configured(this)) { showForm(true); tvErr.setText("Спершу вкажіть номер черги"); return; }
        if (!Cfg.enabled(this)) Cfg.p(this).edit().putBoolean("enabled", true).commit();
        Cfg.p(this).edit().putLong("ask_ts", System.currentTimeMillis()).apply();
        PingService.start(this, PingService.ACT_PING_NOW);
        Toast.makeText(this, "Надсилаю пінг…", Toast.LENGTH_SHORT).show();
    }

    private void toggle() {
        if (Cfg.enabled(this)) {
            Cfg.p(this).edit().putBoolean("enabled", false).commit();
            Cfg.log(this, "⏸ Пінг зупинено вручну");
            PingService.stop(this);
        } else {
            if (!Cfg.configured(this)) { showForm(true); tvErr.setText("Спершу вкажіть номер черги"); return; }
            Cfg.p(this).edit().putBoolean("enabled", true).commit();
            Cfg.log(this, "▶️ Пінг запущено вручну");
            PingService.start(this, PingService.ACT_PING_NOW);
        }
        refresh();
    }

    private static void put(TextView t, CharSequence s) { if (!s.toString().equals(t.getText().toString())) t.setText(s); }

    private void refresh() {
        SharedPreferences sp = Cfg.p(this);
        long now = System.currentTimeMillis();
        boolean en = Cfg.enabled(this), conf = Cfg.configured(this);
        boolean alive = sp.getBoolean("running", false) && now - sp.getLong("alive", 0) < 70_000L;
        boolean ch = sp.getBoolean("charging", false);
        long last = sp.getLong("last_ts", 0);
        boolean lastOk = sp.getBoolean("last_ok", false);
        if (!conf) { put(tvState, "⚪ Не налаштовано"); tvState.setTextColor(MUTED); }
        else if (!en) { put(tvState, "⏸ Зупинено"); tvState.setTextColor(MUTED); }
        else if (!alive) { put(tvState, "⏳ Запуск…"); tvState.setTextColor(ACCENT); }
        else if (!ch) { put(tvState, "🔌 Чекаю на зарядку"); tvState.setTextColor(ACCENT); }
        else if (last == 0 || now - last > 150_000L) { put(tvState, "⏳ Працює, чекаю на першу відповідь"); tvState.setTextColor(ACCENT); }
        else if (lastOk) { put(tvState, "🟢 Працює"); tvState.setTextColor(OK); }
        else { put(tvState, "🔴 Немає зв'язку з сервером"); tvState.setTextColor(BAD); }

        put(tvPower, ch ? "⚡ Зарядка: є (" + sp.getString("plug", "є") + ")" : "🔌 Зарядка: немає — пінг не надсилається");
        tvPower.setVisibility(conf && en ? View.VISIBLE : View.GONE);
        if (last > 0) {
            long ago = (now - last) / 1000;
            String agoS = ago < 60 ? "щойно" : (ago / 60) + " хв тому";
            put(tvLast, "📡 Останній пінг: " + Cfg.hms(last) + " (" + agoS + ")\n" + (lastOk ? "✅ " : "❌ ") + sp.getString("last_msg", ""));
            tvLast.setTextColor(lastOk ? TEXT : BAD);
        } else put(tvLast, conf ? "📡 Пінгів ще не було" : "Заповніть налаштування нижче і натисніть «Зберегти і запустити».");
        long next = sp.getLong("next", 0);
        put(tvNext, en && ch && next > now ? "⏱ Наступний пінг приблизно через " + Math.max(5, ((next - now) / 5000 + 1) * 5) + " с" : "");
        tvNext.setVisibility(tvNext.getText().length() > 0 ? View.VISIBLE : View.GONE);
        long xt = sp.getLong("x_ts", 0);
        put(tvExtra, !Cfg.extra(this).isEmpty() && xt > 0 ? "Додаткова адреса: " + Cfg.hms(xt) + " " + (sp.getBoolean("x_ok", false) ? "✅" : "❌ " + sp.getString("x_msg", "")) : "");
        tvExtra.setVisibility(tvExtra.getText().length() > 0 ? View.VISIBLE : View.GONE);
        put(tvWhere, conf ? "Черга " + Cfg.queue(this) + " · " + Cfg.board(this) + "\n" + Cfg.pingUrl(this) : "");
        tvWhere.setVisibility(conf ? View.VISIBLE : View.GONE);
        put(btnToggle, en ? "⏸ Зупинити пінг" : "▶️ Запустити пінг");
        btnToggle.setVisibility(conf ? View.VISIBLE : View.GONE);

        boolean bok = batteryOk();
        put(tvBat, bok ? "✅ Обмеження батареї вимкнено — Android не «приспить» застосунок" : "⚠️ Android може призупиняти застосунок у фоні. Натисніть кнопку нижче і дозвольте.");
        btnBat.setVisibility(bok ? View.GONE : View.VISIBLE);
        batBox.setVisibility(Build.VERSION.SDK_INT >= 23 ? View.VISIBLE : View.GONE);
        if (Build.VERSION.SDK_INT >= 33) {
            boolean nok = checkSelfPermission("android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED;
            put(tvNotif, nok ? "✅ Сповіщення дозволені (видно, що пінг працює)" : "⚠️ Сповіщення вимкнені — пінг працює, але його не видно у шторці");
            btnNotif.setVisibility(nok ? View.GONE : View.VISIBLE);
            notifBox.setVisibility(View.VISIBLE);
        } else notifBox.setVisibility(View.GONE);
        if (histBox.getVisibility() == View.VISIBLE) { String h = sp.getString("hist", ""); put(tvHist, h.isEmpty() ? "Подій ще не було" : h); }
    }

    // ---------- дозволи ----------
    private boolean batteryOk() {
        if (Build.VERSION.SDK_INT < 23) return true;
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    private void askBattery() {
        if (Build.VERSION.SDK_INT < 23) return;
        try { startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName()))); }
        catch (ActivityNotFoundException e) {
            try { startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); } catch (Exception x) { openAppSettings(); }
        }
    }

    private void askNotif() {
        if (Build.VERSION.SDK_INT < 33) return;
        if (Cfg.p(this).getInt("notif_asked", 0) >= 2) { openAppSettings(); return; }   // після двох відмов Android більше не питає — відкриваємо налаштування
        Cfg.p(this).edit().putInt("notif_asked", Cfg.p(this).getInt("notif_asked", 0) + 1).apply();
        requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (Cfg.enabled(this) && Cfg.configured(this)) PingService.start(this, PingService.ACT_START);
        if (!batteryOk()) askBattery();
    }

    private void openAppSettings() {
        try { startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))); } catch (Exception ignored) { }
    }

    private void hideKeyboard() {
        try { ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(etQueue.getWindowToken(), 0); } catch (Exception ignored) { }
    }
}
