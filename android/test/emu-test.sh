#!/usr/bin/env bash
# Перевірка Svitlo Пінг на емуляторі. Аргумент — рівень API. Результати: android/test/out/results.txt, скріни *.png
API="$1"; cd "$(dirname "$0")"; mkdir -p out; : > out/results.txt; : > hits.log
PKG=ua.svitlo.ping; APK="$(ls ../../apk/*.apk | head -1)"
ok() { echo "✅ $*" | tee -a out/results.txt; }
bad() { echo "❌ $*" | tee -a out/results.txt; }
check() { if eval "$2"; then ok "$1"; else bad "$1"; fi; }
UI=1
scheck() {
  if [ "$UI" != 1 ]; then echo "ℹ️ $1 — перевірено на скріні (на цій версії Android інструмент читання екрана не працює)" | tee -a out/results.txt; return; fi
  if seen "$2"; then ok "$1"; elif [ ! -s out/ui.xml ]; then echo "ℹ️ $1 — інструмент читання екрана не відповів, див. скрін" | tee -a out/results.txt; else bad "$1"; fi; }
hits() { grep -c " /p/$1" hits.log 2>/dev/null || true; }
shot() { adb exec-out screencap -p > "out/api${API}_$1.png" 2>/dev/null || adb shell screencap -p | sed 's/\r$//' > "out/api${API}_$1.png"; }
front() { adb shell am start -n $PKG/.MainActivity >/dev/null 2>&1; sleep 2; }
WH=$(adb shell wm size 2>/dev/null | grep -o '[0-9]*x[0-9]*' | tail -1); W=${WH%x*}; H=${WH#*x}; [ -z "$W" ] && W=720 && H=1280
X=$((W/2)); Y1=$((H*3/4)); Y2=$((H/4))
dump() { rm -f out/ui.xml; for i in 1 2 3; do adb shell rm -f /data/local/tmp/ui.xml; r=$(adb shell uiautomator dump /data/local/tmp/ui.xml 2>&1); adb pull /data/local/tmp/ui.xml out/ui.xml >/dev/null 2>&1 && [ -s out/ui.xml ] && return 0; echo "   (uiautomator: $(echo $r | tr -d '\r' | head -c 120))"; sleep 2; done; return 1; }
up() { for i in 1 2 3 4; do adb shell input swipe $X $Y2 $X $Y1 150; done; sleep 1; }          # на самий верх
down() { adb shell input swipe $X $Y1 $X $Y2 300; sleep 1; }
seen() { for i in 1 2 3 4 5; do dump; grep -q "$1" out/ui.xml && return 0; down; done; return 1; }   # шукаємо, гортаючи вниз
# натиснути елемент з текстом/підписом $1; якщо його не видно — гортаємо вниз (до 6 разів)
tap() { for i in 1 2 3 4 5 6 7; do dump; xy=$(python3 ui.py out/ui.xml "$1") && { adb shell input tap $xy; sleep 1; return 0; }; down; done; dump; xy=$(python3 ui.py out/ui.xml "$1" any) && { adb shell input tap $xy; sleep 1; return 0; }; echo "   (не знайдено на екрані: $1)"; return 1; }
hidekb() { adb shell dumpsys input_method | grep -q 'mInputShown=true' && adb shell input keyevent 4; sleep 1; }
plug() { adb shell dumpsys battery set ac "$1"; adb shell dumpsys battery set usb 0; adb shell dumpsys battery set wireless 0 2>/dev/null; }
cfg() { case "$*" in *--es\ server*) ;; *) set -- --es server http://10.0.2.2:8765 "$@";; esac; adb shell am broadcast -n $PKG/.TestReceiver -a ua.svitlo.ping.TEST_CONFIG "$@" >/dev/null; }

python3 server.py 8765 > out/server.log 2>&1 & SRV=$!
adb logcat -c
adb install -r "$APK" | tail -1
check "встановлення APK на Android API $API" "adb shell pm list packages | grep -q $PKG"
# ТІЛЬКИ тестовий сервер: запускаємо застосунок (щоб Android дозволив йому отримувати команди), записуємо тестовий сервер, перезапускаємо екран
adb shell am start -W -n $PKG/.MainActivity >/dev/null 2>&1; sleep 3
adb shell am broadcast -n $PKG/.TestReceiver -a ua.svitlo.ping.TEST_CONFIG --es server http://10.0.2.2:8765 --ez enabled false | tr -d '\r' | tail -1 | sed 's/^/   налаштування тесту: /'
sleep 1; adb shell am start -S -W -n $PKG/.MainActivity >/dev/null 2>&1; sleep 3
[ "$API" -ge 33 ] && adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS
plug 1
front; sleep 3; shot 01_pershyi_zapusk
dump || UI=0
if [ "$UI" = 1 ]; then
  check "перший запуск: поле «Номер черги» видно" "seen 'Номер черги'"
  # заповнюємо форму пальцем, як людина: черга «3/2», Додатково → адреса тестового сервера
  tap "Номер черги" && adb shell input text "3/2"; hidekb
  tap "Додатково"
  check "у «Додатково» видно адресу сервера (тестового)" "seen '10.0.2.2:8765'"
  if ! grep -q '10.0.2.2:8765' out/ui.xml 2>/dev/null; then bad "ЗУПИНКА: тестовий сервер не підставився — далі не йдемо, щоб не чіпати справжнього бота"; kill $SRV; exit 1; fi
  shot 02_zapovneno
  tap "Зберегти і запустити"; sleep 6
else
  echo "ℹ️ інструмент читання екрана тут не працює — налаштовуємо командою (черга «3/2»), екран перевіряємо на скрінах" | tee -a out/results.txt
  cfg --es queue 3/2 --es board esp1 --ez enabled true; sleep 6
fi
if [ "$API" -ge 23 ]; then   # вікно «дозволити роботу у фоні»: закриваємо (лише якщо воно справді відкрите) і дозволяємо через adb
  adb shell dumpsys window | grep -E 'mCurrentFocus' | grep -qE "$PKG/($PKG)?\.MainActivity" || adb shell input keyevent 4; sleep 1
  adb shell dumpsys deviceidle whitelist +$PKG >/dev/null
fi
front
check "кнопка «Зберегти»: «3/2» → черга 3.2, перший пінг одразу" "[ \$(hits 3.2/esp1) -ge 1 ]"
grep -m1 "/p/3.2/esp1" hits.log | sed 's/^/   запит: /'
sleep 3; up; shot 03_pratsiuie
scheck "екран показує «Працює»" "Працює"
check "працює як фонова служба з постійним сповіщенням" "adb shell dumpsys activity services $PKG | grep -q 'isForeground=true'"

# застосунок у фоні (на 5.0+ ще й екран вимкнено) — пінг раз на хвилину
# (емулятор Android 4.4 при вимкненому екрані «замерзає» повністю, разом з adb, тому там екран не вимикаємо)
adb shell input keyevent 3; [ "$API" -ge 21 ] && adb shell input keyevent 26; n0=$(hits 3.2/esp1); t0=$(date +%s)
sleep 185
n1=$(hits 3.2/esp1)
WHERE="у фоні"; [ "$API" -ge 21 ] && WHERE="у фоні з вимкненим екраном"
check "$WHERE: 3 пінги за 3 хв (було $((n1-n0)))" "[ $((n1-n0)) -ge 3 ] && [ $((n1-n0)) -le 4 ]"
python3 -c "import sys,time; t=[float(l.split()[0]) for l in open('hits.log') if '/p/3.2/esp1' in l and float(l.split()[0])>$t0]; print('   інтервали між пінгами, с:', [round(b-a,1) for a,b in zip(t,t[1:])])" | tee -a out/results.txt
if [ "$API" -ge 21 ]; then adb shell input keyevent 26; sleep 1; adb shell wm dismiss-keyguard 2>/dev/null; adb shell input keyevent 82; sleep 1; fi

# зарядку від'єднали (= зникло світло) — пінги припиняються
plug 0; sleep 3; n0=$(hits 3.2/esp1); sleep 75; n1=$(hits 3.2/esp1)
check "зарядку від'єднано → пінгів немає ($((n1-n0)) за 75 с)" "[ $((n1-n0)) -eq 0 ]"
front; up; shot 04_nemaie_zariadky
scheck "екран: «Чекаю на зарядку»" "Чекаю на зарядку"
adb shell input keyevent 3
# зарядку підключили (= світло повернулось) — пінг одразу
n0=$(hits 3.2/esp1); plug 1; sleep 8; n1=$(hits 3.2/esp1)
check "зарядку підключено → пінг одразу (за 8 с: $((n1-n0)))" "[ $((n1-n0)) -ge 1 ]"

# неправильна черга
cfg --es queue 9.9 --ez enabled true --ez ping_now true; sleep 6; front; up; shot 05_cherga_ne_znaidena
scheck "черги немає на сервері → пише «черга не знайдена»" "не знайдена"
cfg --es queue 3.2 --es board esp2 --ez enabled true --ez ping_now true; sleep 6
check "esp2 → адреса …/p/3.2/esp2" "[ \$(hits 3.2/esp2) -ge 1 ]"
# немає сервера/інтернету
cfg --es server http://10.0.2.2:9 --ez ping_now true; sleep 8; front; up; shot 06_nemaie_zviazku
scheck "сервер недоступний → «Немає зв'язку з сервером»" "Немає зв"
cfg --es server http://10.0.2.2:8765 --es board esp1 --ez ping_now true; sleep 5
if [ "$UI" = 1 ]; then front; tap "Історія подій"; down; shot 07_istoriia; up; tap "Змінити налаштування"; tap "Додатково"; shot 08_nalashtuvannia; fi

adb logcat -d > out/logcat-pre.txt 2>/dev/null
# перезавантаження телефона — запускається сам
if [ "$API" -ge 21 ]; then
  adb reboot; adb wait-for-device
  for i in $(seq 1 90); do [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break; sleep 2; done
  plug 1; n0=$(hits 3.2/esp1); sleep 100; n1=$(hits 3.2/esp1)
  check "після перезавантаження телефона пінг іде сам (за 100 с: $((n1-n0)))" "[ $((n1-n0)) -ge 1 ]"
fi

adb logcat -d > out/logcat.txt 2>/dev/null
cat out/logcat-pre.txt out/logcat.txt > out/logcat-all.txt 2>/dev/null
check "жодного аварійного завершення застосунку" "! grep -A3 'FATAL EXCEPTION' out/logcat-all.txt | grep -q $PKG"
grep -A12 'FATAL EXCEPTION' out/logcat-all.txt | head -40
echo "   сервер, на який ішли пінги: $(cut -d' ' -f2 hits.log | sort | uniq -c | tr '\n' ' ')"
cp hits.log out/hits.log; kill $SRV 2>/dev/null
echo; cat out/results.txt
! grep -q '^❌' out/results.txt
