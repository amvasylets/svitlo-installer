# Знаходить на екрані елемент і друкує координати центру.
# Порядок: поле вводу за підписом (content-desc) → кнопка (clickable) з таким текстом → (лише з ключем any) будь-який текст.
import sys, re, xml.etree.ElementTree as ET
t = ET.parse(sys.argv[1]); want = sys.argv[2]; anytext = len(sys.argv) > 3 and sys.argv[3] == "any"
nodes = list(t.iter("node"))
def hit(n):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds"))); print((x1 + x2) // 2, (y1 + y2) // 2); sys.exit(0)
for n in nodes:
    if want in (n.get("content-desc") or ""): hit(n)
for n in nodes:
    if n.get("clickable") == "true" and want in (n.get("text") or ""): hit(n)
if anytext:
    for n in nodes:
        if want in (n.get("text") or ""): hit(n)
sys.exit(1)
