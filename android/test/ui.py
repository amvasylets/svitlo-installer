# Знаходить на екрані кнопку за текстом (uiautomator dump) і друкує координати центру: python3 ui.py dump.xml "текст"
import sys, re, xml.etree.ElementTree as ET
t = ET.parse(sys.argv[1]); want = sys.argv[2]
for n in t.iter("node"):
    if want in (n.get("text") or "") or want in (n.get("content-desc") or ""):
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds"))); print((x1 + x2) // 2, (y1 + y2) // 2); sys.exit(0)
sys.exit(1)
