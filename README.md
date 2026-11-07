# Svitlo — веб-інсталятор прошивки для ESP32-C3 SuperMini

Сторінка, з якої плату можна прошити прямо з браузера (Chrome або Edge на комп'ютері), без Arduino IDE.
Працює на ESP Web Tools — так само, як інсталятор Світлобота.

## Як опублікувати на GitHub (один раз, ~5 хвилин)
1. Відкрийте github.com і увійдіть у свій акаунт.
2. Угорі праворуч «+» → **New repository** → Repository name: `svitlo-installer` → **Public** → **Create repository**.
3. На сторінці нового репозиторію натисніть посилання **uploading an existing file**.
4. Перетягніть у вікно все з цієї папки: `index.html`, `manifest.json`, `README.md` і папку `firmware` (саме папку, разом з 4 файлами всередині) → внизу **Commit changes**.
5. **Settings** → ліворуч **Pages** → Source: **Deploy from a branch** → Branch: **main**, папка **/ (root)** → **Save**.
6. Через 1–2 хвилини вгорі цієї сторінки з'явиться адреса: `https://ВАШ-ЛОГІН.github.io/svitlo-installer/` — це і є інсталятор.

## Як оновити прошивку в інсталяторі
1. У репозиторії відкрийте папку `firmware` → **Add file** → **Upload files** → перетягніть новий `svitlo.bin` (з тією самою назвою) → **Commit changes**.
2. Відкрийте `manifest.json` → олівець (Edit) → змініть `"version"` (наприклад `"2.1"`) → **Commit changes**.

## Що всередині
| Файл | Куди записується на плату |
|---|---|
| firmware/bootloader.bin | 0x0 — завантажувач |
| firmware/partitions.bin | 0x8000 — таблиця розділів |
| firmware/boot_app0.bin | 0xE000 — вибір робочої програми |
| firmware/svitlo.bin | 0x10000 — сама прошивка (цей самий файл — для оновлення по повітрю) |

Налаштування плати (розділ NVS, 0x9000) інсталятор не чіпає, якщо не ставити галочку «Erase device».

## Вихідний код
`source/Svitlo_ESP32C3/Svitlo_ESP32C3.ino` — прошивка 2.1 (той самий код, з якого зібрано `firmware/svitlo.bin`).
Збірка: Arduino IDE, пакет «esp32 by Espressif» 3.x, плата **ESP32C3 Dev Module**, USB CDC On Boot: **Enabled**, Flash Mode: **DIO**, Partition Scheme: **Default 4MB with spiffs**.
