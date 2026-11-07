// ===================================================================
//  SVITLO — плата-«пінгер» для ESP32-C3 SuperMini, версія 2.0
//
//  Що робить: раз на хвилину каже серверу «я живий» (є світло).
//  Немає пінгів — сервер розуміє, що світла немає.
//
//  Налаштування з телефона, без жодних посилань:
//   1) підключіться до Wi-Fi «svitlo-setup» (без пароля);
//   2) оберіть свою мережу Wi-Fi, введіть пароль;
//   3) вкажіть номер черги (наприклад 3.2) і яка це плата: esp1…esp6 (на одній черзі може бути до 6 плат/телефонів);
//   4) «Зберегти» — плата сама перевірить Wi-Fi і сервер і покаже результат.
//
//  Кнопка BOOT: натиснути 1 раз — увімкнути «svitlo-setup» на 10 хв;
//               тримати 5 секунд — стерти всі налаштування.
//  Оновлення по повітрю: веб-панель → «⬆️ Оновити прошивку» (.bin).
//
//  Бібліотеки: лише вбудовані в пакет «esp32 by Espressif» (нічого не встановлювати).
//  Плата в Arduino IDE: «ESP32C3 Dev Module», USB CDC On Boot: Enabled.
// ===================================================================
#include <WiFi.h>
#include <WebServer.h>
#include <DNSServer.h>
#include <ESPmDNS.h>
#include <Preferences.h>
#include <HTTPClient.h>
#include <WiFiClientSecure.h>
#include <Update.h>
#include <esp_task_wdt.h>
#include <esp_system.h>
#include <time.h>

#define FW_VER         "2.1"
#define AP_NAME        "svitlo-setup"
#define DEFAULT_SERVER "https://svitlo51.a-m-vasylets.workers.dev"   // адреса вашого сервера Cloudflare
#define LED_PIN        8        // вбудований синій світлодіод SuperMini (світиться при LOW)
#define BTN_PIN        9        // кнопка BOOT
#define LED_ON         LOW
#define LED_OFF        HIGH

const unsigned long PING_EVERY   = 60000UL;          // пінг раз на хвилину
const unsigned long EXTRA_SHIFT  = 30000UL;          // додаткова адреса — зі зсувом 30 с
const unsigned long AP_AFTER     = 3UL * 60000UL;    // Wi-Fi немає 3 хв — вмикаємо «svitlo-setup»
const unsigned long REBOOT_NOWIFI= 30UL * 60000UL;   // Wi-Fi немає 30 хв — перезапуск
const unsigned long AP_BUTTON_ON = 10UL * 60000UL;   // точка після натискання кнопки — 10 хв
const int           MAX_FAILS    = 10;               // 10 невдалих пінгів поспіль (≈10 хв) — перезапуск

WebServer   server(80);
DNSServer   dns;
Preferences prefs;

// ---------------- налаштування (зберігаються в пам'яті плати) ----------------
String ssid, pass;            // домашній Wi-Fi
String queue;                 // номер черги, напр. "3.2"
String board = "esp1";        // esp1 … esp6
String pw;                    // пароль (для «svitlo-setup» і цієї сторінки); порожньо = без пароля
String srv = DEFAULT_SERVER;  // адреса сервера
String extra;                 // додаткова адреса (напр. Світлобот), необов'язково

// ---------------- стан ----------------
bool apOn = false;
unsigned long apOffAt = 0;                 // коли вимкнути точку (0 = не вимикати автоматично)
bool wasConn = false;
unsigned long lostAt = 0, upAt = 0;
uint8_t lastReason = 0;                    // чому Wi-Fi відключився (код Espressif)
unsigned long nextPing = 0, nextExtra = 0;
int  pingCode = 0;                         // останній HTTP-код (0 = ще не було)
String pingMsg = "ще не було";
unsigned long pingAt = 0, lastOkAt = 0, okCount = 0, errCount = 0;
int  failStreak = 0;
int  extraCode = 0; String extraMsg = "—"; unsigned long extraAt = 0;
int  testPhase = 0;                        // після «Зберегти»: 1 підключаюсь, 2 перевіряю сервер, 3 готово, 4 черга не знайдена, 5 немає Wi-Fi, 6 сервер не відповідає
String history;                            // історія перезапусків
String bootReason; bool bootLogged = false;
unsigned long restartAt = 0;
unsigned long btnDownAt = 0;
bool otaBusy = false;
bool staPaused = false; unsigned long noClientsSince = 0;   // поки хтось налаштовує через «svitlo-setup», не шукаємо домашню мережу (щоб точка не «підвисала»)

// ---------------- світлодіод (не блокує роботу) ----------------
int ledLeft = 0, ledSpeed = 0; unsigned long ledT = 0; bool ledLvl = LED_OFF;
void blink(int times, int speedMs) { ledLeft = times * 2; ledSpeed = speedMs; ledT = 0; }
void handleLed(unsigned long now, bool conn) {
  if (ledLeft > 0) {
    if (now - ledT >= (unsigned long)ledSpeed) { ledLvl = (ledLvl == LED_ON) ? LED_OFF : LED_ON; digitalWrite(LED_PIN, ledLvl); ledT = now; ledLeft--; }
    return;
  }
  // фон: швидко блимає — чекає налаштування («svitlo-setup»); повільно — шукає Wi-Fi; не світиться — усе добре
  if (!conn) digitalWrite(LED_PIN, ((now / (apOn ? 150UL : 700UL)) % 2) ? LED_ON : LED_OFF);
  else digitalWrite(LED_PIN, LED_OFF);
}

// ---------------- допоміжне ----------------
String jsonEsc(const String &s) {
  String o; o.reserve(s.length() + 8);
  for (size_t i = 0; i < s.length(); i++) {
    char c = s[i];
    if (c == '"' || c == '\\') { o += '\\'; o += c; }
    else if (c == '\n') o += "\\n";
    else if ((uint8_t)c < 0x20) o += ' ';
    else o += c;
  }
  return o;
}
String normQueue(String q) {             // «3/2», «3,2», « 3.2 » → «3.2»
  q.trim(); q.replace("/", "."); q.replace(",", "."); q.replace(" ", "");
  String o;
  for (size_t i = 0; i < q.length(); i++) { char c = q[i]; if (isalnum((unsigned char)c) || c == '.' || c == '-' || c == '_') o += c; }
  return o;
}
bool validBoard(const String& b) {         // esp1 … esp6
  return b.length() == 4 && b.startsWith("esp") && b.charAt(3) >= '1' && b.charAt(3) <= '6';
}

String hostName() {                      // svitlo-3-2-esp1  →  http://svitlo-3-2-esp1.local
  String h = "svitlo-" + (queue.length() ? queue : String("new")) + "-" + board;
  h.replace(".", "-"); h.replace("_", "-"); h.toLowerCase();
  return h;
}
String pingUrl() { return srv + "/p/" + queue + "/" + board; }
String fmtNow() {
  struct tm t;
  if (!getLocalTime(&t, 5)) return "";
  char b[24]; strftime(b, sizeof(b), "%d.%m %H:%M", &t);
  return String(b);
}

// ---------------- пам'ять ----------------
void loadConfig() {
  prefs.begin("svitlo", false);
  ssid  = prefs.getString("ssid", "");
  pass  = prefs.getString("pass", "");
  queue = prefs.getString("q", "");
  board = prefs.getString("b", "esp1");
  pw    = prefs.getString("pw", "");
  srv   = prefs.getString("srv", DEFAULT_SERVER);
  extra = prefs.getString("x", "");
  // перехід з попередньої прошивки v1.x: там зберігалась повна адреса «…/p/3.2»
  if (!queue.length()) {
    String u1 = prefs.getString("u1", "");
    int p = u1.indexOf("/p/");
    if (p > 0) {
      String q = u1.substring(p + 3); int s = q.indexOf('/'); if (s >= 0) q = q.substring(0, s);
      queue = normQueue(q); srv = u1.substring(0, p);
      board = prefs.getString("i1", "esp1");
      extra = prefs.getString("u2", "");
    }
  }
  history = prefs.getString("hist", "");
  prefs.end();
  if (!validBoard(board)) board = "esp1";
  if (!srv.startsWith("http")) srv = DEFAULT_SERVER;
}
void saveConfig() {
  prefs.begin("svitlo", false);
  prefs.putString("ssid", ssid); prefs.putString("pass", pass);
  prefs.putString("q", queue);   prefs.putString("b", board);
  prefs.putString("pw", pw);     prefs.putString("srv", srv);
  prefs.putString("x", extra);
  prefs.end();
}
void restartSoon(const char *why, unsigned long ms) {     // причина запам'ятовується і видна в «Історії»
  prefs.begin("svitlo", false); prefs.putString("why", why); prefs.end();
  restartAt = millis() + ms;
}
void restartNow(const char *why) { restartSoon(why, 0); delay(100); ESP.restart(); }

String resetReasonText() {
  switch (esp_reset_reason()) {
    case ESP_RST_POWERON:  return "увімкнення живлення (з'явилось світло)";
    case ESP_RST_SW:       return "програмний перезапуск";
    case ESP_RST_PANIC:    return "збій програми";
    case ESP_RST_INT_WDT:
    case ESP_RST_TASK_WDT:
    case ESP_RST_WDT:      return "сторожовий таймер (зависання)";
    case ESP_RST_BROWNOUT: return "просідання живлення";
    case ESP_RST_EXT:      return "кнопка RESET";
    default:               return "інше (" + String((int)esp_reset_reason()) + ")";
  }
}
void logBootOnce() {                       // запис в історію, коли вже відомий час (NTP)
  if (bootLogged) return;
  String t = fmtNow();
  if (!t.length()) return;
  bootLogged = true;
  history = t + " — " + bootReason + "\n" + history;
  int cut = 0, lines = 0;
  for (size_t i = 0; i < history.length(); i++) if (history[i] == '\n' && ++lines == 8) { cut = i + 1; break; }
  if (cut) history = history.substring(0, cut);
  prefs.begin("svitlo", false); prefs.putString("hist", history); prefs.end();
}

// ---------------- мережа ----------------
void onWifiEvent(WiFiEvent_t event, WiFiEventInfo_t info) {
  if (event == ARDUINO_EVENT_WIFI_STA_DISCONNECTED && !staPaused) lastReason = info.wifi_sta_disconnected.reason;
}
String wifiProblem() {                     // зрозуміла причина, чому не підключається
  switch (lastReason) {
    case 0:   return "";
    case 2: case 15: case 202: case 204: case 205: return "невірний пароль Wi-Fi";
    case 201: return "мережу «" + ssid + "» не знайдено (далеко або вимкнена)";
    default:  return "не вдається підключитися (код " + String(lastReason) + ")";
  }
}
void startAp(unsigned long forMs) {
  if (!apOn) {
    WiFi.mode(WIFI_AP_STA);
    if (pw.length() >= 8) WiFi.softAP(AP_NAME, pw.c_str()); else WiFi.softAP(AP_NAME);
    dns.start(53, "*", WiFi.softAPIP());
    WiFi.setTxPower(WIFI_POWER_8_5dBm);
    apOn = true;
    Serial.println("Точка «" AP_NAME "» увімкнена: http://192.168.4.1");
  }
  apOffAt = forMs ? millis() + forMs : 0;
}
void stopAp() {
  if (!apOn) return;
  dns.stop(); WiFi.softAPdisconnect(true); WiFi.mode(WIFI_STA); WiFi.setTxPower(WIFI_POWER_8_5dBm);
  apOn = false; apOffAt = 0;
  Serial.println("Точка вимкнена");
}
void connectWifi() {
  lastReason = 0; staPaused = false;
  if (!ssid.length()) return;
  WiFi.setAutoReconnect(true);
  WiFi.begin(ssid.c_str(), pass.c_str());
}
bool internetOk() {                        // інтернет є? (як у старій прошивці)
  WiFiClient c; HTTPClient h;
  if (!h.begin(c, "http://clients3.google.com/generate_204")) return false;
  h.setConnectTimeout(3000); h.setTimeout(3000);
  int code = h.GET(); h.end();
  return code == 204;
}
int httpGet(const String &url) {
  WiFiClientSecure sc; WiFiClient pc; HTTPClient h;
  sc.setInsecure();                        // без перевірки сертифіката — простіше і надійніше для плати
  bool ok = url.startsWith("https") ? h.begin(sc, url) : h.begin(pc, url);
  if (!ok) return -100;
  h.setConnectTimeout(6000); h.setTimeout(8000);
  h.setFollowRedirects(HTTPC_DISABLE_FOLLOW_REDIRECTS);
  int code = h.GET(); h.end();
  return code;
}

// ---------------- пінг ----------------
void doPing() {
  esp_task_wdt_reset();
  pingAt = millis();
  if (!queue.length()) { pingCode = 0; pingMsg = "не вказано номер черги"; blink(5, 100); if (testPhase == 2) testPhase = 4; return; }
  int code = httpGet(pingUrl());
  pingCode = code;
  if (code >= 200 && code < 400) {
    pingMsg = "OK"; okCount++; lastOkAt = millis(); failStreak = 0; blink(1, 400);
    if (testPhase == 2) { testPhase = 3; if (apOn) apOffAt = millis() + 60000UL; }   // усе працює — точка вимкнеться за хвилину
  } else if (code == 404) {                // сервер працює, але такої черги на ньому немає
    pingMsg = "черга «" + queue + "» не знайдена на сервері — перевірте номер"; errCount++; failStreak = 0; blink(5, 100);
    if (testPhase == 2) { testPhase = 4; if (apOn) apOffAt = millis() + 600000UL; }   // точка ще 10 хв — щоб виправити номер
  } else {
    errCount++; failStreak++;
    bool net = internetOk();
    pingMsg = net ? (code > 0 ? "сервер відповів помилкою " + String(code) : "сервер не відповідає (" + String(code) + ")") : "немає інтернету (Wi-Fi є)";
    blink(net ? 4 : 3, 150);
    if (testPhase == 2) { testPhase = 6; if (apOn) apOffAt = millis() + 600000UL; }
  }
  Serial.printf("[ПІНГ] %s -> %d (%s)\n", pingUrl().c_str(), code, pingMsg.c_str());
}
void doExtra() {
  if (!extra.startsWith("http")) return;
  esp_task_wdt_reset();
  extraCode = httpGet(extra); extraAt = millis();
  extraMsg = (extraCode >= 200 && extraCode < 400) ? "OK" : (extraCode > 0 ? "помилка " + String(extraCode) : "не відповідає (" + String(extraCode) + ")");
  Serial.printf("[ДОД.] %s -> %d\n", extra.c_str(), extraCode);
}

// ---------------- веб-панель ----------------
bool authOk() {
  if (!pw.length()) return true;
  if (apOn && server.client().localIP() == WiFi.softAPIP()) return true;   // через «svitlo-setup» — мережа вже з паролем
  if (server.authenticate("admin", pw.c_str())) return true;
  server.requestAuthentication(BASIC_AUTH, "Svitlo", "Потрібен пароль (логін: admin)");
  return false;
}

// ---------------- сторінки веб-панелі (HTML + JavaScript) ----------------
static const char PAGE_MAIN[] PROGMEM =
  "<!DOCTYPE html><html lang=\"uk\"><head><meta charset=\"utf-8\">\n"
  "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>Svitlo</title><style>\n"
  "body{font-family:-apple-system,Segoe UI,Roboto,sans-serif;background:#121212;color:#e8e8e8;margin:0;padding:14px}\n"
  ".w{max-width:440px;margin:auto}.logo{text-align:center;font-size:22px;font-weight:700;color:#f39c12;letter-spacing:2px;margin:6px 0 14px}\n"
  ".logo span{color:#fff}.logo small{display:block;font-size:12px;color:#777;letter-spacing:0;font-weight:400}\n"
  ".c{background:#1e1e1e;border-radius:12px;padding:14px;margin-bottom:12px}.c h3{margin:0 0 10px;font-size:16px}\n"
  ".row{display:flex;justify-content:space-between;gap:8px;padding:5px 0;border-bottom:1px solid #2a2a2a;font-size:14px}.row:last-child{border:0}\n"
  ".row b{color:#9a9a9a;font-weight:400}.ok{color:#2ecc71}.bad{color:#e74c3c}.mid{color:#f39c12}\n"
  "label{display:block;font-size:13px;color:#9a9a9a;margin:10px 0 4px}\n"
  "input{width:100%;box-sizing:border-box;padding:12px;background:#121212;color:#fff;border:1px solid #3a3a3a;border-radius:8px;font-size:16px}\n"
  ".net{display:flex;justify-content:space-between;align-items:center;padding:11px 12px;background:#2a2a2a;border-radius:8px;margin:6px 0;cursor:pointer;font-size:15px}\n"
  ".net.sel{outline:2px solid #2980b9;background:#1d3446}.net i{font-style:normal;color:#9a9a9a;font-size:13px}\n"
  ".seg{display:grid;grid-template-columns:repeat(3,1fr);gap:8px}.seg div{text-align:center;padding:12px;border-radius:8px;background:#2a2a2a;cursor:pointer;font-weight:600}\n"
  ".seg .on{background:#2980b9;color:#fff}\n"
  ".btn{display:block;width:100%;box-sizing:border-box;padding:13px;margin-top:10px;border:0;border-radius:10px;font-size:16px;font-weight:600;color:#fff;background:#2980b9;text-align:center;text-decoration:none;cursor:pointer}\n"
  ".g{background:#3a3a3a}.o{background:#d35400}.r{background:#c0392b}.small{font-size:13px;color:#8a8a8a;line-height:1.5}\n"
  "details summary{cursor:pointer;color:#9a9a9a;padding:4px 0}.pw{position:relative}.pw span{position:absolute;right:12px;top:11px;cursor:pointer}\n"
  ".step{padding:8px 0;font-size:15px}.hide{display:none}.links{display:flex;gap:8px}.links a{flex:1;font-size:13px;padding:10px 4px}\n"
  "pre{white-space:pre-wrap;font-size:12px;color:#7fdb7f;background:#000;padding:8px;border-radius:6px;margin:6px 0 0}\n"
  "</style></head><body><div class=\"w\">\n"
  "<div class=\"logo\">Svitlo<span>VSlavutychi</span><small id=\"ver\"></small></div>\n"
  "\n"
  "<div class=\"c hide\" id=\"hello\"><h3>👋 Налаштування за хвилину</h3><div class=\"small\">1. Оберіть свою мережу Wi-Fi і введіть пароль.<br>2. Вкажіть номер черги і яка це плата.<br>3. Натисніть «Зберегти» — плата сама все перевірить.</div></div>\n"
  "\n"
  "<div class=\"c hide\" id=\"stat\"><h3>📊 Стан</h3>\n"
  "<div class=\"row\"><b>Черга</b><span id=\"sQ\"></span></div>\n"
  "<div class=\"row\"><b>Wi-Fi</b><span id=\"sW\"></span></div>\n"
  "<div class=\"row\"><b>Сервер</b><span id=\"sP\"></span></div>\n"
  "<div class=\"row hide\" id=\"rX\"><b>Дод. адреса</b><span id=\"sX\"></span></div>\n"
  "<div class=\"row\"><b>Працює</b><span id=\"sU\"></span></div>\n"
  "<button class=\"btn g\" onclick=\"pingNow(this)\">🔔 Перевірити зараз</button><div class=\"small\" id=\"hostInfo\" style=\"margin-top:10px\"></div></div>\n"
  "\n"
  "<div class=\"c hide\" id=\"test\"><h3>⏳ Перевірка</h3><div id=\"steps\"></div>\n"
  "<button class=\"btn g hide\" id=\"tback\" onclick=\"showForm()\">↩️ Назад до налаштувань</button></div>\n"
  "\n"
  "<a class=\"btn hide\" id=\"edit\" onclick=\"openForm()\">✏️ Змінити налаштування</a>\n"
  "<form class=\"c hide\" id=\"form\" onsubmit=\"return save()\" style=\"margin-top:12px\"><h3>📶 Wi-Fi</h3>\n"
  "<div id=\"nets\"><div class=\"small\">Шукаю мережі…</div></div>\n"
  "<a class=\"btn g\" style=\"padding:9px;font-size:14px\" onclick=\"scan()\">🔄 Оновити список</a>\n"
  "<label>Назва мережі</label><input id=\"ssid\" autocomplete=\"off\" placeholder=\"оберіть зі списку вище\">\n"
  "<label>Пароль Wi-Fi</label><div class=\"pw\"><input id=\"pass\" type=\"password\" autocomplete=\"off\"><span onclick=\"tg('pass')\">👁</span></div>\n"
  "<div class=\"small\" id=\"passHint\"></div>\n"
  "<h3 style=\"margin-top:18px\">💡 Черга і плата</h3>\n"
  "<label>Номер черги</label><input id=\"q\" inputmode=\"decimal\" placeholder=\"наприклад 3.2\">\n"
  "<label>Яка це плата <small>(якщо на черзі вже є esp1 — оберіть інший номер)</small></label><div class=\"seg\">"
  "<div id=\"b1\" onclick=\"setB('esp1')\">esp1</div><div id=\"b2\" onclick=\"setB('esp2')\">esp2</div><div id=\"b3\" onclick=\"setB('esp3')\">esp3</div>"
  "<div id=\"b4\" onclick=\"setB('esp4')\">esp4</div><div id=\"b5\" onclick=\"setB('esp5')\">esp5</div><div id=\"b6\" onclick=\"setB('esp6')\">esp6</div></div>\n"
  "<details style=\"margin-top:14px\"><summary>⚙️ Додатково</summary>\n"
  "<label>Пароль для «svitlo-setup» і цієї сторінки (не менше 8 символів; порожньо — без пароля). Для точки діє з наступного її ввімкнення. Забули — тримайте BOOT 5 с.</label><div class=\"pw\"><input id=\"pw\" type=\"password\" autocomplete=\"off\"><span onclick=\"tg('pw')\">👁</span></div>\n"
  "<label>Адреса сервера</label><input id=\"srv\" autocomplete=\"off\">\n"
  "<label>Додаткова адреса (необов'язково, напр. Світлобот) — простий запит раз на хвилину</label><input id=\"x\" autocomplete=\"off\" placeholder=\"https://...\">\n"
  "</details>\n"
  "<button class=\"btn\" type=\"submit\">💾 Зберегти</button><div class=\"small bad\" id=\"err\"></div></form>\n"
  "\n"
  "<div id=\"foot\"><div class=\"links\" style=\"margin-top:12px\"><a class=\"btn o\" href=\"/ota\">⬆️ Оновити прошивку</a><a class=\"btn g\" href=\"#\" onclick=\"return act('/reboot','Перезавантажити плату?')\">♻️ Перезапуск</a><a class=\"btn r\" href=\"#\" onclick=\"return act('/reset','Стерти ВСІ налаштування? Плата знову роздаватиме svitlo-setup.')\">🗑 Скинути</a></div>\n"
  "<div class=\"c\" style=\"margin-top:12px\"><details><summary>📜 Історія перезапусків</summary><pre id=\"hist\"></pre></details></div></div>\n"
  "</div><script>\n"
  "let D={},B='esp1',sel='',testing=false,inited=false,fails=0,formOpen=false,scanned=false;\n"
  "const $=i=>document.getElementById(i),show=(i,v)=>$(i).classList.toggle('hide',!v);\n"
  "function tg(i){$(i).type=$(i).type=='password'?'text':'password'}\n"
  "function setB(b){B=b;for(let i=1;i<=6;i++)$('b'+i).className=b=='esp'+i?'on':''}\n"
  "function bars(r){return r>-55?'▂▄▆█':r>-67?'▂▄▆':r>-78?'▂▄':'▂'}\n"
  "function pick(s,lock){sel=s;$('ssid').value=s;document.querySelectorAll('.net').forEach(e=>e.classList.toggle('sel',e.dataset.s==s));\n"
  " $('passHint').textContent=lock?'':'Відкрита мережа — пароль не потрібен.';if(lock)$('pass').focus()}\n"
  "function scan(){$('nets').innerHTML='<div class=\"small\">Шукаю мережі…</div>';poll()\n"
  " function poll(){fetch('/scan').then(r=>r.json()).then(L=>{if(L.busy)return setTimeout(poll,1200);\n"
  "  if(!L.length){$('nets').innerHTML='<div class=\"small\">Мереж не знайдено. Натисніть «Оновити список».</div>';return}\n"
  "  $('nets').innerHTML=L.map(n=>`<div class=\"net${n.s==sel?' sel':''}\" data-s=\"${n.s.replace(/\"/g,'&quot;')}\" onclick='pick(${JSON.stringify(n.s)},${n.l})'><span>${n.l?'🔒 ':''}${n.s.replace(/</g,'&lt;')}</span><i>${bars(n.r)}</i></div>`).join('')\n"
  " }).catch(()=>setTimeout(poll,2000))}}\n"
  "function ago(s){return s<0?'ще не було':s<90?s+' с тому':Math.round(s/60)+' хв тому'}\n"
  "function upt(m){return m<60?m+' хв':Math.floor(m/60)+' год '+(m%60)+' хв'}\n"
  "function render(){\n"
  " $('ver').textContent='плата v'+D.ver+(D.configured?' · '+D.host:'');\n"
  " if(!inited&&(!D.configured||D.via_ap))formOpen=true;\n"
  " show('hello',!D.configured&&!testing);show('stat',D.configured&&!testing);show('form',formOpen&&!testing);show('edit',D.configured&&!formOpen&&!testing);show('foot',!testing);\n"
  " if(formOpen&&!scanned){scanned=true;scan()}\n"
  " $('sQ').innerHTML=D.q?`<b style=\"color:#fff\">${D.q}</b> · ${D.b}`:'<span class=\"bad\">не вказано</span>';\n"
  " $('sW').innerHTML=D.wifi?`<span class=\"ok\">✅ ${D.ssid}</span> (${D.rssi} dBm)`:D.ssid?`<span class=\"bad\">❌ ${D.problem||'підключаюсь до «'+D.ssid+'»…'}</span>`:'<span class=\"mid\">не налаштовано</span>';\n"
  " $('sP').innerHTML=D.ping.code==0?`<span class=\"mid\">${D.ping.msg}</span>`:(D.ping.ok?`<span class=\"ok\">✅ ${ago(D.ping.okAgo)}</span>`:`<span class=\"bad\">❌ ${D.ping.msg}</span>`);\n"
  " show('rX',!!D.x);if(D.x)$('sX').innerHTML=D.xm=='OK'?'<span class=\"ok\">✅ OK</span>':`<span class=\"bad\">${D.xm}</span>`;\n"
  " $('sU').textContent=upt(D.up)+(D.temp?' · '+D.temp+' °C':'');\n"
  " $('hist').textContent=D.hist||'поки порожньо';\n"
  " $('hostInfo').innerHTML=D.wifi?`Ця сторінка вдома: <b>http://${D.ip}</b> або <b>http://${D.host}.local</b>`:'';\n"
  " if(!inited){inited=true;$('ssid').value=D.ssid||'';sel=D.ssid||'';$('q').value=D.q||'';setB(D.b||'esp1');$('pw').value=D.pw||'';$('srv').value=D.srv||'';$('x').value=D.x||'';\n"
  "  if(D.ssid)$('pass').placeholder='не змінювати'}\n"
  " if(testing)renderTest()}\n"
  "function step(icon,text){return `<div class=\"step\">${icon} ${text}</div>`}\n"
  "function renderTest(){let s='',t=D.test;\n"
  " if(t==1)s+=D.problem?step('❌',`<span class=\"bad\">«${D.ssid}»: ${D.problem}</span>`)+'<div class=\"small\">Перевірте назву мережі й пароль (великі/малі літери мають значення) і спробуйте ще раз.</div>':step('⏳',`Підключаюсь до «${D.ssid}»…`);\n"
  " else s+=step('✅',`Wi-Fi «${D.ssid}» підключено (IP ${D.ip})`);\n"
  " if(t==2)s+=step('⏳','Перевіряю сервер…');\n"
  " if(t==3)s+=step('✅',`Сервер бачить плату: черга <b>${D.q}</b>, ${D.b}`)+step('🎉','<b class=\"ok\">Готово! Плата працює.</b>')+`<div class=\"small\">${D.ap?'Точка «svitlo-setup» вимкнеться за хвилину — підключіть телефон назад до домашнього Wi-Fi.<br>':''}Панель плати вдома: <b>http://${D.ip}</b> або <b>http://${D.host}.local</b></div>`;\n"
  " if(t==4)s+=step('❌',`<span class=\"bad\">${D.ping.msg}</span>`);\n"
  " if(t==6)s+=step('❌',`<span class=\"bad\">${D.ping.msg}</span>`);\n"
  " $('steps').innerHTML=s;const fail=t==4||t==6||(t==1&&D.problem);show('tback',fail);\n"
  " $('test').querySelector('h3').textContent=t==3?'✅ Перевірка':fail?'❌ Не вдалося':'⏳ Перевірка';}\n"
  "function load(){fetch('/api').then(r=>r.json()).then(j=>{D=j;fails=0;render()}).catch(()=>{if(testing&&++fails>2)$('steps').innerHTML+=step('📶','<span class=\"mid\">Зв\\'язок з платою на мить перервався — це нормально під час підключення. Зачекайте…</span>')})}\n"
  "function showForm(){testing=false;formOpen=true;show('test',0);render()}\n"
  "function openForm(){formOpen=true;render()}\n"
  "function save(){const q=$('q').value.trim(),pw=$('pw').value,ss=$('ssid').value.trim();$('err').textContent='';\n"
  " if(!ss)return $('err').textContent='Оберіть мережу Wi-Fi.',false;\n"
  " if(!q)return $('err').textContent='Вкажіть номер черги, наприклад 3.2',false;\n"
  " if(pw&&pw.length<8)return $('err').textContent='Пароль має бути не менше 8 символів (або порожній).',false;\n"
  " const f=new URLSearchParams({ssid:ss,pass:$('pass').value,q:q,b:B,pw:pw,srv:$('srv').value.trim(),x:$('x').value.trim()});\n"
  " fetch('/save',{method:'POST',body:f}).then(r=>r.json()).then(j=>{if(!j.ok)return $('err').textContent=j.err;\n"
  "  testing=true;show('form',0);show('stat',0);show('test',1);$('test').querySelector('h3').textContent='⏳ Перевірка';D.test=1;D.problem='';D.ssid=ss;$('steps').innerHTML=step('⏳','Зберігаю…');setTimeout(load,800)})\n"
  " .catch(()=>$('err').textContent='Немає зв\\'язку з платою');return false}\n"
  "function pingNow(b){b.textContent='⏳ Перевіряю…';fetch('/ping',{method:'POST'}).then(r=>r.json()).then(j=>{D=j;render();b.textContent='🔔 Перевірити зараз'})}\n"
  "function act(u,q){if(confirm(q))fetch(u,{method:'POST'}).then(()=>document.body.innerHTML='<div class=\"w\"><div class=\"c\">♻️ Плата перезапускається… Через ~20 с оновіть сторінку.</div></div>');return false}\n"
  "load();setInterval(load,2000);\n"
  "</script></body></html>";

static const char PAGE_OTA[] PROGMEM =
  "<!DOCTYPE html><html lang=\"uk\"><head><meta charset=\"utf-8\">\n"
  "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>Svitlo — оновлення</title><style>\n"
  "body{font-family:-apple-system,Segoe UI,Roboto,sans-serif;background:#121212;color:#e8e8e8;margin:0;padding:14px}\n"
  ".w{max-width:440px;margin:auto}.c{background:#1e1e1e;border-radius:12px;padding:14px;margin-bottom:12px}\n"
  ".btn{display:block;width:100%;box-sizing:border-box;padding:13px;margin-top:10px;border:0;border-radius:10px;font-size:16px;font-weight:600;color:#fff;background:#d35400;text-align:center;text-decoration:none}\n"
  ".g{background:#3a3a3a}.small{font-size:13px;color:#8a8a8a;line-height:1.5}input{width:100%;margin:10px 0;color:#ccc}\n"
  ".bar{height:14px;background:#2a2a2a;border-radius:7px;overflow:hidden;margin-top:12px}.bar div{height:100%;width:0;background:#2ecc71;transition:width .2s}\n"
  ".ok{color:#2ecc71}.bad{color:#e74c3c}</style></head><body><div class=\"w\">\n"
  "<div style=\"text-align:center;font-size:22px;font-weight:700;color:#f39c12;letter-spacing:2px;margin:6px 0 14px\">Svitlo<span style=\"color:#fff\">VSlavutychi</span></div>\n"
  "<div class=\"c\"><h3 style=\"margin-top:0\">⬆️ Оновлення прошивки по повітрю</h3>\n"
  "<div class=\"small\">1. В Arduino IDE: <b>Скетч → Експортувати скомпільований двійковий файл</b>.<br>2. Оберіть тут файл <b>Svitlo_ESP32C3.ino.bin</b> (не «bootloader» і не «partitions»).<br>3. «Оновити» — плата сама перезапуститься. Налаштування збережуться.</div>\n"
  "<input type=\"file\" id=\"f\" accept=\".bin\"><button class=\"btn\" onclick=\"up()\">⬆️ Оновити</button>\n"
  "<div class=\"bar\"><div id=\"p\"></div></div><div id=\"m\" class=\"small\" style=\"margin-top:8px\"></div></div>\n"
  "<a class=\"btn g\" href=\"/\">🔙 Назад</a></div><script>\n"
  "function up(){const f=document.getElementById('f').files[0],m=document.getElementById('m');\n"
  " if(!f)return m.textContent='Спершу оберіть файл .bin';\n"
  " if(!/\\.bin$/i.test(f.name))return m.textContent='Потрібен файл з розширенням .bin';\n"
  " const x=new XMLHttpRequest(),d=new FormData();d.append('update',f,f.name);\n"
  " x.upload.onprogress=e=>{if(e.lengthComputable){const p=Math.round(e.loaded*100/e.total);document.getElementById('p').style.width=p+'%';m.textContent='Завантаження: '+p+'%'}};\n"
  " x.onload=()=>{if(x.status==200){m.innerHTML='<b class=\"ok\">✅ Оновлено! Плата перезапускається…</b> Через ~20 с відкрийте панель знову.';setTimeout(()=>location.href='/',20000)}else m.innerHTML='<b class=\"bad\">❌ '+x.responseText+'</b>'};\n"
  " x.onerror=()=>m.innerHTML='<b class=\"bad\">❌ Зв\\'язок перервався. Спробуйте ще раз.</b>';\n"
  " x.open('POST','/update');x.send(d);m.textContent='Завантаження…'}\n"
  "</script></body></html>";

String apiJson() {
  bool conn = WiFi.status() == WL_CONNECTED;
  unsigned long now = millis();
  String j; j.reserve(900);
  j += "{\"ver\":\"" FW_VER "\",\"configured\":"; j += (ssid.length() && queue.length()) ? "true" : "false";
  j += ",\"ssid\":\"" + jsonEsc(ssid) + "\",\"wifi\":"; j += conn ? "true" : "false";
  j += ",\"ip\":\"" + (conn ? WiFi.localIP().toString() : String("")) + "\",\"rssi\":" + String(conn ? WiFi.RSSI() : 0);
  j += ",\"problem\":\"" + jsonEsc(conn ? String("") : wifiProblem()) + "\"";
  j += ",\"q\":\"" + jsonEsc(queue) + "\",\"b\":\"" + board + "\",\"host\":\"" + hostName() + "\"";
  j += ",\"pw\":\"" + jsonEsc(pw) + "\",\"srv\":\"" + jsonEsc(srv) + "\",\"x\":\"" + jsonEsc(extra) + "\"";
  j += ",\"ping\":{\"code\":" + String(pingCode) + ",\"ok\":"; j += (pingCode >= 200 && pingCode < 400) ? "true" : "false";
  j += ",\"msg\":\"" + jsonEsc(pingMsg) + "\",\"okAgo\":" + String(lastOkAt ? (long)((now - lastOkAt) / 1000) : -1L);
  j += ",\"okN\":" + String(okCount) + ",\"errN\":" + String(errCount) + "}";
  j += ",\"xm\":\"" + jsonEsc(extraMsg) + "\",\"test\":" + String(testPhase) + ",\"ap\":"; j += apOn ? "true" : "false";
  j += ",\"via_ap\":"; j += (apOn && server.client().localIP() == WiFi.softAPIP()) ? "true" : "false";
  j += ",\"up\":" + String(now / 60000UL) + ",\"temp\":" + String((int)temperatureRead()) + ",\"hist\":\"" + jsonEsc(history) + "\"}";
  return j;
}
void handleRoot()  { if (!authOk()) return; server.send_P(200, "text/html; charset=utf-8", PAGE_MAIN); }
void handleApi()   { if (!authOk()) return; server.send(200, "application/json", apiJson()); }
void handleOtaPage(){ if (!authOk()) return; server.send_P(200, "text/html; charset=utf-8", PAGE_OTA); }
void handleScan() {
  if (!authOk()) return;
  int n = WiFi.scanComplete();
  if (n == WIFI_SCAN_FAILED) { WiFi.scanNetworks(true); server.send(200, "application/json", "{\"busy\":true}"); return; }
  if (n == WIFI_SCAN_RUNNING) { server.send(200, "application/json", "{\"busy\":true}"); return; }
  String j = "["; int cnt = 0;
  for (int i = 0; i < n && cnt < 15; i++) {             // без дублікатів (лишаємо найсильніший сигнал), від найсильнішого
    int best = -1;
    for (int k = 0; k < n; k++) {
      String s = WiFi.SSID(k);
      if (!s.length() || j.indexOf("\"s\":\"" + jsonEsc(s) + "\"") >= 0) continue;
      if (best < 0 || WiFi.RSSI(k) > WiFi.RSSI(best)) best = k;
    }
    if (best < 0) break;
    if (cnt++) j += ",";
    j += "{\"s\":\"" + jsonEsc(WiFi.SSID(best)) + "\",\"r\":" + String(WiFi.RSSI(best)) + ",\"l\":" + String(WiFi.encryptionType(best) == WIFI_AUTH_OPEN ? 0 : 1) + "}";
  }
  j += "]";
  WiFi.scanDelete();                                       // наступного разу — свіжий пошук
  server.send(200, "application/json", j);
}
void handleSave() {
  if (!authOk()) return;
  String nSsid = server.arg("ssid"); nSsid.trim();
  String nPass = server.arg("pass");
  String nQ = normQueue(server.arg("q"));
  String nB = validBoard(server.arg("b")) ? server.arg("b") : String("esp1");
  String nPw = server.arg("pw");
  String nSrv = server.arg("srv"); nSrv.trim(); while (nSrv.endsWith("/")) nSrv.remove(nSrv.length() - 1);
  String nX = server.arg("x"); nX.trim();
  if (!nSsid.length()) { server.send(200, "application/json", "{\"ok\":false,\"err\":\"Оберіть мережу Wi-Fi\"}"); return; }
  if (!nQ.length()) { server.send(200, "application/json", "{\"ok\":false,\"err\":\"Вкажіть номер черги, наприклад 3.2\"}"); return; }
  if (nPw.length() && nPw.length() < 8) { server.send(200, "application/json", "{\"ok\":false,\"err\":\"Пароль має бути не менше 8 символів\"}"); return; }
  if (!nSrv.startsWith("http")) nSrv = DEFAULT_SERVER;
  bool wifiChanged = nSsid != ssid || (nPass.length() && nPass != pass);
  if (nSsid != ssid && !nPass.length()) pass = "";                  // нова відкрита мережа
  else if (nPass.length()) pass = nPass;
  bool hostChanged = nQ != queue || nB != board;
  ssid = nSsid; queue = nQ; board = nB; pw = nPw; srv = nSrv; extra = nX;
  saveConfig();
  server.send(200, "application/json", "{\"ok\":true}");
  if (hostChanged) { MDNS.end(); MDNS.begin(hostName().c_str()); MDNS.addService("http", "tcp", 80); }
  pingCode = 0; pingMsg = "ще не було"; failStreak = 0;
  if (wifiChanged || WiFi.status() != WL_CONNECTED) {
    testPhase = 1;
    WiFi.disconnect(); delay(100);
    wasConn = false; lostAt = millis();
    connectWifi();
  } else { testPhase = 2; nextPing = millis(); }          // Wi-Fi той самий — одразу перевіряємо сервер
  nextExtra = millis() + EXTRA_SHIFT;
}
void handlePing()  { if (!authOk()) return; if (WiFi.status() == WL_CONNECTED) { doPing(); doExtra(); } server.send(200, "application/json", apiJson()); }
void handleReboot(){ if (!authOk()) return; server.send(200, "text/plain", "OK"); restartSoon("перезапуск з веб-панелі", 1500); }
void handleReset() {
  if (!authOk()) return;
  server.send(200, "text/plain", "OK");
  prefs.begin("svitlo", false); prefs.clear(); prefs.end();
  WiFi.disconnect(true, true);
  restartSoon("скидання налаштувань", 1500);
}
void handleUpdateDone() {
  if (!authOk()) return;
  bool ok = !Update.hasError();
  server.sendHeader("Connection", "close");
  server.send(ok ? 200 : 500, "text/plain; charset=utf-8", ok ? "OK" : String("Помилка оновлення: ") + Update.errorString());
  otaBusy = false;
  if (ok) restartSoon("оновлення прошивки", 1500);
}
void handleUpdateUpload() {
  if (pw.length() && !(apOn && server.client().localIP() == WiFi.softAPIP()) && !server.authenticate("admin", pw.c_str())) return;
  esp_task_wdt_reset();
  HTTPUpload &u = server.upload();
  if (u.status == UPLOAD_FILE_START) { otaBusy = true; Serial.printf("OTA: %s\n", u.filename.c_str()); if (!Update.begin(UPDATE_SIZE_UNKNOWN)) Update.printError(Serial); }
  else if (u.status == UPLOAD_FILE_WRITE) { if (Update.write(u.buf, u.currentSize) != u.currentSize) Update.printError(Serial); }
  else if (u.status == UPLOAD_FILE_END) { if (Update.end(true)) Serial.printf("OTA: OK, %u байт\n", u.totalSize); else Update.printError(Serial); }
  else if (u.status == UPLOAD_FILE_ABORTED) { Update.abort(); otaBusy = false; }
}
void handleNotFound() {
  if (apOn) {                                   // «captive portal»: телефон сам відкриє сторінку налаштувань
    server.sendHeader("Location", "http://192.168.4.1/", true);
    server.send(302, "text/plain", "");
  } else server.send(404, "text/plain", "Not found");
}

// ---------------- кнопка BOOT ----------------
void handleButton(unsigned long now) {
  bool down = digitalRead(BTN_PIN) == LOW;
  if (down) {
    if (!btnDownAt) btnDownAt = now;
    if (now - btnDownAt > 5000) {                // тримали 5 с — стерти все
      blink(10, 50);
      prefs.begin("svitlo", false); prefs.clear(); prefs.end();
      WiFi.disconnect(true, true);
      restartNow("скидання кнопкою BOOT");
    }
  } else if (btnDownAt) {
    if (now - btnDownAt > 50 && now - btnDownAt < 2000) { startAp(AP_BUTTON_ON); blink(3, 80); }   // коротке натискання — «svitlo-setup» на 10 хв
    btnDownAt = 0;
  }
}

// ---------------- SETUP / LOOP ----------------
void setup() {
  pinMode(LED_PIN, OUTPUT);
  digitalWrite(LED_PIN, LED_ON);                  // світиться при старті — прошивка запустилась
  pinMode(BTN_PIN, INPUT_PULLUP);
  Serial.begin(115200);
  delay(300);
  loadConfig();
  prefs.begin("svitlo", false);
  String why = prefs.getString("why", ""); prefs.remove("why");
  prefs.end();
  bootReason = resetReasonText();
  if (why.length() && esp_reset_reason() == ESP_RST_SW) bootReason = "перезапуск: " + why;
  Serial.printf("\n=== Svitlo v%s · черга %s · %s · причина: %s ===\n", FW_VER, queue.c_str(), board.c_str(), bootReason.c_str());

  WiFi.persistent(false);
  WiFi.setHostname(hostName().c_str());
  WiFi.onEvent(onWifiEvent);
  WiFi.mode(WIFI_STA);
  WiFi.setTxPower(WIFI_POWER_8_5dBm);             // для SuperMini: стабільніший Wi-Fi
  if (!ssid.length() || !queue.length()) startAp(0);   // ще не налаштована — одразу «svitlo-setup»
  connectWifi();
  lostAt = millis();

  configTzTime("EET-2EEST,M3.5.0/3,M10.5.0/4", "pool.ntp.org", "time.google.com");
  if (MDNS.begin(hostName().c_str())) MDNS.addService("http", "tcp", 80);

  server.on("/", handleRoot);
  server.on("/api", handleApi);
  server.on("/scan", handleScan);
  server.on("/save", HTTP_POST, handleSave);
  server.on("/ping", handlePing);
  server.on("/reboot", HTTP_POST, handleReboot);
  server.on("/reset", HTTP_POST, handleReset);
  server.on("/ota", handleOtaPage);
  server.on("/update", HTTP_POST, handleUpdateDone, handleUpdateUpload);
  server.onNotFound(handleNotFound);
  server.begin();

  esp_task_wdt_config_t wdt = { .timeout_ms = 60000, .idle_core_mask = 0, .trigger_panic = true };   // зависла більше ніж на хвилину — перезапуск
  if (esp_task_wdt_init(&wdt) != ESP_OK) esp_task_wdt_reconfigure(&wdt);
  esp_task_wdt_add(NULL);
  digitalWrite(LED_PIN, LED_OFF);
}

void loop() {
  esp_task_wdt_reset();
  server.handleClient();
  if (apOn) dns.processNextRequest();
  unsigned long now = millis();
  bool conn = WiFi.status() == WL_CONNECTED;
  handleLed(now, conn);
  handleButton(now);
  logBootOnce();

  if (restartAt && (long)(now - restartAt) >= 0) ESP.restart();
  if (otaBusy) return;                                       // під час оновлення — більше нічого

  if (conn) {
    if (!wasConn) {                                          // щойно підключились
      wasConn = true; upAt = now; lastReason = 0;
      Serial.printf("Wi-Fi OK: %s, IP %s\n", ssid.c_str(), WiFi.localIP().toString().c_str());
      nextPing = now + 1500; nextExtra = now + 1500 + EXTRA_SHIFT;
      if (testPhase == 1) testPhase = 2;
      if (apOn && !apOffAt && testPhase == 0) apOffAt = now + 120000UL;   // увімкнулась сама (немає Wi-Fi) — вимкнеться за 2 хв
    }
    // рівно раз на хвилину (без «сповзання»): основна адреса в :00, додаткова в :30
    if ((long)(now - nextPing) >= 0) { doPing(); nextPing += PING_EVERY; if ((long)(millis() - nextPing) > 0) nextPing = millis() + PING_EVERY; }
    if (extra.startsWith("http") && (long)(now - nextExtra) >= 0) { doExtra(); nextExtra += PING_EVERY; if ((long)(millis() - nextExtra) > 0) nextExtra = millis() + PING_EVERY; }
    if (failStreak >= MAX_FAILS) restartNow("10 невдалих пінгів поспіль");
  } else {
    if (wasConn) { wasConn = false; lostAt = now; Serial.println("Wi-Fi втрачено"); }
    if (ssid.length() && !apOn && now - lostAt > AP_AFTER) startAp(0);                 // довго немає Wi-Fi — даємо змогу перевірити налаштування
    if (ssid.length() && now - lostAt > REBOOT_NOWIFI && WiFi.softAPgetStationNum() == 0) restartNow("30 хв без Wi-Fi");
    if (apOn && ssid.length() && testPhase != 1) {
      int st = WiFi.softAPgetStationNum();
      if (st > 0) { noClientsSince = 0; if (!staPaused) { staPaused = true; WiFi.setAutoReconnect(false); WiFi.disconnect(); } }
      else { if (!noClientsSince) noClientsSince = now; if (staPaused && now - noClientsSince > 15000UL) connectWifi(); }
    }
  }
  if (apOn && apOffAt && (long)(now - apOffAt) >= 0) {
    if (conn && ssid.length() && queue.length()) stopAp(); else apOffAt = 0;          // без Wi-Fi точку не вимикаємо
  }
  if (now > 86400000UL && !apOn) restartNow("щодобовий перезапуск");                  // раз на добу — профілактика (як у старій прошивці)
  delay(2);
}
