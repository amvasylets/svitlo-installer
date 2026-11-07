#!/usr/bin/env bash
# Перевірка Svitlo Пінг на емуляторі. Аргумент — рівень API. Результати: android/test/out/results.txt, скріни *.png
API="$1"; cd "$(dirname "$0")"; mkdir -p out; : > out/results.txt; : > hits.log
PKG=ua.svitlo.ping; APK="$(ls ../../apk/*.apk | head -1)"
ok() { echo "✅ $*" | tee -a out/results.txt; }
bad() { echo "❌ $*" | tee -a out/results.txt; }
check() { if eval "$2"; then ok "$1"; else bad "$1"; fi; }
hits() { grep -c " /p/$1" hits.log 2>/dev/null || true; }
shot() { adb exec-out screencap -p > "out/api${API}_$1.png" 2>/dev/null || adb shell screencap -p | sed 's/\r$//' > "out/api${API}_$1.png"; }
front() { adb shell am start -n $PKG/.MainActivity >/dev/null 2>&1; sleep 2; }
WH=$(adb shell wm size 2>/dev/null | grep -o '[0-9]*x[0-9]*' | tail -1); W=${WH%x*}; H=${WH#*x}; [ -z "$W" ] && W=720 && H=1280
X=$((W/2)); Y1=$((H*3/4)); Y2=$((H/4))
dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml out/ui.xml >/dev/null 2>&1; }
up() { for i in 1 2 3 4; do adb shell input swipe $X $Y2 $X $Y1 150; done; sleep 1; }          # на самий верх
down() { adb shell input swipe $X $Y1 $X $Y2 300; sleep 1; }
seen() { dump; grep -q "$1" out/ui.xml; }
# натиснути елемент з текстом/підписом $1; якщо його не видно — гортаємо вниз (до 6 разів)
tap() { for i in 1 2 3 4 5 6 7; do dump; xy=$(python3 ui.py out/ui.xml "$1") && { adb shell input tap $xy; sleep 1; return 0; }; down; done; echo "   (не знайдено на екрані: $1)"; return 1; }
hidekb() { adb shell dumpsys input_method | grep -q 'mInputShown=true' && adb shell input keyevent 4; sleep 1; }
plug() { adb shell dumpsys battery set ac "$1"; adb shell dumpsys battery set usb 0; adb shell dumpsys battery set wireless 0 2>/dev/null; }
cfg() { adb shell am broadcast -n $PKG/.TestReceiver -a ua.svitlo.ping.TEST_CONFIG "$@" >/dev/null; }

python3 server.py 8765 > out/server.log 2>&1 & SRV=$!
adb logcat -c
adb install -r "$APK" | tail -1
check "встановлення APK на Android API $API" "adb shell pm list packages | grep -q $PKG"
[ "$API" -ge 33 ] && adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS
plug 1
front; sleep 3; shot 01_pershyi_zapusk
check "перший запуск: поле «Номер черги» видно" "seen 'Номер черги'"

# заповнюємо форму пальцем, як людина: черга «3/2», Додатково → адреса тестового сервера
tap "Номер черги" && adb shell input text "3/2"; hidekb
tap "Додатково"
tap "Адреса сервера" && adb shell input text "http://10.0.2.2:8765"; hidekb
shot 02_zapovneno
tap "Зберегти і запустити"; sleep 6
[ "$API" -ge 23 ] && { adb shell input keyevent 4; sleep 1; adb shell dumpsys deviceidle whitelist +$PKG >/dev/null; }   # вікно «дозволити роботу у фоні» — у тесті дозволяємо через adb
front
check "кнопка «Зберегти»: «3/2» → черга 3.2, перший пінг одразу" "[ \$(hits 3.2/esp1) -ge 1 ]"
grep -m1 "/p/3.2/esp1" hits.log | sed 's/^/   запит: /'
sleep 3; up; shot 03_pratsiuie
check "екран показує «Працює»" "seen 'Працює'"
check "працює як фонова служба з постійним сповіщенням" "adb shell dumpsys activity services $PKG | grep -q 'isForeground=true'"

# екран вимкнено, застосунок у фоні — пінг раз на хвилину
adb shell input keyevent 3; adb shell input keyevent 26; n0=$(hits 3.2/esp1); t0=$(date +%s)
sleep 185
n1=$(hits 3.2/esp1)
check "у фоні з вимкненим екраном: 3 пінги за 3 хв (було $((n1-n0)))" "[ $((n1-n0)) -ge 3 ] && [ $((n1-n0)) -le 4 ]"
python3 -c "import sys,time; t=[float(l.split()[0]) for l in open('hits.log') if '/p/3.2/esp1' in l and float(l.split()[0])>$t0]; print('   інтервали між пінгами, с:', [round(b-a,1) for a,b in zip(t,t[1:])])" | tee -a out/results.txt
adb shell input keyevent 26; sleep 1; adb shell wm dismiss-keyguard 2>/dev/null; adb shell input keyevent 82; sleep 1

# зарядку від'єднали (= зникло світло) — пінги припиняються
plug 0; sleep 3; n0=$(hits 3.2/esp1); sleep 75; n1=$(hits 3.2/esp1)
check "зарядку від'єднано → пінгів немає ($((n1-n0)) за 75 с)" "[ $((n1-n0)) -eq 0 ]"
front; up; shot 04_nemaie_zariadky
check "екран: «Чекаю на зарядку»" "seen 'Чекаю на зарядку'"
adb shell input keyevent 3
# зарядку підключили (= світло повернулось) — пінг одразу
n0=$(hits 3.2/esp1); plug 1; sleep 8; n1=$(hits 3.2/esp1)
check "зарядку підключено → пінг одразу (за 8 с: $((n1-n0)))" "[ $((n1-n0)) -ge 1 ]"

# неправильна черга
cfg --es queue 9.9 --ez enabled true --ez ping_now true; sleep 6; front; up; shot 05_cherga_ne_znaidena
check "черги немає на сервері → пише «черга не знайдена»" "seen 'не знайдена'"
cfg --es queue 3.2 --es board esp2 --ez enabled true --ez ping_now true; sleep 6
check "esp2 → адреса …/p/3.2/esp2" "[ \$(hits 3.2/esp2) -ge 1 ]"
# немає сервера/інтернету
cfg --es server http://10.0.2.2:9 --ez ping_now true; sleep 8; front; up; shot 06_nemaie_zviazku
check "сервер недоступний → «Немає зв'язку з сервером»" "seen 'Немає зв'"
cfg --es server http://10.0.2.2:8765 --es board esp1 --ez ping_now true; sleep 5
front; tap "Історія подій"; down; shot 07_istoriia
up; tap "Змінити налаштування"; tap "Додатково"; shot 08_nalashtuvannia

# перезавантаження телефона — запускається сам
if [ "$API" -ge 21 ]; then
  adb reboot; adb wait-for-device
  for i in $(seq 1 90); do [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break; sleep 2; done
  plug 1; n0=$(hits 3.2/esp1); sleep 100; n1=$(hits 3.2/esp1)
  check "після перезавантаження телефона пінг іде сам (за 100 с: $((n1-n0)))" "[ $((n1-n0)) -ge 1 ]"
fi

adb logcat -d > out/logcat.txt 2>/dev/null
check "жодного аварійного завершення застосунку" "! grep -A3 'FATAL EXCEPTION' out/logcat.txt | grep -q $PKG"
grep -A12 'FATAL EXCEPTION' out/logcat.txt | head -40
cp hits.log out/hits.log; kill $SRV 2>/dev/null
echo; cat out/results.txt
! grep -q '^❌' out/results.txt
