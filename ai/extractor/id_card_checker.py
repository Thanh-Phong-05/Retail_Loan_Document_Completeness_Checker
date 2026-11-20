"""Completeness and sharpness check for Vietnamese national ID cards.

Lenh chay thu:
    python ai/extractor/id_card_checker.py ai/classifier/sample/tin1.jpg ai/classifier/sample/tin2.jpg
    python ai/extractor/id_card_checker.py ai/classifier/sample/phuc1.jpg ai/classifier/sample/phuc2.jpg
( phuc1, phuc2, tin1, tin2 la anh cccd mat truoc va mat sau cua 2 nguoi khac nhau, co the thay bang anh cua minh de kiem tra )

Ghi ket qua vao file chi dinh:
    python ai/extractor/id_card_checker.py ai/classifier/sample/phuc1.jpg ai/classifier/sample/phuc2.jpg --out ket_qua.json
"""

from __future__ import annotations

import json
import re
import sys
import unicodedata
from pathlib import Path
from typing import Iterable, Sequence


VALID = "VALID"
MISSING_BACK_SIDE = "MISSING_BACK_SIDE"
BLURRY = "BLURRY"

FRONT_KEYWORDS = ("can cuoc cong dan", "so dinh danh")
BACK_KEYWORDS = ("dac diem nhan dang", "noi cu tru")
BLUR_THRESHOLD = 100.0


def normalize_text(text: str) -> str:
    text = text.replace("đ", "d").replace("Đ", "D")
    text = unicodedata.normalize("NFD", text)
    text = "".join(char for char in text if unicodedata.category(char) != "Mn")
    text = re.sub(r"[^a-z0-9\s<]", " ", text.lower())
    return re.sub(r"\s+", " ", text).strip()


def contains_mrz(text: str) -> bool:
    plain = normalize_text(text)
    if re.search(r"\b(?:idvnm|vnm|p<vnm)\b", plain):
        return True
    mrz_like = re.findall(r"[a-z0-9<]{20,}", plain)
    mrz_lines = [line for line in mrz_like if line.count("<") >= 4]
    return len(mrz_lines) >= 1


def extract_mrz_lines(text: str) -> list[str]:
    line1 = None
    line2 = None
    line3 = None
    for raw_line in text.upper().splitlines():
        line = re.sub(r"[^A-Z0-9<]", "", raw_line)
        if re.match(r"^IDVNM\d{12}[A-Z0-9<]{3,}", line):
            line1 = line[:44]
        if re.match(r"^\d{6,7}[MF]\d{7}VNM[A-Z0-9<]{8,}", line):
            line2 = line[:44]
        if re.match(r"^(NGO|LE)<<[A-Z]", line) and len(line) >= 15:
            line3 = line[:44]
    return [line for line in (line1, line2, line3) if line]


def fuzzy_contains(text: str, keyword: str, threshold: float = 0.72) -> bool:
    if keyword in text:
        return True
    from difflib import SequenceMatcher

    keyword_length = len(keyword)
    if len(text) < keyword_length:
        return False
    for start in range(0, len(text) - keyword_length + 2):
        window = text[start : start + keyword_length]
        if SequenceMatcher(None, window, keyword).ratio() >= threshold:
            return True
    return False


def extract_cccd_number(text: str) -> str | None:
    province_codes = {
        "001", "002", "004", "006", "008", "010", "011", "012", "014",
        "015", "017", "019", "020", "022", "024", "025", "026", "027",
        "030", "031", "033", "034", "035", "036", "037", "038", "040",
        "042", "044", "045", "046", "048", "049", "051", "052", "054",
        "056", "058", "060", "062", "064", "066", "067", "068", "070",
        "072", "074", "075", "077", "079", "080", "082", "083", "084",
        "086", "087", "089", "091", "092", "093", "094", "095", "096",
    }
    candidates = re.findall(r"(?<!\d)(\d{12})(?!\d)", text)
    candidates += re.findall(r"\b(\d{12})\b", text)

    compact = re.sub(r"[^0-9]", "", text)
    for start in range(max(0, len(compact) - 11)):
        candidate = compact[start : start + 12]
        if len(candidate) == 12:
            candidates.append(candidate)

    valid = [candidate for candidate in candidates if candidate[:3] in province_codes]
    if valid:
        counts = {}
        for candidate in valid:
            counts[candidate] = counts.get(candidate, 0) + 1
        return max(counts, key=lambda candidate: (counts[candidate], candidate == text[:12]))
    return None


class IDCardChecker:
    """Check whether an image/PDF set contains front and back ID-card pages."""

    def __init__(self, blur_threshold: float = BLUR_THRESHOLD) -> None:
        self.blur_threshold = blur_threshold
        self._predictor = None

    def _load_predictor(self):
        if self._predictor is not None:
            return self._predictor

        try:
            import torch
            from PIL import Image
            from vietocr.tool.config import Cfg
            from vietocr.tool.predictor import Predictor
        except ImportError as error:
            raise RuntimeError(
                "Thieu thu vien VietOCR. Can cai: vietocr, torch, pillow, opencv-python, numpy "
                "va pymupdf."
            ) from error

        config = Cfg.load_config_from_name("vgg_transformer")
        config["device"] = "cuda:0" if torch.cuda.is_available() else "cpu"
        config["cnn"]["pretrained"] = False
        config.setdefault("predictor", {})["beamsearch"] = False
        self._predictor = Predictor(config)
        return self._predictor

    def read_image(self, image_path: str | Path):
        import cv2
        import numpy as np
        from PIL import Image, ImageOps

        path = Path(image_path)
        if not path.is_file():
            raise FileNotFoundError(str(path))
        with Image.open(path) as pil_image:
            pil_image = ImageOps.exif_transpose(pil_image).convert("RGB")
            image = cv2.cvtColor(np.asarray(pil_image), cv2.COLOR_RGB2BGR)
        longest = max(image.shape[:2])
        if longest > 3200:
            scale = 3200.0 / longest
            image = cv2.resize(
                image,
                None,
                fx=scale,
                fy=scale,
                interpolation=cv2.INTER_AREA,
            )
        return image

    def render_pdf_pages(self, pdf_path: str | Path) -> list:
        try:
            import fitz
            import numpy as np
        except ImportError as error:
            raise RuntimeError("Thieu pymupdf de doc file PDF.") from error

        from PIL import Image

        pages = []
        with fitz.open(str(pdf_path)) as document:
            for page in document:
                pixmap = page.get_pixmap(dpi=200)
                image = Image.frombytes("RGB", (pixmap.width, pixmap.height), pixmap.samples)
                pages.append(np.asarray(image)[:, :, ::-1].copy())
        return pages

    def laplacian_variance(self, image) -> float:
        import cv2

        gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
        return float(cv2.Laplacian(gray, cv2.CV_64F).var())

    def detect_and_rotate(self, image):
        import cv2
        import os
        import pytesseract

        command = os.environ.get(
            "TESSERACT_CMD",
            r"C:\Program Files\Tesseract-OCR\tesseract.exe",
        )
        if Path(command).exists():
            pytesseract.pytesseract.tesseract_cmd = command

        orientations = (
            ("0", image),
            ("90_CW", cv2.rotate(image, cv2.ROTATE_90_CLOCKWISE)),
            ("180", cv2.rotate(image, cv2.ROTATE_180)),
            ("90_CCW", cv2.rotate(image, cv2.ROTATE_90_COUNTERCLOCKWISE)),
        )
        best = ("0", image, "", -1)

        for label, candidate in orientations:
            height, width = candidate.shape[:2]
            scaled = candidate
            if max(height, width) > 1600:
                scale = 1600.0 / max(height, width)
                scaled = cv2.resize(
                    candidate,
                    None,
                    fx=scale,
                    fy=scale,
                    interpolation=cv2.INTER_AREA,
                )
            gray = cv2.cvtColor(scaled, cv2.COLOR_BGR2GRAY)
            try:
                text = pytesseract.image_to_string(gray, lang="eng", config="--psm 6")
            except Exception:
                continue

            plain = normalize_text(text)
            score = sum(
                3
                for keyword in (
                    "can cuoc cong dan",
                    "citizen identity card",
                    "so dinh danh",
                    "dac diem nhan dang",
                    "personal identification",
                    "idvnm",
                )
                if fuzzy_contains(plain, keyword)
            )
            score += len(re.findall(r"(?<!\d)\d{12}(?!\d)", text)) * 5
            if score > best[3]:
                best = (label, candidate, text, score)

        return best[1], best[0], best[2]

    def read_mrz_region(self, image) -> str:
        import cv2
        import pytesseract
        from PIL import Image

        command = __import__("os").environ.get(
            "TESSERACT_CMD",
            r"C:\Program Files\Tesseract-OCR\tesseract.exe",
        )
        if Path(command).exists():
            pytesseract.pytesseract.tesseract_cmd = command

        height, width = image.shape[:2]
        outputs = []
        for start, end in ((0.48, 0.62), (0.45, 0.60), (0.70, 0.96), (0.68, 0.84)):
            crop = image[int(height * start):int(height * end), int(width * 0.02):int(width * 0.98)]
            gray = cv2.cvtColor(crop, cv2.COLOR_BGR2GRAY)
            gray = cv2.resize(gray, None, fx=2.0, fy=2.0, interpolation=cv2.INTER_CUBIC)
            gray = cv2.createCLAHE(3.0, (8, 8)).apply(gray)
            _, binary = cv2.threshold(gray, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)
            pil_image = Image.fromarray(binary)
            try:
                text = pytesseract.image_to_string(
                    pil_image,
                    lang="eng",
                    config="--psm 6 -c tessedit_char_whitelist=ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789<>",
                )
                outputs.append(text)
            except Exception:
                continue
        for text in outputs:
            if "IDVNM" in text.upper():
                return text
        return outputs[0] if outputs else ""

    def ocr_text(self, image) -> str:
        import cv2
        from PIL import Image, ImageOps

        variants = [image]
        height, width = image.shape[:2]
        if height > width:
            variants.extend([
                cv2.rotate(image, cv2.ROTATE_90_CLOCKWISE),
                cv2.rotate(image, cv2.ROTATE_90_COUNTERCLOCKWISE),
            ])
        gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
        variants.append(cv2.cvtColor(cv2.createCLAHE(2.0, (8, 8)).apply(gray), cv2.COLOR_GRAY2BGR))
        variants.append(cv2.rotate(image, cv2.ROTATE_180))

        recognized = []
        tesseract_error = None
        try:
            import pytesseract
        except ImportError as error:
            pytesseract = None
            tesseract_error = error
        if pytesseract is not None:
            import os

            command = os.environ.get("TESSERACT_CMD", r"C:\Program Files\Tesseract-OCR\tesseract.exe")
            if Path(command).exists():
                pytesseract.pytesseract.tesseract_cmd = command
            ranked_texts = []
            for variant in variants:
                rgb = cv2.cvtColor(variant, cv2.COLOR_BGR2RGB)
                pil_image = Image.fromarray(rgb)
                for psm in (6, 11):
                    try:
                        text = pytesseract.image_to_string(
                            pil_image,
                            lang="vie+eng",
                            config=f"--psm {psm}",
                        )
                    except Exception as error:
                        tesseract_error = error
                        try:
                            text = pytesseract.image_to_string(
                                pil_image,
                                lang="eng",
                                config=f"--psm {psm}",
                            )
                        except Exception as fallback_error:
                            tesseract_error = fallback_error
                            continue
                    if text.strip():
                        recognized.append(text.strip())
                        ranked_texts.append(text.strip())
                    break

        predictor_error = None
        try:
            predictor = self._load_predictor()
        except Exception as error:
            predictor_error = error
            predictor = None

        if predictor is not None:
            for variant in variants:
                rgb = cv2.cvtColor(variant, cv2.COLOR_BGR2RGB)
                pil_image = ImageOps.expand(
                    Image.fromarray(rgb),
                    border=8,
                    fill="white",
                )
                try:
                    text = predictor.predict(pil_image)
                except Exception:
                    continue
                if text:
                    recognized.append(str(text).strip())

        if not recognized:
            details = []
            if tesseract_error:
                details.append(f"Tesseract: {tesseract_error}")
            if predictor_error:
                details.append(f"VietOCR: {predictor_error}")
            raise RuntimeError(
                "Khong the OCR anh. " + "; ".join(details)
            )
        unique_texts = list(dict.fromkeys(recognized))
        if ranked_texts:
            def keyword_score(candidate: str) -> int:
                plain_candidate = normalize_text(candidate)
                return sum(
                    fuzzy_contains(plain_candidate, keyword)
                    for keyword in (
                        "can cuoc cong dan",
                        "citizen identity card",
                        "so dinh danh",
                        "dac diem nhan dang",
                        "personal identification",
                        "idvnm",
                    )
                )

            best = max(ranked_texts, key=keyword_score)
            if keyword_score(best) > 0:
                return best
        return "\n".join(unique_texts)

    def classify_page(self, image) -> tuple[str | None, str, float, dict[str, bool]]:
        image, detected_rotation, oriented_text = self.detect_and_rotate(image)
        text = self.ocr_text(image)
        try:
            import cv2
            import pytesseract

            command = __import__("os").environ.get(
                "TESSERACT_CMD",
                r"C:\Program Files\Tesseract-OCR\tesseract.exe",
            )
            if Path(command).exists():
                pytesseract.pytesseract.tesseract_cmd = command

            height, width = image.shape[:2]
            if height > width:
                cw = cv2.rotate(image, cv2.ROTATE_90_CLOCKWISE)
                gray = cv2.cvtColor(cw, cv2.COLOR_BGR2GRAY)
                gray = cv2.resize(gray, None, fx=2.0, fy=2.0, interpolation=cv2.INTER_CUBIC)
                cw_text = pytesseract.image_to_string(gray, lang="eng", config="--psm 6")
                if len(cw_text.strip()) > len(text.strip()):
                    text = cw_text
        except Exception:
            pass
        if oriented_text.strip():
            text = oriented_text
        plain = normalize_text(text)
        blur_score = self.laplacian_variance(image)

        front_matches = {
            keyword: fuzzy_contains(plain, keyword)
            for keyword in (
                "can cuoc cong dan",
                "so dinh danh",
                "citizen identity card",
                "cccd",
            )
        }
        front_strong = (
            front_matches.get("can cuoc cong dan", False)
            or front_matches.get("citizen identity card", False)
            or front_matches.get("so dinh danh", False)
            or front_matches.get("cccd", False)
        )
        back_matches = {
            keyword: fuzzy_contains(plain, keyword)
            for keyword in (
                "dac diem nhan dang",
                "personal identification",
                "noi cu tru",
                "noi thuong tru",
                "place of residence",
            )
        }
        back_personal = (
            back_matches.get("dac diem nhan dang", False)
            or back_matches.get("personal identification", False)
        )
        back_residence = (
            back_matches.get("noi cu tru", False)
            or back_matches.get("noi thuong tru", False)
            or back_matches.get("place of residence", False)
        )
        mrz_lines: list[str] = []
        if back_personal:
            side = "BACK"
            mrz_lines = extract_mrz_lines(text)
            if not mrz_lines:
                mrz_lines = extract_mrz_lines(self.read_mrz_region(image))
        elif front_strong:
            side = "FRONT"
        else:
            mrz_region_text = self.read_mrz_region(image)
            mrz_lines = extract_mrz_lines(mrz_region_text)
            if mrz_lines:
                side = "BACK"
            else:
                side = None

        mrz_match = bool(mrz_lines)
        evidence = {
            "frontKeywords": any(front_matches.values()),
            "frontNumberKeyword": front_matches.get("so dinh danh", False),
            "backPersonalIdentification": (
                back_matches.get("dac diem nhan dang", False)
                or back_matches.get("personal identification", False)
            ),
            "backResidence": (
                back_matches.get("noi cu tru", False)
                or back_matches.get("noi thuong tru", False)
                or back_matches.get("place of residence", False)
            ),
            "mrz": mrz_match,
            "mrzLines": mrz_lines,
        }
        return side, text, blur_score, evidence

    def iter_pages(self, target: str | Path) -> Iterable[tuple[int, object]]:
        path = Path(target)
        suffix = path.suffix.lower()
        if suffix == ".pdf":
            for page_number, image in enumerate(self.render_pdf_pages(path), start=1):
                yield page_number, image
        elif suffix in {".jpg", ".jpeg", ".png"}:
            yield 1, self.read_image(path)
        else:
            raise ValueError("Chi ho tro anh JPG/JPEG/PNG hoac PDF.")

    def check(self, targets: str | Path | Sequence[str | Path]) -> dict:
        import cv2

        if isinstance(targets, (str, Path)):
            targets = [targets]

        page_results = []
        front_anchors = []
        back_anchors = []
        blurry_anchors = []
        errors = []
        cccd_numbers = []

        for target in targets:
            try:
                for page_anchor, image in self.iter_pages(target):
                    if image is None or getattr(image, "size", 0) == 0:
                        errors.append(f"{Path(target).name}: khong doc duoc anh.")
                        continue

                    side, text, blur_score, evidence = self.classify_page(image)
                    is_blurry = blur_score < self.blur_threshold
                    cccd_number = extract_cccd_number(text)
                    if cccd_number:
                        cccd_numbers.append(cccd_number)
                    if side == "FRONT":
                        front_anchors.append(page_anchor)
                    elif side == "BACK":
                        back_anchors.append(page_anchor)
                    if is_blurry:
                        blurry_anchors.append(page_anchor)

                    page_results.append(
                        {
                            "pageAnchor": page_anchor,
                            "fileName": Path(target).name,
                            "detectedSide": side,
                            "isBlurry": is_blurry,
                            "laplacianVariance": round(blur_score, 2),
                            "keywordEvidence": evidence,
                        }
                    )
            except Exception as error:
                errors.append(f"{Path(target).name}: {type(error).__name__}: {error}")

        if not front_anchors or not back_anchors:
            status = MISSING_BACK_SIDE
        elif blurry_anchors:
            status = BLURRY
        else:
            status = VALID

        if status == VALID:
            page_anchor = front_anchors[0]
        elif status == BLURRY:
            page_anchor = blurry_anchors[0]
        else:
            page_anchor = front_anchors[0] if front_anchors else (back_anchors[0] if back_anchors else 1)

        return {
            "status": status,
            "pageAnchor": page_anchor,
            "cccdNumber": cccd_numbers[0] if cccd_numbers else None,
            "isComplete": bool(front_anchors and back_anchors),
            "isBlurry": bool(blurry_anchors),
            "frontPageAnchors": sorted(set(front_anchors)),
            "backPageAnchors": sorted(set(back_anchors)),
            "blurryPageAnchors": sorted(set(blurry_anchors)),
            "pages": page_results,
            "errors": errors,
        }


def check_id_card(
    targets: str | Path | Sequence[str | Path],
    blur_threshold: float = BLUR_THRESHOLD,
) -> dict:
    return IDCardChecker(blur_threshold=blur_threshold).check(targets)


def main() -> int:
    import argparse

    parser = argparse.ArgumentParser(description="Kiem tra mat truoc/mat sau va do net CCCD bang VietOCR")
    parser.add_argument("targets", nargs="+", help="Anh hoac PDF can kiem tra")
    parser.add_argument("--blur-threshold", type=float, default=BLUR_THRESHOLD)
    parser.add_argument("--out", help="Ghi ket qua JSON ra duong dan nay")
    args = parser.parse_args()

    try:
        result = check_id_card(args.targets, blur_threshold=args.blur_threshold)
    except Exception as error:
        result = {"error": f"{type(error).__name__}: {error}"}
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 1
    output = json.dumps(result, ensure_ascii=False, indent=2)
    print(output)
    if args.out:
        output_path = Path(args.out)
        output_path.parent.mkdir(parents=True, exist_ok=True)
    else:
        target_paths = [Path(target).resolve() for target in args.targets]
        directories = {path.parent for path in target_paths}
        output_dir = next(iter(directories)) if len(directories) == 1 else target_paths[0].parent
        number = result.get("cccdNumber") or "unknown_cccd"
        output_path = output_dir / f"{number}.json"
        output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(output, encoding="utf-8")
    print(f"\nDa ghi JSON: {output_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
