# Знаходить на екрані елемент (спершу за підписом content-desc — це поля вводу, потім за текстом) і друкує координати центру.
import sys, re, xml.etree.ElementTree as ET
t = ET.parse(sys.argv[1]); want = sys.argv[2]
nodes = list(t.iter("node"))
for key in ("content-desc", "text"):
    for n in nodes:
        if want in (n.get(key) or ""):
            x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds"))); print((x1 + x2) // 2, (y1 + y2) // 2); sys.exit(0)
sys.exit(1)
