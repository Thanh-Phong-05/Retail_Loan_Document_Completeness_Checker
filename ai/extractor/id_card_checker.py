"""Kiểm tra độ đầy đủ, độ nét và trích xuất thông tin CCCD Việt Nam (Tesseract + VietOCR + RegEx).

Cách chạy:
    python ai/extractor/id_card_checker.py ai/classifier/sample/tin1.jpg ai/classifier/sample/tin2.jpg
"""

from __future__ import annotations

import json
import os
import re
import sys
import unicodedata
from dataclasses import dataclass
from difflib import SequenceMatcher
from pathlib import Path
from typing import Iterable, Sequence

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
if hasattr(sys.stderr, "reconfigure"):
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")


VALID = "VALID"
MISSING_FRONT_SIDE = "MISSING_FRONT_SIDE"
MISSING_BACK_SIDE = "MISSING_BACK_SIDE"
MISSING_BOTH_SIDES = "MISSING_BOTH_SIDES"
BLURRY = "BLURRY"
CARD_MISMATCH = "CARD_MISMATCH"

BLUR_THRESHOLD = 65.0
CARD_W = 1000
CARD_H = 630

PROVINCE_CODES = {
    "001", "002", "004", "006", "008", "010", "011", "012", "014",
    "015", "017", "019", "020", "022", "024", "025", "026", "027",
    "030", "031", "033", "034", "035", "036", "037", "038", "040",
    "042", "044", "045", "046", "048", "049", "051", "052", "054",
    "056", "058", "060", "062", "064", "066", "067", "068", "070",
    "072", "074", "075", "077", "079", "080", "082", "083", "084",
    "086", "087", "089", "091", "092", "093", "094", "095", "096",
}


# ==============================================================================
# 1. CHUẨN HÓA VĂN BẢN, REGEX NGÀY THÁNG & KIỂM TRA SỐ CCCD
# ==============================================================================

def configure_tesseract() -> None:
    import pytesseract

    cmd = os.environ.get("TESSERACT_CMD", r"C:\Program Files\Tesseract-OCR\tesseract.exe")
    if Path(cmd).exists():
        pytesseract.pytesseract.tesseract_cmd = cmd


def normalize_ascii(text: str) -> str:
    text = text.replace("đ", "d").replace("Đ", "D")
    text = unicodedata.normalize("NFD", text)
    text = "".join(ch for ch in text if unicodedata.category(ch) != "Mn")
    text = re.sub(r"[^a-z0-9\s<]", " ", text.lower())
    return re.sub(r"\s+", " ", text).strip()


def fuzzy_has(text: str, keyword: str, threshold: float = 0.72) -> bool:
    if keyword in text:
        return True
    klen = len(keyword)
    if len(text) < klen:
        return False
    return any(
        SequenceMatcher(None, text[i : i + klen], keyword).ratio() >= threshold
        for i in range(len(text) - klen + 1)
    )


def clean_str(val: str) -> str:
    val = re.sub(r"[|¦~_=*•·<>\\]+", " ", val)
    return re.sub(r"\s+", " ", val).strip(" \t:;-.,")


def restore_spaces(viet_text: str, tess_text: str) -> str:
    v, t = clean_str(viet_text), clean_str(tess_text)
    if not v or not t:
        return v or t
    if " " not in v and " " in t:
        parts = t.split(" ")
        if len(v) == sum(len(p) for p in parts):
            out, pos = [], 0
            for p in parts:
                out.append(v[pos : pos + len(p)])
                pos += len(p)
            return " ".join(out)
    return v


def parse_date(text: str) -> str | None:
    for d_s, m_s, y_s in re.findall(r"(?<!\d)(\d{1,2})\s*[\/.\-]\s*(\d{1,2})\s*[\/.\-]\s*(\d{4})(?!\d)", text):
        if d_s.startswith("4") and len(d_s) == 2 and int(d_s) > 31:
            d_s = "1" + d_s[1]
        d, m, y = int(d_s), int(m_s), int(y_s)
        if 1 <= d <= 31 and 1 <= m <= 12 and 1900 <= y <= 2100:
            return f"{d:02d}/{m:02d}/{y}"
    for dm_s, y_s in re.findall(r"(?<!\d)(\d{4})\s*[\/.\-]\s*(\d{4})(?!\d)", text):
        d, m, y = int(dm_s[:2]), int(dm_s[2:]), int(y_s)
        if 1 <= d <= 31 and 1 <= m <= 12 and 1900 <= y <= 2100:
            return f"{d:02d}/{m:02d}/{y}"
    return None


def is_valid_cccd(cccd: str, gender: str | None = None, dob: str | None = None) -> bool:
    if not re.fullmatch(r"\d{12}", cccd) or cccd[:3] not in PROVINCE_CODES:
        return False
    cg = int(cccd[3])
    if (gender == "Nam" and cg % 2 != 0) or (gender == "Nữ" and cg % 2 != 1):
        return False
    if dob and len(dob) == 10:
        yr = int(dob[-4:])
        if cccd[4:6] != f"{yr % 100:02d}":
            return False
        if (1900 <= yr <= 1999 and cg not in (0, 1)) or (2000 <= yr <= 2099 and cg not in (2, 3)):
            return False
    return True


def extract_cccd_number(text: str, gender: str | None = None, dob: str | None = None) -> str | None:
    cands = re.findall(r"(?<!\d)(\d{12})(?!\d)", text)
    for grp in re.findall(r"(?<!\d)(\d{3,4}\s+\d{3,4}\s+\d{4,6})(?!\d)", text):
        digits = re.sub(r"\s+", "", grp)
        if len(digits) == 12:
            cands.append(digits)
    valid = [c for c in cands if c[:3] in PROVINCE_CODES]
    if not valid:
        return None
    strict = [c for c in valid if is_valid_cccd(c, gender=gender, dob=dob)]
    pool = strict or valid
    return max(set(pool), key=pool.count)


# ==============================================================================
# 2. GIẢI MÃ & KIỂM TRA CHECKSUM MRZ 3 DÒNG (ICAO 9303 TD1)
# ==============================================================================

_MRZ_VAL = {str(i): i for i in range(10)}
_MRZ_VAL.update({chr(ord("A") + i): 10 + i for i in range(26)})
_MRZ_VAL["<"] = 0


def mrz_checksum(field: str) -> int:
    w = (7, 3, 1)
    return sum(_MRZ_VAL.get(c, 0) * w[i % 3] for i, c in enumerate(field)) % 10


def _mrz_digits(s: str) -> str:
    mp = {"O": "0", "Q": "0", "D": "0", "U": "0", "I": "1", "L": "1", "Z": "2", "S": "5", "G": "6", "B": "8"}
    return "".join(mp.get(c, c) for c in s)


def _mrz_letters(s: str) -> str:
    mp = {"0": "O", "1": "I", "2": "Z", "5": "S", "6": "G", "8": "B"}
    return "".join(mp.get(c, c) for c in s)


def _parse_mrz_line3(raw: str) -> str | None:
    s = re.sub(r"[^A-Z0-9<]", "", raw.upper())
    if len(s) < 10:
        return None
    s = re.sub(r"<[A-Z0-9<]*\d[A-Z0-9<]*$", "", s)
    s = _mrz_letters(s)
    s = re.sub(r"[KEC](?=<{2,})", "<", s)
    s = re.sub(r"(?<=<{2})[KEC]", "<", s)
    s = re.sub(r"K{3,}$", lambda m: "<" * len(m.group(0)), s)
    s = re.sub(r"(?:KK|<K|K<)", "<<", s)
    core = re.sub(r"<{3,}.*$", "", s.rstrip("<"))
    if len(core) < 4 or not re.search(r"[A-Z]{2,}", core):
        return None
    return core.ljust(30, "<")[:30]


def parse_td1_mrz(band_lines: Sequence[str], fallback_text: str = "") -> dict | None:
    l1 = l2 = l3 = None
    cands = [re.sub(r"[^A-Z0-9<]", "", b.upper()) for b in band_lines if len(b.strip()) >= 14]
    for raw in fallback_text.upper().splitlines():
        c = re.sub(r"[^A-Z0-9<]", "", raw)
        if len(c) >= 24 and c not in cands:
            cands.append(c)

    for idx, line in enumerate(cands):
        if l1 is None and (re.match(r"^(?:ID|1D|I0)VNM", line) or ("VNM" in line[:6] and sum(ch.isdigit() for ch in line) >= 14)):
            fx = ("IDVNM" + line[line.find("VNM") + 3 :]).ljust(30, "<")[:30]
            l1 = f"IDVNM{_mrz_digits(fx[5:14])}{_mrz_digits(fx[14])}{_mrz_digits(fx[15:27])}<<{_mrz_digits(fx[29])}"
        elif l2 is None and ("VNM" in line[12:20] and sum(ch.isdigit() for ch in line[:15]) >= 10):
            vi = line.find("VNM")
            if vi >= 15:
                p = line[vi - 15 : vi]
                sex = p[7] if p[7] in ("M", "F", "<") else ("M" if p[7] in ("N", "H") else "<")
                tail = line[vi + 3 :].ljust(12, "<")[:12]
                l2 = f"{_mrz_digits(p[0:6])}{_mrz_digits(p[6])}{sex}{_mrz_digits(p[8:14])}{_mrz_digits(p[14])}VNM<<<<<<<<<<<{_mrz_digits(tail[-1])}"
        elif l3 is None and idx == 2 and "VNM" not in line and not line.startswith("IDVNM"):
            l3 = _parse_mrz_line3(line)

    if l3 is None:
        for line in cands:
            if "VNM" not in line and not line.startswith("IDVNM") and ("<<" in line or "KK" in line):
                l3 = _parse_mrz_line3(line)
                if l3:
                    break

    if l1 is None and l2 is None:
        return None

    checks: dict[str, bool] = {}
    out: dict = {
        "lines": [x for x in (l1, l2, l3) if x],
        "documentNumber": None,
        "cccdNumber": None,
        "dateOfBirth": None,
        "gender": None,
        "dateOfExpiry": None,
        "nationality": None,
        "fullName": None,
        "checksumValid": False,
        "checks": checks,
    }

    if l1 and len(l1) == 30:
        doc_n, doc_c, cccd_n, cccd_c = l1[5:14], l1[14], l1[15:27], l1[29]
        if doc_n.isdigit() and doc_c.isdigit():
            checks["documentNumber"] = mrz_checksum(doc_n) == int(doc_c)
            out["documentNumber"] = doc_n
        if cccd_n.isdigit() and cccd_c.isdigit():
            checks["cccdNumber"] = mrz_checksum(cccd_n) == int(cccd_c)
            if cccd_n[:3] in PROVINCE_CODES:
                out["cccdNumber"] = cccd_n

    if l2 and len(l2) == 30:
        dob_s, dob_c, sex_s, exp_s, exp_c = l2[0:6], l2[6], l2[7], l2[8:14], l2[14]
        if dob_s.isdigit() and dob_c.isdigit():
            checks["dateOfBirth"] = mrz_checksum(dob_s) == int(dob_c)
            yy, mm, dd = int(dob_s[0:2]), int(dob_s[2:4]), int(dob_s[4:6])
            yr = 2000 + yy if yy <= 35 else 1900 + yy
            if 1 <= mm <= 12 and 1 <= dd <= 31:
                out["dateOfBirth"] = f"{dd:02d}/{mm:02d}/{yr}"
        out["gender"] = "Nam" if sex_s == "M" else ("Nữ" if sex_s == "F" else None)
        if exp_s.isdigit() and exp_c.isdigit():
            checks["dateOfExpiry"] = mrz_checksum(exp_s) == int(exp_c)
            yy, mm, dd = int(exp_s[0:2]), int(exp_s[2:4]), int(exp_s[4:6])
            if 1 <= mm <= 12 and 1 <= dd <= 31:
                out["dateOfExpiry"] = f"{dd:02d}/{mm:02d}/{2000 + yy}"
        if l2[15:18] == "VNM":
            out["nationality"] = "Việt Nam"

    if l3:
        parts = [p for p in l3.rstrip("<").split("<<") if p]
        if parts:
            full = re.sub(
                r"\s+", " ", f"{parts[0].replace('<', ' ')} {' '.join(p.replace('<', ' ') for p in parts[1:])}"
            ).strip()
            if len(full) >= 3:
                out["fullName"] = full

    out["checksumValid"] = bool(checks) and all(checks.values())
    return out


# ==============================================================================
# 3. TRÍCH XUẤT TỪNG TRƯỜNG THEO NHÃN SONG NGỮ + VÙNG TỌA ĐỘ
# ==============================================================================

@dataclass
class OCRLine:
    tess_text: str
    viet_full: str
    viet_value: str
    x1: int
    y1: int
    x2: int
    y2: int

    @property
    def cy(self) -> float:
        return (self.y1 + self.y2) / 2.0

    def best_vietnamese(self, prefer_after_colon: bool = False) -> str:
        t = clean_str(self.tess_text)
        if prefer_after_colon and self.viet_value:
            return clean_str(self.viet_value)
        v = restore_spaces(self.viet_full, t)
        if not v:
            return t
        if not t:
            return v
        digit_ratio = sum(ch.isdigit() for ch in t) / max(1, len(t))
        if digit_ratio > 0.55 or len(v) < len(t) * 0.45 or len(v) > len(t) * 1.75:
            return t
        return v


_LABEL_STOP_PHRASES = (
    "cong hoa xa hoi", "doc lap tu do", "socialist republic", "independence freedom",
    "can cuoc cong dan", "citizen identity card", "ho va ten", "full name",
    "ngay sinh", "date of birth", "gioi tinh", "quoc tich", "nationality",
    "que quan", "place of origin", "noi thuong tru", "noi cu tru", "place of residence",
    "co gia tri den", "date of expiry", "dac diem nhan dang", "personal identification",
    "ngay thang nam", "date month year", "cuc truong cuc canh sat",
)


def _is_label_noise(text: str) -> bool:
    norm = normalize_ascii(text)
    return any(p in norm for p in _LABEL_STOP_PHRASES)


def _strip_label_prefix(text: str, label_patterns: Sequence[str]) -> str:
    pat = "|".join(label_patterns)
    s = re.sub(rf"^.*?(?:{pat})\s*(?:/[^:：]*?)?\s*[:：\-]?\s*", "", text, flags=re.I)
    return clean_str(s)


def extract_front_fields(lines_in: Sequence[OCRLine], full_blob: str) -> dict[str, str | None]:
    lines = sorted(lines_in, key=lambda l: (round(l.cy / 14), l.x1))
    right_lines = [l for l in lines if l.x1 >= 270]

    # 1. Ngày sinh
    dob: str | None = None
    for idx, ln in enumerate(right_lines):
        probe = f"{ln.tess_text} {ln.viet_full}"
        if re.search(r"(ng[aà]y\s*sinh|date\s*of\s*birth|birth)", probe, re.I):
            zone = f"{probe} {ln.viet_value}"
            for nxt in right_lines[idx + 1 : idx + 3]:
                if abs(nxt.cy - ln.cy) <= 40:
                    zone += f" {nxt.tess_text} {nxt.viet_full}"
            dob = parse_date(zone)
            if dob:
                break
    if dob is None:
        for ln in right_lines:
            if 345 <= ln.cy <= 435:
                d = parse_date(f"{ln.tess_text} {ln.viet_full}")
                if d and int(d[-4:]) <= 2024:
                    dob = d
                    break

    # 2. Giới tính & Quốc tịch
    gender: str | None = None
    nationality: str | None = None
    for ln in right_lines:
        row = f"{ln.tess_text} {ln.viet_full}"
        if gender is None and re.search(r"\b(nam|male)\b", row, re.I):
            gender = "Nam"
        elif gender is None and re.search(r"\b(n[uữ]|female)\b", row, re.I):
            gender = "Nữ"
        if nationality is None and re.search(r"(vi[eệ]t\s*nam|viet\s*nam)", row, re.I):
            nationality = "Việt Nam"

    # 3. Số CCCD
    cccd = extract_cccd_number(full_blob, gender=gender, dob=dob)

    # 4. Họ và tên (ưu tiên kết quả có dấu từ VietOCR trên dòng giá trị thuần tiếng Việt)
    def parse_name(raw: str) -> str | None:
        s = re.sub(r"[^A-Za-zÀ-ỹĐđ\s]", " ", raw)
        s = re.sub(r"\s+", " ", s).strip()
        s = re.sub(r"^[B-Z]\s+(?=[A-ZÀ-ỸĐ]{2,})", "", s)
        words = s.split()
        if 2 <= len(words) <= 7 and not _is_label_noise(s):
            return s.upper()
        return None

    full_name: str | None = None
    for idx, ln in enumerate(right_lines):
        probe = f"{ln.tess_text} {ln.viet_full}"
        if re.search(r"(h[oọ]\s*v[aà]\s*t[eê]n|full\s*name)", probe, re.I):
            cands = []
            if ln.viet_value:
                cands.append(ln.viet_value)
            inl = _strip_label_prefix(ln.best_vietnamese(prefer_after_colon=True), [r"h[oọ]\s*v[aà]\s*t[eê]n", r"full\s*name"])
            if inl:
                cands.append(inl)
            for nxt in right_lines[idx + 1 : idx + 3]:
                if 12 <= (nxt.cy - ln.cy) <= 68:
                    cands.append(nxt.best_vietnamese())
                    cands.append(nxt.tess_text)
            for c in cands:
                parsed = parse_name(c)
                if parsed:
                    full_name = parsed
                    break
            if full_name:
                break

    if full_name is None:
        for ln in right_lines:
            if 255 <= ln.cy <= 320:
                parsed = parse_name(ln.best_vietnamese()) or parse_name(ln.tess_text)
                if parsed:
                    full_name = parsed
                    break

    # 5. Quê quán & Nơi thường trú / Nơi cư trú
    def clean_addr_part(raw: str) -> str:
        s = clean_str(raw)
        return re.sub(r"\s+[a-zA-Z]{1,2}$", "", s).strip(" ,.-")

    def extract_address(label_res: Sequence[str], stop_res: Sequence[str], y_range: tuple[int, int]) -> str | None:
        for idx, ln in enumerate(right_lines):
            probe = f"{ln.tess_text} {ln.viet_full}"
            if not re.search("|".join(label_res), probe, re.I):
                continue
            parts: list[str] = []
            inl_raw = ln.viet_value or _strip_label_prefix(ln.best_vietnamese(prefer_after_colon=True), label_res)
            inl = clean_addr_part(inl_raw)
            if sum(ch.isalnum() for ch in inl) >= 3 and not _is_label_noise(inl):
                parts.append(inl)

            for nxt in right_lines[idx + 1 : idx + 5]:
                if (nxt.cy - ln.cy) > 105:
                    continue
                if re.search("|".join(stop_res), f"{nxt.tess_text} {nxt.viet_full}", re.I):
                    break
                cand = clean_addr_part(nxt.best_vietnamese())
                if sum(ch.isalpha() for ch in cand) >= 4 and not _is_label_noise(cand):
                    parts.append(cand)

            if parts:
                joined = re.sub(r",\s*,+", ", ", ", ".join(parts))
                return re.sub(r"\s+", " ", joined).strip(" ,.-")

        # Fallback theo vùng tọa độ chuẩn nếu nhãn bị mờ
        band_parts: list[str] = []
        for ln in right_lines:
            if y_range[0] <= ln.cy <= y_range[1]:
                cand = clean_addr_part(ln.viet_value or ln.best_vietnamese())
                if sum(ch.isalpha() for ch in cand) >= 4 and not _is_label_noise(cand):
                    band_parts.append(cand)
        if band_parts:
            return re.sub(r"\s+", " ", re.sub(r",\s*,+", ", ", ", ".join(band_parts))).strip(" ,.-")
        return None

    place_of_origin = extract_address(
        [r"qu[eê]\s*qu[aá]n", r"place\s*of\s*o(?:rigin|ngin)"],
        [r"n[oơ]i\s*th[uư][oờ]ng\s*tr[uú]", r"n[oơ]i\s*c[uư]\s*tr[uú]", r"place\s*of\s*residence"],
        (465, 512),
    )
    place_of_residence = extract_address(
        [r"n[oơ]i\s*th[uư][oờ]ng\s*tr[uú]", r"n[oơ]i\s*c[uư]\s*tr[uú]", r"place\s*of\s*residence"],
        [r"idvnm"],
        (515, 625),
    )

    # 6. Có giá trị đến (cột trái dưới ảnh chân dung: x1 < 220, cy >= 520)
    expiry: str | None = None
    for ln in lines:
        if ln.x1 < 220 and ln.cy >= 520:
            zone = f"{ln.tess_text} {ln.viet_full}"
            if re.search(r"kh[oô]ng\s*th[oờ]i\s*h[aạ]n", zone, re.I):
                expiry = "Không thời hạn"
                break
            d = parse_date(zone)
            if d and int(d[-4:]) >= 2020:
                expiry = d
                break

    return {
        "cccdNumber": cccd,
        "fullName": full_name,
        "dateOfBirth": dob,
        "gender": gender,
        "nationality": nationality,
        "placeOfOrigin": place_of_origin,
        "placeOfResidence": place_of_residence,
        "dateOfExpiry": expiry,
    }


def extract_back_fields(lines_in: Sequence[OCRLine], mrz_data: dict | None) -> dict:
    lines = sorted(lines_in, key=lambda l: (round(l.cy / 14), l.x1))
    top_left = [l for l in lines if l.x1 < 550 and l.cy < 155]

    pid_parts: list[str] = []
    issue_date: str | None = None

    for idx, ln in enumerate(top_left):
        probe = f"{ln.tess_text} {ln.viet_full}"
        if re.search(r"(?:[đd][aặ]c\s*[đd]i[eể]m\s*nh[aậ]n\s*d[aạ]ng|personal\s*identification)", probe, re.I):
            inl_raw = ln.viet_value or _strip_label_prefix(
                ln.best_vietnamese(prefer_after_colon=True),
                [r"[đd][aặ]c\s*[đd]i[eể]m\s*nh[aậ]n\s*d[aạ]ng", r"personal\s*identification"],
            )
            inl = clean_str(inl_raw)
            if sum(ch.isalpha() for ch in inl) >= 3 and not _is_label_noise(inl):
                pid_parts.append(inl)

            for nxt in top_left[idx + 1 : idx + 5]:
                if re.search(r"(ng[aà]y,?\s*th[aá]ng|date,?\s*month|c[uụ]c\s*tr[uư][oở]ng)", f"{nxt.tess_text} {nxt.viet_full}", re.I):
                    break
                cand = clean_str(nxt.best_vietnamese())
                if sum(ch.isalpha() for ch in cand) >= 3 and not _is_label_noise(cand):
                    pid_parts.append(cand)

        if issue_date is None and re.search(r"(ng[aà]y,?\s*th[aá]ng|date,?\s*month)", probe, re.I):
            zone = f"{probe} {ln.viet_value}"
            for nxt in top_left[idx + 1 : idx + 3]:
                if abs(nxt.cy - ln.cy) <= 35:
                    zone += f" {nxt.tess_text} {nxt.viet_full}"
            issue_date = parse_date(zone)

    if not pid_parts:
        for ln in top_left:
            if 30 <= ln.cy <= 88:
                cand = clean_str(ln.viet_value or ln.best_vietnamese())
                if sum(ch.isalpha() for ch in cand) >= 3 and not _is_label_noise(cand):
                    pid_parts.append(cand)

    if issue_date is None:
        for ln in top_left:
            if 75 <= ln.cy <= 135:
                d = parse_date(f"{ln.tess_text} {ln.viet_full}")
                if d:
                    issue_date = d
                    break

    personal_id = re.sub(r"\s+", " ", " ".join(pid_parts)).strip(" ,.-") if pid_parts else None
    if personal_id:
        personal_id = re.sub(r"(?i)\b(?:n[oôố]t\s*t[uủ]i|not\s*tudi)\b", "Nốt ruồi", personal_id)
        personal_id = re.sub(r"(?i)\btr[eéê]n[\s.]*sau\b", "trên sau", personal_id)
        personal_id = re.sub(r"(?i)\bc[aá]nh\s*m[aâ]i\s*tr[aá]i\b", "cánh mũi trái", personal_id)

    # Hỗ trợ thẻ Căn cước mẫu mới nếu Nơi cư trú nằm ở mặt sau
    back_res_parts: list[str] = []
    for idx, ln in enumerate(lines):
        if ln.x1 < 550 and 160 <= ln.cy <= 360:
            probe = f"{ln.tess_text} {ln.viet_full}"
            if re.search(r"(n[oơ]i\s*c[uư]\s*tr[uú]|place\s*of\s*residence)", probe, re.I):
                inl = clean_str(ln.viet_value or _strip_label_prefix(ln.best_vietnamese(True), [r"n[oơ]i\s*c[uư]\s*tr[uú]", r"place\s*of\s*residence"]))
                if sum(ch.isalnum() for ch in inl) >= 3 and not _is_label_noise(inl):
                    back_res_parts.append(inl)
                for nxt in lines[idx + 1 : idx + 4]:
                    if nxt.x1 < 550 and nxt.cy <= 365:
                        c = clean_str(nxt.best_vietnamese())
                        if sum(ch.isalpha() for ch in c) >= 4 and not _is_label_noise(c):
                            back_res_parts.append(c)
                break

    return {
        "personalIdentification": personal_id,
        "issueDate": issue_date,
        "placeOfResidence": re.sub(r"\s+", " ", ", ".join(back_res_parts)).strip(" ,.-") if back_res_parts else None,
        "mrz": mrz_data,
    }


# ==============================================================================
# 4. BỘ KIỂM TRA CHÍNH: CĂN CHỈNH THẺ, CHUẨN HÓA NỀN & OCR LAI
# ==============================================================================

class IDCardChecker:
    """Kiểm tra mặt trước/mặt sau, đo độ nét Laplacian và trích xuất thông tin CCCD."""

    def __init__(self, blur_threshold: float = BLUR_THRESHOLD) -> None:
        self.blur_threshold = blur_threshold
        self._predictor = None
        configure_tesseract()

    def _load_predictor(self):
        if self._predictor is not None:
            return self._predictor
        try:
            import torch
            from vietocr.tool.config import Cfg
            from vietocr.tool.predictor import Predictor
        except ImportError as err:
            raise RuntimeError(
                "Thiếu thư viện VietOCR. Cần cài: vietocr, torch, pillow, opencv-python, numpy, pytesseract, pymupdf."
            ) from err

        cfg = Cfg.load_config_from_name("vgg_transformer")
        cfg["device"] = "cuda:0" if torch.cuda.is_available() else "cpu"
        cfg["cnn"]["pretrained"] = False
        cfg.setdefault("predictor", {})["beamsearch"] = False
        self._predictor = Predictor(cfg)
        return self._predictor

    def read_image(self, image_path: str | Path):
        import cv2
        import numpy as np
        from PIL import Image, ImageOps

        path = Path(image_path)
        if not path.is_file():
            raise FileNotFoundError(str(path))
        with Image.open(path) as im:
            im = ImageOps.exif_transpose(im).convert("RGB")
            img = cv2.cvtColor(np.asarray(im), cv2.COLOR_RGB2BGR)
        longest = max(img.shape[:2])
        if longest > 2400:
            s = 2400.0 / longest
            img = cv2.resize(img, None, fx=s, fy=s, interpolation=cv2.INTER_AREA)
        return img

    def render_pdf_pages(self, pdf_path: str | Path) -> list:
        try:
            import fitz
            import numpy as np
        except ImportError as err:
            raise RuntimeError("Thiếu pymupdf để đọc file PDF.") from err
        from PIL import Image

        pages = []
        with fitz.open(str(pdf_path)) as doc:
            for page in doc:
                pix = page.get_pixmap(dpi=250)
                im = Image.frombytes("RGB", (pix.width, pix.height), pix.samples)
                pages.append(np.asarray(im)[:, :, ::-1].copy())
        return pages

    @staticmethod
    def _order_points(pts):
        import numpy as np

        rect = np.zeros((4, 2), dtype="float32")
        s = pts.sum(axis=1)
        rect[0], rect[2] = pts[np.argmin(s)], pts[np.argmax(s)]
        d = np.diff(pts, axis=1)
        rect[1], rect[3] = pts[np.argmin(d)], pts[np.argmax(d)]
        return rect

    def detect_and_warp_card(self, image):
        import cv2
        import numpy as np

        h, w = image.shape[:2]
        img_area = float(h * w)
        gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
        lab = cv2.cvtColor(image, cv2.COLOR_BGR2LAB).astype("float32")

        patch = lab[int(h * 0.42) : int(h * 0.58), int(w * 0.42) : int(w * 0.58)]
        dist = np.linalg.norm(lab - np.median(patch.reshape(-1, 3), axis=0), axis=2)

        masks = []
        for th in (34.0, 42.0, 50.0):
            m = (dist < th).astype("uint8") * 255
            m = cv2.morphologyEx(m, cv2.MORPH_CLOSE, cv2.getStructuringElement(cv2.MORPH_RECT, (15, 15)))
            m = cv2.morphologyEx(m, cv2.MORPH_OPEN, cv2.getStructuringElement(cv2.MORPH_RECT, (11, 11)))
            masks.append(m)

        blurred = cv2.GaussianBlur(gray, (5, 5), 0)
        for lo, hi in ((30, 100), (60, 170)):
            e = cv2.Canny(blurred, lo, hi)
            masks.append(cv2.morphologyEx(e, cv2.MORPH_CLOSE, cv2.getStructuringElement(cv2.MORPH_RECT, (7, 7))))

        best_quad, best_score = None, 0.0
        for mask in masks:
            contours, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
            for cnt in sorted(contours, key=cv2.contourArea, reverse=True)[:6]:
                hull = cv2.convexHull(cnt)
                area = cv2.contourArea(hull)
                if area < img_area * 0.12 or area > img_area * 0.99:
                    continue
                rb = cv2.minAreaRect(hull)
                bw, bh = rb[1]
                if bw == 0 or bh == 0:
                    continue
                rect_ness = area / (bw * bh)
                ratio = max(bw, bh) / min(bw, bh)
                if rect_ness >= 0.86 and 1.40 <= ratio <= 1.78:
                    peri = cv2.arcLength(hull, True)
                    approx = cv2.approxPolyDP(hull, 0.02 * peri, True)
                    pts = approx.reshape(4, 2).astype("float32") if len(approx) == 4 else cv2.boxPoints(rb).astype("float32")
                    rect = self._order_points(pts)
                    score = area * (rect_ness ** 2) * (1.0 - min(0.4, abs(ratio - 1.586)))
                    if score > best_score:
                        best_score = score
                        best_quad = rect

        if best_quad is not None:
            center = best_quad.mean(axis=0)
            best_quad = center + (best_quad - center) * 1.015
            best_quad[:, 0] = np.clip(best_quad[:, 0], 0, w - 1)
            best_quad[:, 1] = np.clip(best_quad[:, 1], 0, h - 1)

            wt = np.linalg.norm(best_quad[1] - best_quad[0])
            hl = np.linalg.norm(best_quad[3] - best_quad[0])
            dw, dh = (CARD_W, CARD_H) if wt >= hl else (CARD_H, CARD_W)
            dst = np.array([[0, 0], [dw - 1, 0], [dw - 1, dh - 1], [0, dh - 1]], dtype="float32")
            M = cv2.getPerspectiveTransform(best_quad.astype("float32"), dst)
            return cv2.warpPerspective(image, M, (dw, dh)), True

        ratio_raw = max(w, h) / max(1, min(w, h))
        if 1.38 <= ratio_raw <= 1.78:
            dw, dh = (CARD_W, CARD_H) if w >= h else (CARD_H, CARD_W)
            return cv2.resize(image, (dw, dh), interpolation=cv2.INTER_AREA), True
        return image, False

    def compute_sharpness(self, card_image) -> float:
        import cv2

        h, w = card_image.shape[:2]
        roi = card_image[int(h * 0.12) : int(h * 0.92), int(w * 0.05) : int(w * 0.95)]
        gray = cv2.cvtColor(roi, cv2.COLOR_BGR2GRAY)
        return float(cv2.Laplacian(gray, cv2.CV_64F).var())

    @staticmethod
    def normalize_line_for_ocr(crop_bgr, target_height: int = 48):
        """Chuẩn hóa ánh sáng dịu nhẹ, bảo toàn nguyên vẹn nét dấu thanh tiếng Việt và chữ số."""
        import cv2
        import numpy as np

        h, w = crop_bgr.shape[:2]
        scale = target_height / max(1, h)
        up = cv2.resize(crop_bgr, (max(16, int(w * scale)), target_height), interpolation=cv2.INTER_CUBIC)
        gray = cv2.cvtColor(up, cv2.COLOR_BGR2GRAY)
        k = max(21, (target_height // 2) * 2 + 1)
        bg = cv2.morphologyEx(gray, cv2.MORPH_CLOSE, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (k, k)))
        norm = np.clip((gray.astype(np.float32) / np.maximum(bg.astype(np.float32), 1.0)) * 248.0, 0, 255).astype(np.uint8)
        p1, p96 = np.percentile(norm, (1, 96))
        if p96 > p1 + 12:
            norm = np.clip((norm.astype(np.float32) - p1) * (255.0 / (p96 - p1)), 0, 255).astype(np.uint8)
        return norm

    def detect_and_rotate(self, image):
        import cv2
        import pytesseract

        orientations = (
            ("0", image),
            ("90_CW", cv2.rotate(image, cv2.ROTATE_90_CLOCKWISE)),
            ("180", cv2.rotate(image, cv2.ROTATE_180)),
            ("90_CCW", cv2.rotate(image, cv2.ROTATE_90_COUNTERCLOCKWISE)),
        )
        best = ("0", image, "", -1)
        for label, cand in orientations:
            h, w = cand.shape[:2]
            scaled = (
                cv2.resize(cand, None, fx=1100.0 / max(h, w), fy=1100.0 / max(h, w), interpolation=cv2.INTER_AREA)
                if max(h, w) > 1100
                else cand
            )
            red_ch = scaled[:, :, 2]
            try:
                txt = pytesseract.image_to_string(red_ch, lang="eng", config="--psm 6")
            except Exception:
                continue
            plain = normalize_ascii(txt)
            score = sum(
                4
                for kw in (
                    "can cuoc cong dan", "citizen identity card", "so dinh danh",
                    "dac diem nhan dang", "personal identification", "noi cu tru", "idvnm",
                )
                if fuzzy_has(plain, kw)
            )
            score += len(re.findall(r"(?<!\d)\d{12}(?!\d)", txt)) * 5
            if "ho va ten" in plain or "full name" in plain:
                score += 4
            if score > best[3]:
                best = (label, cand, txt, score)
        return best[1], best[0], best[2]

    def read_full_card_tesseract(self, card_img) -> str:
        import cv2
        import pytesseract

        chunks: list[str] = []
        red_ch = card_img[:, :, 2]
        top_left = cv2.cvtColor(card_img[0:175, 0:560], cv2.COLOR_BGR2GRAY)
        for img_in in (red_ch, top_left):
            for lang in ("vie+eng", "eng"):
                try:
                    t = pytesseract.image_to_string(img_in, lang=lang, config="--psm 6")
                    if t.strip():
                        chunks.append(t)
                        break
                except Exception:
                    continue
        return "\n".join(chunks)

    def detect_text_lines(self, card_img, is_back: bool) -> list[tuple[int, int, int, int]]:
        import cv2
        import numpy as np

        red = card_img[:, :, 2]
        bh = cv2.morphologyEx(red, cv2.MORPH_BLACKHAT, cv2.getStructuringElement(cv2.MORPH_RECT, (31, 13)))
        _, th = cv2.threshold(bh, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)
        conn = cv2.dilate(th, cv2.getStructuringElement(cv2.MORPH_RECT, (14, 1)), iterations=1)

        raw: list[tuple[int, int, int, int]] = []
        for cnt in cv2.findContours(conn, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)[0]:
            x, y, bw, bh_b = cv2.boundingRect(cnt)
            if bw < 20 or bh_b < 11 or bh_b > 90:
                continue
            if bh_b > 44:
                proj = np.sum(th[y : y + bh_b, x : x + bw] > 0, axis=1)
                ms, me = int(bh_b * 0.28), int(bh_b * 0.72)
                mi = ms + int(np.argmin(proj[ms:me]))
                if proj[mi] <= np.max(proj) * 0.25 and mi >= 11 and (bh_b - mi) >= 11:
                    raw.extend([(x, y, x + bw, y + mi), (x, y + mi, x + bw, y + bh_b)])
                    continue
            raw.append((x, y, x + bw, y + bh_b))

        raw.sort(key=lambda b: ((b[1] + b[3]) / 2.0, b[0]))
        rows: list[list[tuple[int, int, int, int]]] = []
        for box in raw:
            cy, bh_b = (box[1] + box[3]) / 2.0, box[3] - box[1]
            placed = False
            for row in rows:
                rcy = sum((b[1] + b[3]) / 2.0 for b in row) / len(row)
                rh = sum((b[3] - b[1]) for b in row) / len(row)
                if abs(cy - rcy) <= max(10.0, min(bh_b, rh) * 0.55):
                    row.append(box)
                    placed = True
                    break
            if not placed:
                rows.append([box])

        merged: list[tuple[int, int, int, int]] = []
        for row in rows:
            row.sort(key=lambda b: b[0])
            cur = row[0]
            for nxt in row[1:]:
                gap = nxt[0] - cur[2]
                crosses_col = (not is_back) and (cur[0] < 220 and nxt[0] >= 280)
                if gap <= 20 and not crosses_col:
                    cur = (min(cur[0], nxt[0]), min(cur[1], nxt[1]), max(cur[2], nxt[2]), max(cur[3], nxt[3]))
                else:
                    merged.append(cur)
                    cur = nxt
            merged.append(cur)

        merged.sort(key=lambda b: (round(((b[1] + b[3]) / 2.0) / 14), b[0]))
        return merged

    def read_mrz_bands(self, card_img, is_warped: bool) -> list[str]:
        import cv2
        import numpy as np
        import pytesseract
        from PIL import Image

        h, w = card_img.shape[:2]
        y0, y1 = (0.64, 0.94) if is_warped else (0.48, 0.95)
        reg = card_img[int(h * y0) : int(h * y1), int(w * 0.025) : int(w * 0.985)]

        max_rgb = np.max(reg, axis=2)
        bh = cv2.morphologyEx(max_rgb, cv2.MORPH_BLACKHAT, cv2.getStructuringElement(cv2.MORPH_RECT, (25, 11)))
        _, th = cv2.threshold(bh, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)
        active = np.sum(th > 0, axis=1) > (reg.shape[1] * 0.12)

        bands: list[tuple[int, int]] = []
        in_b, sy = False, 0
        for yi, fl in enumerate(active):
            if fl and not in_b:
                in_b, sy = True, yi
            elif not fl and in_b:
                in_b = False
                if yi - sy >= 16:
                    bands.append((sy, yi))
        if in_b and (len(active) - sy) >= 16:
            bands.append((sy, len(active)))

        out: list[str] = []
        for by1, by2 in bands[:3]:
            band_th = th[by1:by2, :]
            nz = np.where(np.sum(band_th > 0, axis=0) > 1)[0]
            x1 = max(0, int(nz[0]) - 18) if len(nz) else 0
            x2 = min(reg.shape[1], int(nz[-1]) + 22) if len(nz) else reg.shape[1]

            crop = reg[max(0, by1 - 6) : min(reg.shape[0], by2 + 6), x1:x2]
            norm = self.normalize_line_for_ocr(crop, target_height=56)
            norm_pad = cv2.copyMakeBorder(norm, 16, 16, 28, 28, cv2.BORDER_CONSTANT, value=255)
            _, otsu_pad = cv2.threshold(norm_pad, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)

            best_txt, best_sc = "", -1
            for proc in (norm_pad, otsu_pad):
                try:
                    raw = pytesseract.image_to_string(
                        Image.fromarray(proc),
                        lang="eng",
                        config="--psm 7 -c tessedit_char_whitelist=ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789<",
                    )
                except Exception:
                    continue
                compact = re.sub(r"[^A-Z0-9<]", "", raw.upper())
                sc = len(compact) + compact.count("<") * 2 + (20 if any(k in compact for k in ("IDVNM", "VNM", "<<")) else 0)
                if sc > best_sc:
                    best_sc, best_txt = sc, compact
            if best_txt:
                out.append(best_txt)
        return out

    def recognize_lines_hybrid(self, card_img, is_back: bool) -> list[OCRLine]:
        import cv2
        import pytesseract
        from PIL import Image, ImageOps

        h, w = card_img.shape[:2]
        boxes = self.detect_text_lines(card_img, is_back=is_back)

        try:
            predictor = self._load_predictor()
        except Exception:
            predictor = None

        label_tok_re = re.compile(
            r"^(?:h[oọ]|v[aà]|t[eê]n|full|name|qu[eê]|qu[aá]n|place|of|origin|ngin|"
            r"n[oơ]i|th[uư][oờ]ng|tr[uú]|c[uư]|residence|[đd][aặ]c|[đd]i[eể]m|nh[aậ]n|d[aạ]ng|"
            r"personal|identification|s[oố]|no|ng[aà]y|sinh|date|birth)[:：/.\-]*$",
            re.I,
        )

        results: list[OCRLine] = []
        for x1, y1, x2, y2 in boxes:
            cy = (y1 + y2) / 2.0
            if not is_back:
                if cy < 185 or (x1 < 220 and cy < 515):
                    continue
            else:
                if cy >= 395 or x1 >= 550:
                    continue

            crop = card_img[max(0, y1 - 5) : min(h, y2 + 5), max(0, x1 - 8) : min(w, x2 + 8)]
            if crop.size == 0:
                continue

            norm = self.normalize_line_for_ocr(crop, target_height=48)
            norm_pad = cv2.copyMakeBorder(norm, 12, 12, 18, 18, cv2.BORDER_CONSTANT, value=255)
            rgb_norm = cv2.cvtColor(norm, cv2.COLOR_GRAY2RGB)

            words: list[tuple[str, int, int]] = []
            for lang in ("vie+eng", "eng"):
                try:
                    wd = pytesseract.image_to_data(
                        Image.fromarray(norm_pad), lang=lang, config="--psm 7", output_type=pytesseract.Output.DICT
                    )
                    words = [
                        (
                            str(wd["text"][i]).strip(),
                            max(0, int(wd["left"][i]) - 18),
                            max(0, int(wd["left"][i]) + int(wd["width"][i]) - 18),
                        )
                        for i in range(len(wd["text"]))
                        if str(wd["text"][i]).strip() and float(wd["conf"][i]) >= 0
                    ]
                    if words:
                        break
                except Exception:
                    continue

            t_text = " ".join(item[0] for item in words).strip()

            colon_x2 = -1
            for wt, _, wx2 in words:
                if label_tok_re.match(wt) or ":" in wt or "：" in wt:
                    colon_x2 = wx2
                elif colon_x2 > 0:
                    break

            v_full = ""
            v_val = ""
            if predictor is not None:
                try:
                    pil_full = ImageOps.expand(Image.fromarray(rgb_norm), border=14, fill="white").convert("RGB")
                    v_full = str(predictor.predict(pil_full)).strip()
                except Exception:
                    v_full = ""

                if colon_x2 > 0 and (rgb_norm.shape[1] - colon_x2) >= 24:
                    val_slice = rgb_norm[:, max(0, colon_x2 + 2) :]
                    if val_slice.size > 0 and val_slice.shape[1] >= 14:
                        try:
                            pil_val = ImageOps.expand(Image.fromarray(val_slice), border=14, fill="white").convert("RGB")
                            v_val = str(predictor.predict(pil_val)).strip()
                        except Exception:
                            v_val = ""

            if not t_text and not v_full:
                continue

            results.append(
                OCRLine(
                    tess_text=t_text,
                    viet_full=v_full,
                    viet_value=v_val,
                    x1=x1,
                    y1=y1,
                    x2=x2,
                    y2=y2,
                )
            )
        return results

    def analyze_page(self, image) -> tuple[str | None, float, dict, dict]:
        oriented, rot_label, quick_text = self.detect_and_rotate(image)
        card_img, is_warped = self.detect_and_warp_card(oriented)
        blur_score = self.compute_sharpness(card_img)

        full_tess = self.read_full_card_tesseract(card_img)
        hint_plain = normalize_ascii(f"{quick_text}\n{full_tess}")

        front_kw = {
            kw: fuzzy_has(hint_plain, kw)
            for kw in ("can cuoc cong dan", "so dinh danh", "citizen identity card", "ho va ten", "que quan")
        }
        back_kw = {
            kw: fuzzy_has(hint_plain, kw)
            for kw in ("dac diem nhan dang", "personal identification", "noi cu tru", "cuc truong cuc canh sat")
        }

        if any(back_kw.values()) or "idvnm" in hint_plain:
            side = "BACK"
        elif any(front_kw.values()):
            side = "FRONT"
        else:
            side = None

        mrz_bands = self.read_mrz_bands(card_img, is_warped=is_warped) if side != "FRONT" else []
        mrz_data = parse_td1_mrz(mrz_bands, fallback_text=f"{quick_text}\n{full_tess}") if side != "FRONT" else None

        if side is None and mrz_data is not None:
            side = "BACK"

        ocr_lines = self.recognize_lines_hybrid(card_img, is_back=(side == "BACK"))
        full_blob = f"{quick_text}\n{full_tess}\n" + "\n".join(f"{l.tess_text} {l.viet_full}" for l in ocr_lines)
        full_plain = normalize_ascii(full_blob)

        front_kw_final = any(front_kw.values()) or any(
            fuzzy_has(full_plain, kw) for kw in ("can cuoc cong dan", "so dinh danh", "ho va ten")
        )
        back_kw_final = any(back_kw.values()) or any(
            fuzzy_has(full_plain, kw) for kw in ("dac diem nhan dang", "personal identification", "noi cu tru")
        )

        evidence = {
            "detectedRotation": rot_label,
            "cardAligned": is_warped,
            "frontKeywords": front_kw_final,
            "backKeywords": back_kw_final,
            "mrz": mrz_data is not None,
            "mrzChecksumValid": bool(mrz_data and mrz_data.get("checksumValid")),
            "mrzLines": mrz_data["lines"] if mrz_data else [],
        }

        if side == "FRONT":
            extracted = extract_front_fields(ocr_lines, full_blob=full_blob)
        elif side == "BACK":
            extracted = extract_back_fields(ocr_lines, mrz_data=mrz_data)
        else:
            extracted = {}

        return side, blur_score, evidence, extracted

    def iter_pages(self, target: str | Path) -> Iterable[tuple[int, object]]:
        path = Path(target)
        ext = path.suffix.lower()
        if ext == ".pdf":
            for page_num, im in enumerate(self.render_pdf_pages(path), start=1):
                yield page_num, im
        elif ext in {".jpg", ".jpeg", ".png", ".webp", ".bmp"}:
            yield 1, self.read_image(path)
        else:
            raise ValueError("Chỉ hỗ trợ ảnh JPG/JPEG/PNG/WEBP/BMP hoặc file PDF.")

    def check(self, targets: str | Path | Sequence[str | Path]) -> dict:
        if isinstance(targets, (str, Path)):
            targets = [targets]

        pages_out, front_anchors, back_anchors, blurry_anchors, errors = [], [], [], [], []
        front_data: dict[str, str | None] = {}
        back_data: dict = {}

        for target in targets:
            try:
                for page_anchor, image in self.iter_pages(target):
                    if image is None or getattr(image, "size", 0) == 0:
                        errors.append(f"{Path(target).name}: không đọc được ảnh.")
                        continue

                    side, blur_score, evidence, extracted = self.analyze_page(image)
                    is_blurry = blur_score < self.blur_threshold

                    if side == "FRONT":
                        front_anchors.append(page_anchor)
                        front_data.update({k: v for k, v in extracted.items() if v})
                    elif side == "BACK":
                        back_anchors.append(page_anchor)
                        back_data.update({k: v for k, v in extracted.items() if v})

                    if is_blurry:
                        blurry_anchors.append(page_anchor)

                    pages_out.append(
                        {
                            "pageAnchor": page_anchor,
                            "fileName": Path(target).name,
                            "detectedSide": side,
                            "isBlurry": is_blurry,
                            "laplacianVariance": round(blur_score, 2),
                            "keywordEvidence": evidence,
                            "extractedFields": extracted,
                        }
                    )
            except Exception as err:
                errors.append(f"{Path(target).name}: {type(err).__name__}: {err}")

        mrz_info = back_data.get("mrz") or {}
        mismatch_reasons: list[str] = []

        if front_data and mrz_info:
            if front_data.get("cccdNumber") and mrz_info.get("cccdNumber") and front_data["cccdNumber"] != mrz_info["cccdNumber"]:
                mismatch_reasons.append(f"Số CCCD mặt trước ({front_data['cccdNumber']}) khác MRZ ({mrz_info['cccdNumber']})")
            if front_data.get("dateOfBirth") and mrz_info.get("dateOfBirth") and front_data["dateOfBirth"] != mrz_info["dateOfBirth"]:
                mismatch_reasons.append(f"Ngày sinh mặt trước ({front_data['dateOfBirth']}) khác MRZ ({mrz_info['dateOfBirth']})")
            fn = normalize_ascii(front_data.get("fullName") or "")
            mn = normalize_ascii(mrz_info.get("fullName") or "")
            if fn and mn and SequenceMatcher(None, fn, mn).ratio() < 0.72:
                mismatch_reasons.append(f"Họ tên mặt trước ({fn}) khác MRZ ({mn})")

        if not front_anchors and not back_anchors:
            status = MISSING_BOTH_SIDES
        elif not front_anchors:
            status = MISSING_FRONT_SIDE
        elif not back_anchors:
            status = MISSING_BACK_SIDE
        elif mismatch_reasons:
            status = CARD_MISMATCH
        elif blurry_anchors:
            status = BLURRY
        else:
            status = VALID

        page_anchor = (
            front_anchors[0] if status == VALID and front_anchors
            else blurry_anchors[0] if status == BLURRY and blurry_anchors
            else (front_anchors[0] if front_anchors else (back_anchors[0] if back_anchors else 1))
        )

        return {
            "status": status,
            "pageAnchor": page_anchor,
            "cccdNumber": front_data.get("cccdNumber") or mrz_info.get("cccdNumber"),
            "fullName": front_data.get("fullName") or mrz_info.get("fullName"),
            "dateOfBirth": front_data.get("dateOfBirth") or mrz_info.get("dateOfBirth"),
            "gender": front_data.get("gender") or mrz_info.get("gender"),
            "nationality": front_data.get("nationality") or mrz_info.get("nationality"),
            "placeOfOrigin": front_data.get("placeOfOrigin"),
            "placeOfResidence": front_data.get("placeOfResidence") or back_data.get("placeOfResidence"),
            "dateOfExpiry": front_data.get("dateOfExpiry") or mrz_info.get("dateOfExpiry"),
            "personalIdentification": back_data.get("personalIdentification"),
            "issueDate": back_data.get("issueDate"),
            "mrzVerified": bool(mrz_info.get("checksumValid")) and not mismatch_reasons,
            "mismatchReasons": mismatch_reasons,
            "isComplete": bool(front_anchors and back_anchors),
            "isBlurry": bool(blurry_anchors),
            "frontPageAnchors": sorted(set(front_anchors)),
            "backPageAnchors": sorted(set(back_anchors)),
            "blurryPageAnchors": sorted(set(blurry_anchors)),
            "pages": pages_out,
            "errors": errors,
        }


def check_id_card(targets: str | Path | Sequence[str | Path], blur_threshold: float = BLUR_THRESHOLD) -> dict:
    return IDCardChecker(blur_threshold=blur_threshold).check(targets)


def main() -> int:
    import argparse

    parser = argparse.ArgumentParser(description="Kiểm tra và trích xuất thông tin CCCD Việt Nam")
    parser.add_argument("targets", nargs="+", help="Đường dẫn ảnh hoặc file PDF CCCD")
    parser.add_argument("--blur-threshold", type=float, default=BLUR_THRESHOLD)
    parser.add_argument("--out", help="Đường dẫn file JSON đầu ra")
    args = parser.parse_args()

    try:
        result = check_id_card(args.targets, blur_threshold=args.blur_threshold)
    except Exception as err:
        print(json.dumps({"error": f"{type(err).__name__}: {err}"}, ensure_ascii=False, indent=2))
        return 1

    output = json.dumps(result, ensure_ascii=False, indent=2)
    print(output)
    if args.out:
        out_path = Path(args.out)
    else:
        paths = [Path(t).resolve() for t in args.targets]
        dirs = {p.parent for p in paths}
        out_dir = next(iter(dirs)) if len(dirs) == 1 else paths[0].parent
        out_path = out_dir / f"{result.get('cccdNumber') or 'unknown_cccd'}.json"
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(output, encoding="utf-8")
    print(f"\nĐã lưu JSON: {out_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())