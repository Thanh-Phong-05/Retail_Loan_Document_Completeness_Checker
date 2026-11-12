"""evaluate.py - do Macro F1 cua classifier.
labels.csv co cot: file,page,label   (vi du: bo1_cccd.pdf,1,NATIONAL_ID)
Chay: python evaluate.py <thu_muc_ho_so> labels.csv
UNKNOWN tren trang co nhan that duoc tinh la sai (giong AT 03 cua RFP).
"""
import csv
import sys
from collections import Counter
from pathlib import Path

from classifier import classify_file, load_config

CLASSES = ["NATIONAL_ID", "LABOR_CONTRACT", "BANK_STATEMENT", "LOAN_APPLICATION"]


def main(folder, labels_csv):
    cfg = load_config()
    truth = {}
    with open(labels_csv, encoding="utf-8") as f:
        for row in csv.DictReader(f):
            truth[(row["file"], int(row["page"]))] = row["label"]
    pred, cache = {}, {}
    for (fn, pg) in truth:
        if fn not in cache:
            cache[fn] = classify_file(Path(folder) / fn, cfg)
        pages = {p["pageAnchor"]: p["documentClass"] for p in cache[fn]["pages"]}
        pred[(fn, pg)] = pages.get(pg, "MISSING")
    conf = Counter((truth[k], pred[k]) for k in truth)
    f1s = []
    print(f"{'class':18} {'P':>6} {'R':>6} {'F1':>6}  n")
    for c in CLASSES:
        tp = conf[(c, c)]
        fp = sum(v for (t, p), v in conf.items() if p == c and t != c)
        fn_ = sum(v for (t, p), v in conf.items() if t == c and p != c)
        n = tp + fn_
        if n == 0:
            continue
        P = tp / (tp + fp) if tp + fp else 0.0
        R = tp / n
        F = 2 * P * R / (P + R) if P + R else 0.0
        f1s.append(F)
        print(f"{c:18} {P:6.2f} {R:6.2f} {F:6.2f}  {n}")
    print(f"\nMacro F1 = {sum(f1s)/len(f1s):.3f}  (muc tieu RFP AT 03: >= 0.90)")
    print("\nTrang du doan sai:")
    for k in truth:
        if truth[k] != pred[k]:
            print(f"  {k[0]} trang {k[1]}: that={truth[k]} | doan={pred[k]}")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
