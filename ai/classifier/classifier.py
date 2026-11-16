"""classifier.py - UNIT02-39 (SUB-106.2)
Phan loai TUNG TRANG cua file PDF/JPG/PNG thanh:
NATIONAL_ID | LABOR_CONTRACT | BANK_STATEMENT | LOAN_APPLICATION | UNKNOWN

Cach dung:
  python classifier.py <file_hoac_thu_muc> [--out result.json]
"""
import argparse
import hashlib
import json
import os
import re
import sys
import unicodedata
from pathlib import Path

CONFIG_PATH = Path(__file__).parent / "config" / "keywords.json"
SUPPORTED = {".pdf", ".jpg", ".jpeg", ".png"}
MIN_TEXT_CHARS = 30          # it hon so ky tu nay -> coi la trang scan, dung OCR
STRONG_W, WEAK_W = 3, 1
UNKNOWN = "UNKNOWN"


def normalize(text: str) -> str:
    """Thuong hoa, bo dau tieng Viet, gop khoang trang."""
    text = text.replace("đ", "d").replace("Đ", "D")
    text = unicodedata.normalize("NFD", text)
    text = "".join(c for c in text if unicodedata.category(c) != "Mn")
    text = re.sub(r"[^a-z0-9\s]", " ", text.lower())
    return re.sub(r"\s+", " ", text).strip()


def load_config(path=CONFIG_PATH) -> dict:
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def classify_text(text: str, cfg: dict) -> dict:
    """Tra ve nhan, do tin cay, tu khoa khop. Khong du bang chung -> UNKNOWN."""
    norm = normalize(text)
    scores, matched = {}, {}
    for cls, kw in cfg["classes"].items():
        hits, score = [], 0
        for words, weight in ((kw["strong"], STRONG_W), (kw["weak"], WEAK_W)):
            for w in words:
                if re.search(rf"\b{re.escape(w)}\b", norm):
                    hits.append(w)
                    score += weight
        scores[cls], matched[cls] = score, hits
    ranked = sorted(scores.items(), key=lambda x: x[1], reverse=True)
    (best, top), (_, second) = ranked[0], ranked[1]
    if top < cfg["min_score"] or (top - second) < cfg["min_margin"]:
        return {"documentClass": UNKNOWN, "confidence": 0.0,
                "matchedKeywords": matched[best] if top else [],
                "scores": scores}
    confidence = round((top / (top + second + 1)) * min(1.0, top / 8), 3)
    return {"documentClass": best, "confidence": confidence,
            "matchedKeywords": matched[best], "scores": scores}


# ---------- doc noi dung tung trang ----------
def _ocr_image(pil_image) -> str:
    import pytesseract
    cmd = os.environ.get("TESSERACT_CMD",
                         r"C:\Program Files\Tesseract-OCR\tesseract.exe")
    if os.path.exists(cmd):
        pytesseract.pytesseract.tesseract_cmd = cmd
    try:
        return pytesseract.image_to_string(pil_image, lang="vie+eng")
    except pytesseract.TesseractError:  # chua cai goi ngon ngu 'vie'
        print("CANH BAO: chua co goi 'vie' cua Tesseract, dung 'eng'", file=sys.stderr)
        return pytesseract.image_to_string(pil_image, lang="eng")


def read_pages(path: Path):
    """Sinh (so_trang, van_ban, da_dung_ocr). So trang bat dau tu 1."""
    from PIL import Image
    ext = path.suffix.lower()
    if ext in {".jpg", ".jpeg", ".png"}:
        yield 1, _ocr_image(Image.open(path)), True
        return
    import fitz  # PyMuPDF
    with fitz.open(path) as doc:
        for i, page in enumerate(doc, start=1):
            text = page.get_text()
            if len(text.strip()) >= MIN_TEXT_CHARS:
                yield i, text, False
            else:
                pix = page.get_pixmap(dpi=200)
                img = Image.frombytes("RGB", (pix.width, pix.height), pix.samples)
                yield i, _ocr_image(img), True


def sha256_of(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def classify_file(path, cfg=None) -> dict:
    path, cfg = Path(path), cfg or load_config()
    result = {"file": path.name, "sha256": sha256_of(path), "pages": [],
              "error": None}
    try:
        for page_no, text, used_ocr in read_pages(path):
            r = classify_text(text, cfg)
            result["pages"].append({
                "pageAnchor": page_no,
                "documentClass": r["documentClass"],
                "confidence": r["confidence"],
                "matchedKeywords": r["matchedKeywords"],
                "ocrUsed": used_ocr,
                "needsReview": r["documentClass"] == UNKNOWN,
            })
    except Exception as e:  # file hong/ma hoa: KHONG duoc bien mat khoi ket qua
        result["error"] = f"{type(e).__name__}: {e}"
    return result


def iter_files(target: Path):
    if target.is_file():
        yield target
    else:
        for p in sorted(target.rglob("*")):
            if p.is_file() and p.suffix.lower() in SUPPORTED:
                yield p


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("target")
    ap.add_argument("--out", default=None)
    args = ap.parse_args()
    cfg = load_config()
    results = [classify_file(p, cfg) for p in iter_files(Path(args.target))]
    out = json.dumps(results, ensure_ascii=False, indent=2)
    if args.out:
        Path(args.out).write_text(out, encoding="utf-8")
        print(f"Da ghi {len(results)} file -> {args.out}")
    else:
        print(out)


if __name__ == "__main__":
    sys.exit(main())
