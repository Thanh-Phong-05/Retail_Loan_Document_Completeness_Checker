"""ai/extractor/id_card_checker.py

Single-file Vietnam ID Card OCR, Quality Assessment, Classification & Validation Module.
Supports:
  - Old Citizen Identity Card (CITIZEN_ID_CARD / Căn cước công dân)
  - New Identity Card (IDENTITY_CARD / Căn cước)
  - Front & back side classification, orientation correction (0/90/180/270 deg),
    perspective rectification, Laplacian sharpness evaluation, dual OCR fusion
    (Tesseract vie+eng + VietOCR CPU/CUDA), accent-insensitive fuzzy matching,
    context-aware regex extraction, TD1 ICAO-9303 MRZ parsing & checksum validation,
    anti-hallucination field validation, cross-side identity consistency verification,
    PII-safe logging, classifier.py integration, and FastAPI endpoint.
"""

from __future__ import annotations

import asyncio
import calendar
import dataclasses
import datetime as dt
import difflib
import io
import json
import logging
import math
import os
import re
import shutil
import sys
import tempfile
import time
import unicodedata
from dataclasses import dataclass, field
from enum import Enum
from pathlib import Path
from typing import Any, Dict, List, Optional, Sequence, Tuple, Union

import cv2
import numpy as np
from PIL import Image, ImageOps
import pytesseract
from pytesseract import Output
from pydantic import BaseModel, Field

from fastapi import APIRouter, FastAPI, File, Form, HTTPException, UploadFile
from fastapi.responses import JSONResponse

# Optional PDF & VietOCR dependencies (graceful degradation if absent)
try:
    import fitz  # PyMuPDF
except ImportError:  # pragma: no cover
    fitz = None  # type: ignore

try:
    from pdf2image import convert_from_bytes  # type: ignore
except ImportError:  # pragma: no cover
    convert_from_bytes = None  # type: ignore

try:
    import torch
except ImportError:  # pragma: no cover
    torch = None  # type: ignore

try:
    from vietocr.tool.config import Cfg as VietOCRCfg  # type: ignore
    from vietocr.tool.predictor import Predictor as VietOCRPredictor  # type: ignore
except ImportError:  # pragma: no cover
    VietOCRCfg = None  # type: ignore
    VietOCRPredictor = None  # type: ignore


# ==============================================================================
# 1. SECURITY & PII-MASKING LOGGER
# ==============================================================================

_PII_PATTERNS: List[Tuple[re.Pattern[str], str]] = [
    (re.compile(r"\b(\d{4})\d{4}(\d{4})\b"), r"\1****\2"),
    (re.compile(r"\b(\d{2}/\d{2}/)\d{4}\b"), r"\1****"),
    (re.compile(r"(IDVNM[A-Z0-9<]{5})[A-Z0-9<]+"), r"\1****************"),
    (re.compile(r"([A-Z]{2,}<<[A-Z<]{4,})"), r"[REDACTED_MRZ_NAME]"),
]


def mask_pii(message: str) -> str:
    """Redact sensitive ID numbers, dates of birth, and MRZ strings before logging."""
    masked = str(message)
    for pattern, replacement in _PII_PATTERNS:
        masked = pattern.sub(replacement, masked)
    return masked


class PIIMaskingFilter(logging.Filter):
    """Logging filter that guarantees sensitive personal data is never written to logs."""

    def filter(self, record: logging.LogRecord) -> bool:
        if isinstance(record.msg, str):
            record.msg = mask_pii(record.msg)
        if record.args:
            if isinstance(record.args, tuple):
                record.args = tuple(
                    mask_pii(a) if isinstance(a, str) else a for a in record.args
                )
            elif isinstance(record.args, dict):
                record.args = {
                    k: mask_pii(v) if isinstance(v, str) else v
                    for k, v in record.args.items()
                }
        return True


logger = logging.getLogger("ai.extractor.id_card_checker")
if not logger.handlers:
    _handler = logging.StreamHandler(sys.stderr)
    _handler.setFormatter(
        logging.Formatter("%(asctime)s [%(levelname)s] %(name)s: %(message)s")
    )
    _handler.addFilter(PIIMaskingFilter())
    logger.addHandler(_handler)
logger.setLevel(logging.INFO)
logger.propagate = False


# ==============================================================================
# 2. ENUMS, CONFIGURATION & PYDANTIC SCHEMAS
# ==============================================================================


class DocumentType(str, Enum):
    CITIZEN_ID_CARD = "CITIZEN_ID_CARD"
    IDENTITY_CARD = "IDENTITY_CARD"
    UNKNOWN = "UNKNOWN"


class CardSide(str, Enum):
    FRONT = "front"
    BACK = "back"
    UNKNOWN = "unknown"


class CheckStatus(str, Enum):
    VALID = "VALID"
    MISSING_FRONT_SIDE = "MISSING_FRONT_SIDE"
    MISSING_BACK_SIDE = "MISSING_BACK_SIDE"
    MISSING_BOTH_SIDES = "MISSING_BOTH_SIDES"
    BLURRY = "BLURRY"
    UNREADABLE = "UNREADABLE"
    UNKNOWN_DOCUMENT = "UNKNOWN_DOCUMENT"


class ImageQualityStatus(str, Enum):
    SHARP = "SHARP"
    ACCEPTABLE = "ACCEPTABLE"
    BLURRY = "BLURRY"


DOCUMENT_NAMES: Dict[DocumentType, Optional[str]] = {
    DocumentType.CITIZEN_ID_CARD: "Căn cước công dân",
    DocumentType.IDENTITY_CARD: "Căn cước",
    DocumentType.UNKNOWN: None,
}

SUPPORTED_IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png", ".webp"}
SUPPORTED_EXTENSIONS = SUPPORTED_IMAGE_EXTENSIONS | {".pdf"}
SUPPORTED_MIME_TYPES = {
    "image/jpeg",
    "image/jpg",
    "image/png",
    "image/webp",
    "application/pdf",
}

MAX_UPLOAD_FILE_BYTES = int(os.environ.get("ID_CARD_MAX_FILE_BYTES", 15 * 1024 * 1024))
MAX_PDF_PAGES = int(os.environ.get("ID_CARD_MAX_PDF_PAGES", 10))
PROCESSING_TIMEOUT_SECONDS = float(os.environ.get("ID_CARD_TIMEOUT_SEC", 45.0))


@dataclass
class QualityConfig:
    """Configurable resolution-aware thresholds for sharpness & image quality."""

    blur_threshold_blurry: float = 55.0
    blur_threshold_sharp: float = 135.0
    min_card_width_px: int = 380
    min_card_height_px: int = 230
    min_contrast_std: float = 20.0
    min_readable_ocr_conf: float = 0.35
    reference_width_px: int = 1280


@dataclass
class EngineConfig:
    """Runtime configuration for Tesseract and VietOCR."""

    tesseract_cmd: Optional[str] = os.environ.get(
        "TESSERACT_CMD",
        r"C:\Program Files\Tesseract-OCR\tesseract.exe"
        if os.name == "nt"
        else "/usr/bin/tesseract",
    )
    tesseract_lang: str = os.environ.get("TESSERACT_LANG", "vie+eng")
    vietocr_config_name: str = os.environ.get("VIETOCR_CONFIG", "vgg_transformer")
    vietocr_weights_path: Optional[str] = os.environ.get("VIETOCR_WEIGHTS", None)
    vietocr_device: Optional[str] = os.environ.get("VIETOCR_DEVICE", None)
    enable_vietocr: bool = os.environ.get("ENABLE_VIETOCR", "1").lower() not in {
        "0",
        "false",
        "no",
    }
    pdf_render_dpi: int = int(os.environ.get("ID_CARD_PDF_DPI", 250))


class PageAnchorModel(BaseModel):
    front: List[int] = Field(default_factory=list)
    back: List[int] = Field(default_factory=list)


class ValidationSummaryModel(BaseModel):
    is_complete: bool = False
    image_quality: str = ImageQualityStatus.BLURRY.value
    confidence: float = 0.0


class MRZDataModel(BaseModel):
    raw: Optional[str] = None
    normalized: Optional[str] = None
    lines: List[str] = Field(default_factory=list)
    valid_format: Optional[bool] = None
    checksum_valid: Optional[bool] = None
    confidence: float = 0.0


class IDCardExtractedDataModel(BaseModel):
    id_number: Optional[str] = None
    personal_id_number: Optional[str] = None
    full_name: Optional[str] = None
    date_of_birth: Optional[str] = None
    gender: Optional[str] = None
    nationality: Optional[str] = None
    place_of_origin: Optional[str] = None
    permanent_residence: Optional[str] = None
    place_of_residence: Optional[str] = None
    place_of_birth_registration: Optional[str] = None
    personal_identification: Optional[str] = None
    issue_date: Optional[str] = None
    expiry_date: Optional[str] = None
    mrz: MRZDataModel = Field(default_factory=MRZDataModel)


class OCRTextModel(BaseModel):
    front: Optional[str] = None
    back: Optional[str] = None


class IDCardCheckResponse(BaseModel):
    success: bool
    status: str
    document_type: str
    document_name: Optional[str] = None
    front_detected: bool
    back_detected: bool
    pageAnchor: PageAnchorModel
    validation: ValidationSummaryModel
    data: IDCardExtractedDataModel
    field_confidence: Dict[str, float] = Field(default_factory=dict)
    field_sources: Dict[str, Any] = Field(default_factory=dict)
    ocr_text: OCRTextModel = Field(default_factory=OCRTextModel)
    errors: List[str] = Field(default_factory=list)
    warnings: List[str] = Field(default_factory=list)


def _to_json_safe(obj: Any) -> Any:
    """Recursively convert NumPy / custom objects into pure Python JSON-serializable types."""
    if isinstance(obj, dict):
        return {str(k): _to_json_safe(v) for k, v in obj.items()}
    if isinstance(obj, (list, tuple)):
        return [_to_json_safe(v) for v in obj]
    if isinstance(obj, Enum):
        return obj.value
    if isinstance(obj, np.integer):
        return int(obj)
    if isinstance(obj, np.floating):
        val = float(obj)
        return 0.0 if (math.isnan(val) or math.isinf(val)) else round(val, 4)
    if isinstance(obj, np.bool_):
        return bool(obj)
    if isinstance(obj, np.ndarray):
        return obj.tolist()
    if dataclasses.is_dataclass(obj):
        return _to_json_safe(dataclasses.asdict(obj))
    if isinstance(obj, BaseModel):
        return _to_json_safe(obj.model_dump())
    if isinstance(obj, float):
        return 0.0 if (math.isnan(obj) or math.isinf(obj)) else round(obj, 4)
    return obj


# ==============================================================================
# 3. VIETNAMESE NORMALIZATION, FUZZY MATCHING & CONTEXT-AWARE REGEX
# ==============================================================================


def normalize_vietnamese(text: str) -> str:
    """Convert Vietnamese text into lowercase ASCII without accents for keyword/label matching.
    Never modifies original text in place; returns a separate normalized copy.
    """
    if not text:
        return ""
    s = text.replace("đ", "d").replace("Đ", "D")
    s = unicodedata.normalize("NFD", s)
    s = "".join(ch for ch in s if unicodedata.category(ch) != "Mn")
    s = re.sub(r"[^a-zA-Z0-9\s/<:.,\-]", " ", s.lower())
    return re.sub(r"\s+", " ", s).strip()


def normalize_ocr_text(text: str) -> str:
    """Clean OCR control characters and collapse redundant spaces while preserving line structure."""
    if not text:
        return ""
    lines: List[str] = []
    for raw_line in text.replace("\r\n", "\n").replace("\r", "\n").split("\n"):
        cleaned = re.sub(r"[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]", "", raw_line)
        cleaned = re.sub(r"[ \t]+", " ", cleaned).strip()
        if cleaned:
            lines.append(cleaned)
    return "\n".join(lines)


def match_keyword(text: str, keywords: List[str]) -> bool:
    """Return True if any keyword (accent-insensitive) matches inside text."""
    norm_text = normalize_vietnamese(text)
    if not norm_text:
        return False
    for kw in keywords:
        norm_kw = normalize_vietnamese(kw)
        if not norm_kw:
            continue
        if re.search(rf"(?:^|\b|\s){re.escape(norm_kw)}(?:$|\b|\s)", norm_text):
            return True
    return False


def fuzzy_match_keyword(
    text: str, keywords: List[str], threshold: float = 0.78
) -> Dict[str, Any]:
    """Fuzzy match accent-insensitive keywords against lines and sliding word windows."""
    norm_text = normalize_vietnamese(text)
    if not norm_text or not keywords:
        return {
            "matched": False,
            "keyword": None,
            "matched_span": None,
            "score": 0.0,
        }

    lines = [normalize_vietnamese(ln) for ln in text.splitlines() if ln.strip()]
    best_score = 0.0
    best_kw: Optional[str] = None
    best_span: Optional[str] = None

    for kw in keywords:
        norm_kw = normalize_vietnamese(kw)
        if not norm_kw:
            continue
        if norm_kw in norm_text:
            return {
                "matched": True,
                "keyword": kw,
                "matched_span": norm_kw,
                "score": 1.0,
            }
        kw_tokens = norm_kw.split()
        k_len = len(kw_tokens)
        for line in lines:
            tokens = line.split()
            if not tokens:
                continue
            if len(tokens) <= k_len + 2:
                ratio = difflib.SequenceMatcher(None, norm_kw, line).ratio()
                if ratio > best_score:
                    best_score = ratio
                    best_kw = kw
                    best_span = line
            for win_size in range(max(1, k_len - 1), min(len(tokens), k_len + 2) + 1):
                for idx in range(0, len(tokens) - win_size + 1):
                    candidate = " ".join(tokens[idx : idx + win_size])
                    ratio = difflib.SequenceMatcher(None, norm_kw, candidate).ratio()
                    if ratio > best_score:
                        best_score = ratio
                        best_kw = kw
                        best_span = candidate

    return {
        "matched": best_score >= threshold,
        "keyword": best_kw if best_score >= threshold else None,
        "matched_span": best_span if best_score >= threshold else None,
        "score": round(best_score, 4),
    }


def _repair_numeric_ocr_tokens(raw_digits: str) -> str:
    """Context-aware digit repair ONLY inside verified numeric/date fields (never on names/addresses)."""
    trans = str.maketrans(
        {
            "O": "0",
            "o": "0",
            "D": "0",
            "Q": "0",
            "I": "1",
            "l": "1",
            "|": "1",
            "Z": "2",
            "z": "2",
            "S": "5",
            "s": "5",
            "G": "6",
            "B": "8",
        }
    )
    return raw_digits.translate(trans)


# Next-label boundary regex (accentless) used to prevent value bleed-through across fields
_NEXT_LABEL_BOUNDARY = (
    r"(?="
    r"\b(?:so\s*/\s*no|so\s+dinh\s+danh|personal\s+identification|"
    r"ho\s+va\s+ten|ho[,\s]+chu\s+dem|full\s+name|"
    r"ngay\s+sinh|ngay[,\s]+thang[,\s]+nam\s+sinh|date\s+of\s+birth|"
    r"gioi\s+tinh|sex|quoc\s+tich|nationality|"
    r"que\s+quan|place\s+of\s+origin|"
    r"noi\s+thuong\s+tru|noi\s+cu\s+tru|place\s+of\s+residence|"
    r"noi\s+dang\s+ky\s+khai\s+sinh|place\s+of\s+birth|"
    r"co\s+gia\s+tri\s+den|date\s+of\s+expiry|"
    r"dac\s+diem\s+nhan\s+dang|"
    r"ngay[,\s]+thang[,\s]+nam\s+cap|ngay[,\s]+thang[,\s]+nam\s+het\s+han|"
    r"ngay[,\s]+thang[,\s]+nam|date\s+of\s+issue|date[,\s]+month[,\s]+year|"
    r"cuc\s+truong|bo\s+cong\s+an|ministry\s+of\s+public|ngon\s+tro|idvnm)\b"
    r"|$)"
)


# Dedicated regex patterns separated by card type and field semantics
OLD_CCCD_FRONT_PATTERNS: Dict[str, List[re.Pattern[str]]] = {
    "id_number": [
        re.compile(
            r"(?:s[ốo]\s*/\s*no\.?|s[ốo]\s*cccd|no\.?)\s*[:.\-]?\s*([0-9OoIl|SsBb]{12})\b",
            re.IGNORECASE,
        ),
        re.compile(r"\b(0[0-9]{11})\b"),
    ],
    "full_name": [
        re.compile(
            r"(?:h[ọo]\s+v[àa]\s+t[êe]n\s*(?:/\s*full\s+name)?|full\s+name)\s*[:.\-]?\s*\n?\s*"
            r"([A-ZÀÁÂÃÈÉÊÌÍÒÓÔÕÙÚĂĐĨŨƠƯẠ-Ỹ][A-ZÀÁÂÃÈÉÊÌÍÒÓÔÕÙÚĂĐĨŨƠƯẠ-Ỹ\s]{3,55})",
            re.IGNORECASE,
        ),
    ],
    "date_of_birth": [
        re.compile(
            r"(?:ng[àa]y\s+sinh\s*(?:/\s*date\s+of\s+birth)?|date\s+of\s+birth)\s*[:.\-]?\s*\n?\s*"
            r"([0-9OoIl]{1,2}\s*[/\-.]\s*[0-9OoIl]{1,2}\s*[/\-.]\s*[0-9OoIl]{4})",
            re.IGNORECASE,
        ),
    ],
    "gender": [
        re.compile(
            r"(?:gi[ớo]i\s+t[íi]nh\s*(?:/\s*sex)?|sex)\s*[:.\-]?\s*\n?\s*(nam|n[ữư]|male|female)\b",
            re.IGNORECASE,
        ),
    ],
    "nationality": [
        re.compile(
            r"(?:qu[ốo]c\s+t[ịi]ch\s*(?:/\s*nationality)?|nationality)\s*[:.\-]?\s*\n?\s*"
            r"(vi[ệe]t\s*nam|vietnam)\b",
            re.IGNORECASE,
        ),
    ],
    "place_of_origin": [
        re.compile(
            r"(?:qu[êe]\s+qu[áa]n\s*(?:/\s*place\s+of\s+origin)?|place\s+of\s+origin)\s*[:.\-]?\s*\n?\s*"
            r"([^\n]{4,90}(?:\n[^\n]{3,90})?)",
            re.IGNORECASE,
        ),
    ],
    "permanent_residence": [
        re.compile(
            r"(?:n[ơo]i\s+th[ườu][ờo]ng\s+tr[úu]\s*(?:/\s*place\s+of\s+residence)?)\s*[:.\-]?\s*\n?\s*"
            r"([^\n]{4,100}(?:\n[^\n]{3,100})?)",
            re.IGNORECASE,
        ),
    ],
}

OLD_CCCD_BACK_PATTERNS: Dict[str, List[re.Pattern[str]]] = {
    "personal_identification": [
        re.compile(
            r"(?:đ[ặa]c\s+đi[ểe]m\s+nh[ậa]n\s+d[ạa]ng\s*(?:/\s*personal\s+identification)?|personal\s+identification)\s*[:.\-]?\s*\n?\s*"
            r"([^\n]{3,90}(?:\n[^\n]{2,90})?)",
            re.IGNORECASE,
        ),
    ],
    "issue_date": [
        re.compile(
            r"(?:ng[àa]y[,\s]+th[áa]ng[,\s]+n[ăa]m\s*(?:/\s*date[,\s]+month[,\s]+year)?|date[,\s]+month[,\s]+year)\s*[:.\-]?\s*\n?\s*"
            r"([0-9OoIl]{1,2}\s*[/\-.]\s*[0-9OoIl]{1,2}\s*[/\-.]\s*[0-9OoIl]{4})",
            re.IGNORECASE,
        ),
    ],
}

NEW_IDENTITY_FRONT_PATTERNS: Dict[str, List[re.Pattern[str]]] = {
    "personal_id_number": [
        re.compile(
            r"(?:s[ốo]\s+đ[ịi]nh\s+danh\s+c[áa]\s+nh[âa]n\s*(?:/\s*personal\s+identification\s+number)?|"
            r"personal\s+identification\s+number)\s*[:.\-]?\s*\n?\s*([0-9OoIl|SsBb]{12})\b",
            re.IGNORECASE,
        ),
        re.compile(r"\b(0[0-9]{11})\b"),
    ],
    "full_name": [
        re.compile(
            r"(?:h[ọo][,\s]+ch[ữư]\s+đ[ệe]m\s+v[àa]\s+t[êe]n\s+khai\s+sinh\s*(?:/\s*full\s+name)?|full\s+name)\s*[:.\-]?\s*\n?\s*"
            r"([A-ZÀÁÂÃÈÉÊÌÍÒÓÔÕÙÚĂĐĨŨƠƯẠ-Ỹ][A-ZÀÁÂÃÈÉÊÌÍÒÓÔÕÙÚĂĐĨŨƠƯẠ-Ỹ\s]{3,55})",
            re.IGNORECASE,
        ),
    ],
    "date_of_birth": [
        re.compile(
            r"(?:ng[àa]y[,\s]+th[áa]ng[,\s]+n[ăa]m\s+sinh\s*(?:/\s*date\s+of\s+birth)?|date\s+of\s+birth)\s*[:.\-]?\s*\n?\s*"
            r"([0-9OoIl]{1,2}\s*[/\-.]\s*[0-9OoIl]{1,2}\s*[/\-.]\s*[0-9OoIl]{4})",
            re.IGNORECASE,
        ),
    ],
    "gender": [
        re.compile(
            r"(?:gi[ớo]i\s+t[íi]nh\s*(?:/\s*sex)?|sex)\s*[:.\-]?\s*\n?\s*(nam|n[ữư]|male|female)\b",
            re.IGNORECASE,
        ),
    ],
    "nationality": [
        re.compile(
            r"(?:qu[ốo]c\s+t[ịi]ch\s*(?:/\s*nationality)?|nationality)\s*[:.\-]?\s*\n?\s*"
            r"(vi[ệe]t\s*nam|vietnam)\b",
            re.IGNORECASE,
        ),
    ],
}

NEW_IDENTITY_BACK_PATTERNS: Dict[str, List[re.Pattern[str]]] = {
    "place_of_residence": [
        re.compile(
            r"(?:n[ơo]i\s+c[ưư]\s+tr[úu]\s*(?:/\s*place\s+of\s+residence)?|place\s+of\s+residence)\s*[:.\-]?\s*\n?\s*"
            r"([^\n]{4,100}(?:\n[^\n]{3,100})?)",
            re.IGNORECASE,
        ),
    ],
    "place_of_birth_registration": [
        re.compile(
            r"(?:n[ơo]i\s+đ[ăa]ng\s+k[ýy]\s+khai\s+sinh\s*(?:/\s*place\s+of\s+birth(?:\s+registration)?)?|"
            r"place\s+of\s+birth(?:\s+registration)?)\s*[:.\-]?\s*\n?\s*"
            r"([^\n]{4,100}(?:\n[^\n]{3,100})?)",
            re.IGNORECASE,
        ),
    ],
    "issue_date": [
        re.compile(
            r"(?:ng[àa]y[,\s]+th[áa]ng[,\s]+n[ăa]m\s+c[ấa]p\s*(?:/\s*date\s+of\s+issue)?|date\s+of\s+issue)\s*[:.\-]?\s*\n?\s*"
            r"([0-9OoIl]{1,2}\s*[/\-.]\s*[0-9OoIl]{1,2}\s*[/\-.]\s*[0-9OoIl]{4})",
            re.IGNORECASE,
        ),
    ],
    "expiry_date": [
        re.compile(
            r"(?:ng[àa]y[,\s]+th[áa]ng[,\s]+n[ăa]m\s+h[ếe]t\s+h[ạa]n\s*(?:/\s*date\s+of\s+expiry)?|date\s+of\s+expiry)\s*[:.\-]?\s*\n?\s*"
            r"([0-9OoIl]{1,2}\s*[/\-.]\s*[0-9OoIl]{1,2}\s*[/\-.]\s*[0-9OoIl]{4})",
            re.IGNORECASE,
        ),
    ],
}


def extract_field_with_regex(
    text: str, patterns: List[Union[str, re.Pattern[str]]]
) -> Dict[str, Any]:
    """Extract a field value using ordered regex patterns and return value, span, and pattern index."""
    if not text:
        return {"matched": False, "value": None, "span": None, "pattern_index": None}

    for idx, pat in enumerate(patterns):
        compiled = re.compile(pat, re.IGNORECASE) if isinstance(pat, str) else pat
        m = compiled.search(text)
        if m:
            raw_val = m.group(1) if m.lastindex else m.group(0)
            cleaned = re.sub(r"\s+", " ", raw_val).strip(" :.-,;")
            if cleaned:
                return {
                    "matched": True,
                    "value": cleaned,
                    "span": m.span(1) if m.lastindex else m.span(0),
                    "pattern_index": idx,
                }
    return {"matched": False, "value": None, "span": None, "pattern_index": None}


# ==============================================================================
# 4. OPENCV IMAGE PREPROCESSING, PERSPECTIVE RECTIFICATION & QUALITY EVALUATION
# ==============================================================================


def calculate_blur_score(image: np.ndarray) -> float:
    """Compute Laplacian variance blur score on the central informative card region."""
    if image is None or image.size == 0:
        return 0.0
    gray = (
        cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
        if len(image.shape) == 3
        else image.copy()
    )
    h, w = gray.shape[:2]
    # Focus on inner 84% ROI to avoid textured background surfaces skewing sharpness
    margin_y, margin_x = int(h * 0.08), int(w * 0.08)
    roi = (
        gray[margin_y : h - margin_y, margin_x : w - margin_x]
        if (h > 60 and w > 60)
        else gray
    )
    # Normalize width to 1000px for scale-invariant Laplacian comparison
    if roi.shape[1] > 0 and roi.shape[1] != 1000:
        scale = 1000.0 / roi.shape[1]
        roi = cv2.resize(
            roi,
            (1000, max(1, int(roi.shape[0] * scale))),
            interpolation=cv2.INTER_AREA if scale < 1.0 else cv2.INTER_CUBIC,
        )
    return float(cv2.Laplacian(roi, cv2.CV_64F).var())


def check_image_sharpness(
    image: np.ndarray, config: Optional[QualityConfig] = None
) -> Dict[str, Any]:
    """Evaluate sharpness status (SHARP | ACCEPTABLE | BLURRY) with configurable thresholds."""
    cfg = config or QualityConfig()
    blur_score = calculate_blur_score(image)
    h, w = image.shape[:2]
    res_factor = min(1.15, max(0.75, w / float(cfg.reference_width_px)))
    adj_blurry = cfg.blur_threshold_blurry * res_factor
    adj_sharp = cfg.blur_threshold_sharp * res_factor

    if blur_score < adj_blurry:
        status = ImageQualityStatus.BLURRY
    elif blur_score < adj_sharp:
        status = ImageQualityStatus.ACCEPTABLE
    else:
        status = ImageQualityStatus.SHARP

    return {
        "status": status.value,
        "blur_score": round(blur_score, 2),
        "thresholds": {
            "blurry_below": round(adj_blurry, 2),
            "sharp_above": round(adj_sharp, 2),
        },
        "resolution": [int(w), int(h)],
    }


def check_image_quality(
    image: np.ndarray,
    ocr_confidence: Optional[float] = None,
    config: Optional[QualityConfig] = None,
) -> Dict[str, Any]:
    """Combine Laplacian variance, resolution, contrast, edge clarity, and OCR confidence."""
    cfg = config or QualityConfig()
    sharpness = check_image_sharpness(image, cfg)
    gray = (
        cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
        if len(image.shape) == 3
        else image.copy()
    )
    h, w = gray.shape[:2]
    contrast_std = float(np.std(gray))
    sobel_x = cv2.Sobel(gray, cv2.CV_64F, 1, 0, ksize=3)
    sobel_y = cv2.Sobel(gray, cv2.CV_64F, 0, 1, ksize=3)
    edge_energy = float(np.mean(np.hypot(sobel_x, sobel_y)))

    low_resolution = w < cfg.min_card_width_px or h < cfg.min_card_height_px
    low_contrast = contrast_std < cfg.min_contrast_std

    status = ImageQualityStatus(sharpness["status"])
    if low_resolution and status == ImageQualityStatus.SHARP:
        status = ImageQualityStatus.ACCEPTABLE
    if ocr_confidence is not None and ocr_confidence < cfg.min_readable_ocr_conf:
        if sharpness["blur_score"] < cfg.blur_threshold_sharp * 0.85:
            status = ImageQualityStatus.BLURRY

    return {
        "status": status.value,
        "blur_score": sharpness["blur_score"],
        "contrast_std": round(contrast_std, 2),
        "edge_energy": round(edge_energy, 2),
        "resolution": [int(w), int(h)],
        "low_resolution": low_resolution,
        "low_contrast": low_contrast,
        "ocr_confidence": round(ocr_confidence, 4) if ocr_confidence is not None else None,
    }


def _order_quad_points(pts: np.ndarray) -> np.ndarray:
    """Order 4 quadrilateral points as [top-left, top-right, bottom-right, bottom-left]."""
    rect = np.zeros((4, 2), dtype="float32")
    s = pts.sum(axis=1)
    rect[0] = pts[np.argmin(s)]
    rect[2] = pts[np.argmax(s)]
    diff = np.diff(pts, axis=1)
    rect[1] = pts[np.argmin(diff)]
    rect[3] = pts[np.argmax(diff)]
    return rect


def detect_and_warp_card(image: np.ndarray) -> Tuple[np.ndarray, bool, Optional[List[List[int]]]]:
    """Detect ID card quadrilateral contour (rounded corners, low-contrast edges) and rectify perspective."""
    h, w = image.shape[:2]
    scale = 900.0 / max(h, w) if max(h, w) > 900 else 1.0
    small = (
        cv2.resize(image, (int(w * scale), int(h * scale)), interpolation=cv2.INTER_AREA)
        if scale < 1.0
        else image.copy()
    )
    small_area = float(small.shape[0] * small.shape[1])
    gray = cv2.cvtColor(small, cv2.COLOR_BGR2GRAY)
    blurred = cv2.GaussianBlur(gray, (5, 5), 0)

    binary_masks: List[np.ndarray] = []
    for low, high in ((30, 110), (50, 150), (75, 200)):
        edges = cv2.Canny(blurred, low, high)
        kernel = cv2.getStructuringElement(cv2.MORPH_RECT, (9, 9))
        binary_masks.append(cv2.morphologyEx(edges, cv2.MORPH_CLOSE, kernel, iterations=2))

    # Additional Otsu brightness mask (effective for light cards on dark desk mats)
    _, otsu_mask = cv2.threshold(blurred, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)
    k_open = cv2.getStructuringElement(cv2.MORPH_RECT, (11, 11))
    binary_masks.append(cv2.morphologyEx(otsu_mask, cv2.MORPH_OPEN, k_open, iterations=1))

    candidates: List[Tuple[float, np.ndarray]] = []
    for mask in binary_masks:
        contours, _ = cv2.findContours(
            mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE
        )
        for cnt in sorted(contours, key=cv2.contourArea, reverse=True)[:8]:
            area = cv2.contourArea(cnt)
            if area < small_area * 0.16 or area > small_area * 0.95:
                continue
            rect = cv2.minAreaRect(cnt)
            (cx, cy), (rw, rh), _ = rect
            if rw < 10 or rh < 10:
                continue
            rect_area = float(rw * rh)
            rectangularity = area / rect_area
            aspect = max(rw, rh) / min(rw, rh)
            if rectangularity < 0.85 or not (1.35 <= aspect <= 1.85):
                continue

            peri = cv2.arcLength(cnt, True)
            approx = cv2.approxPolyDP(cnt, 0.025 * peri, True)
            if len(approx) == 4 and cv2.isContourConvex(approx):
                pts = (approx.reshape(4, 2) / scale).astype("float32")
            elif 4 <= len(approx) <= 10:
                box = cv2.boxPoints(rect)
                pts = (box / scale).astype("float32")
            else:
                continue

            ordered = _order_quad_points(pts)
            area_ratio = area / small_area
            # Slight center bias to avoid partial background shapes touching image borders
            center_dist = math.hypot(
                (cx / small.shape[1]) - 0.5, (cy / small.shape[0]) - 0.5
            )
            score = (
                rectangularity * 1.5
                + area_ratio
                - abs(aspect - 1.586) * 0.4
                - center_dist * 0.5
            )
            candidates.append((score, ordered))

    if not candidates:
        return image, False, None

    _, best_quad = max(candidates, key=lambda x: x[0])
    w_top = np.linalg.norm(best_quad[1] - best_quad[0])
    w_bot = np.linalg.norm(best_quad[2] - best_quad[3])
    h_left = np.linalg.norm(best_quad[3] - best_quad[0])
    h_right = np.linalg.norm(best_quad[2] - best_quad[1])
    dst_w = max(int(round(max(w_top, w_bot))), 1)
    dst_h = max(int(round(max(h_left, h_right))), 1)

    dst_pts = np.array(
        [[0, 0], [dst_w - 1, 0], [dst_w - 1, dst_h - 1], [0, dst_h - 1]],
        dtype="float32",
    )
    M = cv2.getPerspectiveTransform(best_quad, dst_pts)
    warped = cv2.warpPerspective(
        image, M, (dst_w, dst_h), flags=cv2.INTER_CUBIC, borderMode=cv2.BORDER_REPLICATE
    )
    quad_int = [[int(round(x)), int(round(y))] for x, y in best_quad]
    return warped, True, quad_int


def _rotate_image_90(image: np.ndarray, angle_cw: int) -> np.ndarray:
    angle = angle_cw % 360
    if angle == 90:
        return cv2.rotate(image, cv2.ROTATE_90_CLOCKWISE)
    if angle == 180:
        return cv2.rotate(image, cv2.ROTATE_180)
    if angle == 270:
        return cv2.rotate(image, cv2.ROTATE_90_COUNTERCLOCKWISE)
    return image


def _score_orientation_probe(image: np.ndarray) -> float:
    """Tesseract orientation score using ID card anchor keywords & word confidence."""
    h, w = image.shape[:2]
    probe_w = 1200
    scale = probe_w / float(w)
    resized = cv2.resize(
        image, (probe_w, max(1, int(h * scale))),
        interpolation=cv2.INTER_CUBIC if scale > 1.0 else cv2.INTER_AREA,
    )
    gray = cv2.cvtColor(resized, cv2.COLOR_BGR2GRAY)
    try:
        data = pytesseract.image_to_data(
            gray, lang="vie+eng", config="--oem 3 --psm 6", output_type=Output.DICT
        )
    except Exception:
        return -1.0

    words: List[str] = []
    confs: List[float] = []
    for txt, conf in zip(data.get("text", []), data.get("conf", [])):
        t = (txt or "").strip()
        try:
            c = float(conf)
        except (ValueError, TypeError):
            c = -1.0
        if t and c >= 0:
            words.append(t)
            confs.append(c)

    joined = normalize_vietnamese(" ".join(words))
    mean_conf = (sum(confs) / len(confs) / 100.0) if confs else 0.0
    anchor_hits = sum(
        1
        for kw in (
            "can cuoc",
            "cong dan",
            "viet nam",
            "so dinh danh",
            "ho va ten",
            "ngay sinh",
            "quoc tich",
            "que quan",
            "noi thuong tru",
            "noi cu tru",
            "dac diem",
            "idvnm",
            "bo cong an",
            "republic",
        )
        if kw in joined
    )
    return anchor_hits * 2.5 + mean_conf


def auto_orient_card(image: np.ndarray) -> Tuple[np.ndarray, int]:
    """Detect and correct 0 / 90 / 180 / 270 degree card rotation."""
    score_0 = _score_orientation_probe(image)
    if score_0 >= 3.0:
        return image, 0

    h, w = image.shape[:2]
    other_angles = [90, 270, 180] if h > w else [180, 90, 270]
    best_angle = 0
    best_score = score_0
    for angle in other_angles:
        rotated = _rotate_image_90(image, angle)
        score = _score_orientation_probe(rotated)
        if score > best_score:
            best_score = score
            best_angle = angle
    return _rotate_image_90(image, best_angle), best_angle


def build_ocr_image_variants(
    image: np.ndarray, target_width: int = 1280
) -> Dict[str, np.ndarray]:
    """Build multiple preprocessed image variants optimized for different OCR stages."""
    h, w = image.shape[:2]
    if w > 0 and w != target_width:
        scale = target_width / float(w)
        interp = cv2.INTER_CUBIC if scale > 1.0 else cv2.INTER_AREA
        base = cv2.resize(
            image, (target_width, max(1, int(h * scale))), interpolation=interp
        )
    else:
        base = image.copy()

    # 1. Gentle color denoising + subtle unsharp mask (preserves Vietnamese diacritics)
    denoised = cv2.fastNlMeansDenoisingColored(base, None, 4, 4, 7, 21)
    blur_gauss = cv2.GaussianBlur(denoised, (0, 0), 1.2)
    color_sharp = cv2.addWeighted(denoised, 1.35, blur_gauss, -0.35, 0)

    # 2. LAB L-channel CLAHE + gentle sharpening (for VietOCR line crops & low contrast)
    lab = cv2.cvtColor(denoised, cv2.COLOR_BGR2LAB)
    l_ch, a_ch, b_ch = cv2.split(lab)
    clahe = cv2.createCLAHE(clipLimit=2.0, tileGridSize=(8, 8))
    l_clahe = clahe.apply(l_ch)
    clahe_bgr = cv2.cvtColor(cv2.merge([l_clahe, a_ch, b_ch]), cv2.COLOR_LAB2BGR)
    clahe_gray = cv2.cvtColor(clahe_bgr, cv2.COLOR_BGR2GRAY)

    # 3. Otsu & Adaptive threshold versions (used specifically for high-contrast MRZ/digit checks)
    _, otsu_bin = cv2.threshold(
        clahe_gray, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU
    )
    adaptive_bin = cv2.adaptiveThreshold(
        clahe_gray,
        255,
        cv2.ADAPTIVE_THRESH_GAUSSIAN_C,
        cv2.THRESH_BINARY,
        31,
        11,
    )

    return {
        "color_sharp": color_sharp,
        "clahe_bgr": clahe_bgr,
        "clahe_gray": clahe_gray,
        "otsu_bin": otsu_bin,
        "adaptive_bin": adaptive_bin,
    }


# ==============================================================================
# 5. DUAL OCR ENGINES (TESSERACT + VIETOCR) & FUSION ENGINE
# ==============================================================================


@dataclass
class OCRLineItem:
    text: str
    bbox: List[int]  # [x1, y1, x2, y2] in processed_card coordinates
    confidence: float
    engine: str
    confidence_method: str


class TesseractEngine:
    """Wrapper around pytesseract with Vietnamese + English support and line-level bounding boxes."""

    def __init__(self, config: EngineConfig) -> None:
        self.config = config
        self.available = False
        self.init_error: Optional[str] = None
        self._configure()

    def _configure(self) -> None:
        if self.config.tesseract_cmd and os.path.exists(self.config.tesseract_cmd):
            pytesseract.pytesseract.tesseract_cmd = self.config.tesseract_cmd
        elif shutil.which("tesseract"):
            pytesseract.pytesseract.tesseract_cmd = shutil.which("tesseract")  # type: ignore

        try:
            _ = pytesseract.get_tesseract_version()
            self.available = True
        except Exception as exc:
            self.available = False
            self.init_error = f"Tesseract unavailable: {exc}"
            logger.warning(self.init_error)

    def recognize(
        self, variants: Dict[str, np.ndarray]
    ) -> Dict[str, Any]:
        """Run Tesseract OCR to extract structured line boxes, full text, and MRZ candidate crop."""
        if not self.available:
            return {
                "available": False,
                "error": self.init_error,
                "raw_text": "",
                "lines": [],
                "mrz_raw_text": "",
                "mean_confidence": 0.0,
            }

        img_for_ocr = variants["color_sharp"]
        try:
            data = pytesseract.image_to_data(
                img_for_ocr,
                lang=self.config.tesseract_lang,
                config="--oem 3 --psm 6",
                output_type=Output.DICT,
            )
        except pytesseract.TesseractError:
            data = pytesseract.image_to_data(
                img_for_ocr,
                lang="eng",
                config="--oem 3 --psm 6",
                output_type=Output.DICT,
            )

        grouped_lines: Dict[Tuple[int, int, int], List[Dict[str, Any]]] = {}
        n_boxes = len(data.get("text", []))
        for i in range(n_boxes):
            txt = (data["text"][i] or "").strip()
            try:
                conf = float(data["conf"][i])
            except (ValueError, TypeError):
                conf = -1.0
            if not txt or conf < 0:
                continue
            key = (
                int(data["block_num"][i]),
                int(data["par_num"][i]),
                int(data["line_num"][i]),
            )
            x, y, w, h = (
                int(data["left"][i]),
                int(data["top"][i]),
                int(data["width"][i]),
                int(data["height"][i]),
            )
            grouped_lines.setdefault(key, []).append(
                {
                    "text": txt,
                    "conf": max(0.0, min(1.0, conf / 100.0)),
                    "bbox": [x, y, x + w, y + h],
                }
            )

        line_items: List[OCRLineItem] = []
        for _, words in sorted(
            grouped_lines.items(),
            key=lambda kv: (
                min(w["bbox"][1] for w in kv[1]),
                min(w["bbox"][0] for w in kv[1]),
            ),
        ):
            words_sorted = sorted(words, key=lambda w: w["bbox"][0])
            line_text = " ".join(w["text"] for w in words_sorted)
            x1 = min(w["bbox"][0] for w in words_sorted)
            y1 = min(w["bbox"][1] for w in words_sorted)
            x2 = max(w["bbox"][2] for w in words_sorted)
            y2 = max(w["bbox"][3] for w in words_sorted)
            mean_c = sum(w["conf"] for w in words_sorted) / len(words_sorted)
            line_items.append(
                OCRLineItem(
                    text=line_text,
                    bbox=[x1, y1, x2, y2],
                    confidence=round(mean_c, 4),
                    engine="tesseract",
                    confidence_method="tesseract_word_mean_prob",
                )
            )

        # Dedicated high-contrast MRZ zone OCR on bottom 34% of card
        gray_otsu = variants["otsu_bin"]
        gh, gw = gray_otsu.shape[:2]
        mrz_roi = gray_otsu[int(gh * 0.64) : gh, 0:gw]
        mrz_raw = ""
        try:
            mrz_raw = pytesseract.image_to_string(
                mrz_roi,
                lang="eng",
                config=(
                    "--oem 3 --psm 6 "
                    "-c tessedit_char_whitelist=ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789<"
                ),
            )
        except Exception:
            mrz_raw = ""

        raw_text = "\n".join(li.text for li in line_items)
        mean_conf = (
            sum(li.confidence for li in line_items) / len(line_items)
            if line_items
            else 0.0
        )
        return {
            "available": True,
            "error": None,
            "raw_text": raw_text,
            "lines": line_items,
            "mrz_raw_text": normalize_ocr_text(mrz_raw),
            "mean_confidence": round(mean_conf, 4),
        }


class VietOCREngine:
    """Wrapper around VietOCR (CPU / CUDA auto-selection) for line-level Vietnamese recognition."""

    def __init__(self, config: EngineConfig) -> None:
        self.config = config
        self.available = False
        self.device = "cpu"
        self.init_error: Optional[str] = None
        self.predictor: Any = None
        if config.enable_vietocr:
            self._initialize()
        else:
            self.init_error = "VietOCR disabled via ENABLE_VIETOCR=0"

    def _initialize(self) -> None:
        if VietOCRCfg is None or VietOCRPredictor is None:
            self.init_error = (
                "vietocr package is not installed. Install with: pip install vietocr"
            )
            logger.warning(self.init_error)
            return

        if self.config.vietocr_device:
            self.device = self.config.vietocr_device
        elif torch is not None and torch.cuda.is_available():
            self.device = "cuda:0"
        else:
            self.device = "cpu"

        try:
            cfg = VietOCRCfg.load_config_from_name(self.config.vietocr_config_name)
            cfg["device"] = self.device
            cfg["cnn"]["pretrained"] = False
            cfg["predictor"]["beamsearch"] = False
            if self.config.vietocr_weights_path and os.path.exists(
                self.config.vietocr_weights_path
            ):
                cfg["weights"] = self.config.vietocr_weights_path
            self.predictor = VietOCRPredictor(cfg)
            self.available = True
            logger.info("VietOCR initialized successfully on device=%s", self.device)
        except Exception as exc:
            self.available = False
            self.predictor = None
            self.init_error = f"VietOCR initialization failed: {exc}"
            logger.warning(self.init_error)

    @staticmethod
    def _estimate_vietocr_line_confidence(
        text: str, raw_prob: Optional[float] = None
    ) -> Tuple[float, str]:
        """Compute VietOCR confidence independently without borrowing Tesseract scores."""
        if raw_prob is not None and 0.0 <= raw_prob <= 1.0:
            return round(float(raw_prob), 4), "vietocr_seq_decoder_prob"

        cleaned = text.strip()
        if not cleaned:
            return 0.0, "vietocr_structural_heuristic"
        valid_chars = sum(
            1
            for c in cleaned
            if c.isalnum() or c in " .,/:;-()<>Đđ" or unicodedata.category(c).startswith("L")
        )
        ratio = valid_chars / max(1, len(cleaned))
        length_bonus = min(0.12, len(cleaned) * 0.006)
        score = max(0.35, min(0.95, 0.72 * ratio + length_bonus + 0.12))
        return round(score, 4), "vietocr_structural_heuristic"

    def _detect_supplementary_line_boxes(
        self, gray_img: np.ndarray, existing_boxes: List[List[int]]
    ) -> List[List[int]]:
        """Detect horizontal text line regions via morphology to catch lines missed by Tesseract."""
        h, w = gray_img.shape[:2]
        grad = cv2.Sobel(gray_img, cv2.CV_8U, 1, 0, ksize=3)
        _, thresh = cv2.threshold(grad, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)
        kernel = cv2.getStructuringElement(cv2.MORPH_RECT, (max(18, w // 35), 3))
        dilated = cv2.dilate(thresh, kernel, iterations=1)
        contours, _ = cv2.findContours(
            dilated, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE
        )

        extra_boxes: List[List[int]] = []
        for cnt in contours:
            x, y, bw, bh = cv2.boundingRect(cnt)
            if bw < w * 0.06 or bh < 10 or bh > h * 0.18:
                continue
            # Skip left portrait zone (x < 0.28*w and tall aspect)
            if x < w * 0.25 and bh > h * 0.25:
                continue
            cand = [max(0, x - 4), max(0, y - 3), min(w, x + bw + 4), min(h, y + bh + 3)]
            overlaps = False
            for eb in existing_boxes:
                ix1, iy1 = max(cand[0], eb[0]), max(cand[1], eb[1])
                ix2, iy2 = min(cand[2], eb[2]), min(cand[3], eb[3])
                if ix2 > ix1 and iy2 > iy1:
                    inter = (ix2 - ix1) * (iy2 - iy1)
                    area_c = (cand[2] - cand[0]) * (cand[3] - cand[1])
                    if inter / max(1, area_c) > 0.35:
                        overlaps = True
                        break
            if not overlaps:
                extra_boxes.append(cand)
        return extra_boxes

    def _cluster_words_into_segments(words: List[Dict[str, Any]]) -> List[List[Dict[str, Any]]]:
        """Group OCR words into tight single-line local segments (splitting across wide horizontal gaps)."""
        if not words:
            return []
        sorted_w = sorted(words, key=lambda w: ((w["bbox"][1] + w["bbox"][3]) / 2.0, w["bbox"][0]))
        rows: List[List[Dict[str, Any]]] = []
        for w in sorted_w:
            wy_mid = (w["bbox"][1] + w["bbox"][3]) / 2.0
            wh = max(1, w["bbox"][3] - w["bbox"][1])
            placed = False
            for row in rows:
                ry_mid = sum((x["bbox"][1] + x["bbox"][3]) / 2.0 for x in row) / len(row)
                rh = sum(max(1, x["bbox"][3] - x["bbox"][1]) for x in row) / len(row)
                if abs(wy_mid - ry_mid) <= 0.55 * max(wh, rh):
                    row.append(w)
                    placed = True
                    break
            if not placed:
                rows.append([w])

        segments: List[List[Dict[str, Any]]] = []
        for row in rows:
            row.sort(key=lambda w: w["bbox"][0])
            cur_seg = [row[0]]
            for nxt in row[1:]:
                prev = cur_seg[-1]
                gap = nxt["bbox"][0] - prev["bbox"][2]
                line_h = max(
                    prev["bbox"][3] - prev["bbox"][1],
                    nxt["bbox"][3] - nxt["bbox"][1],
                    12,
                )
                # Split into separate segments if horizontal gap exceeds 2.1x character height
                if gap > line_h * 2.1:
                    segments.append(cur_seg)
                    cur_seg = [nxt]
                else:
                    cur_seg.append(nxt)
            segments.append(cur_seg)
        return segments

    def recognize(
        self,
        variants: Dict[str, np.ndarray],
        candidate_boxes: List[List[int]],
    ) -> Dict[str, Any]:
        """Run VietOCR on each detected text-line crop."""
        if not self.available or self.predictor is None:
            return {
                "available": False,
                "device": self.device,
                "error": self.init_error,
                "raw_text": "",
                "lines": [],
                "mean_confidence": 0.0,
            }

        contrast_std = float(np.std(variants["clahe_gray"]))
        bgr = variants["clahe_bgr"] if contrast_std < 38.0 else variants["color_sharp"]
        h, w = bgr.shape[:2]
        all_boxes = list(candidate_boxes) + self._detect_supplementary_line_boxes(
            variants["clahe_gray"], candidate_boxes
        )
        all_boxes.sort(key=lambda b: (b[1], b[0]))

        line_items: List[OCRLineItem] = []
        for box in all_boxes:
            x1 = max(0, box[0] - 4)
            y1 = max(0, box[1] - 3)
            x2 = min(w, box[2] + 4)
            y2 = min(h, box[3] + 3)
            if x2 - x1 < 8 or y2 - y1 < 6:
                continue
            crop_bgr = bgr[y1:y2, x1:x2]
            crop_rgb = cv2.cvtColor(crop_bgr, cv2.COLOR_BGR2RGB)
            pil_crop = Image.fromarray(crop_rgb)
            try:
                pred_out = self.predictor.predict(pil_crop, return_prob=True)
                if isinstance(pred_out, tuple) and len(pred_out) == 2:
                    text_val, raw_prob = str(pred_out[0]), float(pred_out[1])
                else:
                    text_val, raw_prob = str(pred_out), None
            except TypeError:
                text_val = str(self.predictor.predict(pil_crop))
                raw_prob = None
            except Exception:
                continue

            text_val = text_val.strip()
            if not text_val:
                continue
            conf, method = self._estimate_vietocr_line_confidence(text_val, raw_prob)
            line_items.append(
                OCRLineItem(
                    text=text_val,
                    bbox=[x1, y1, x2, y2],
                    confidence=conf,
                    engine="vietocr",
                    confidence_method=method,
                )
            )

        raw_text = "\n".join(li.text for li in line_items)
        mean_conf = (
            sum(li.confidence for li in line_items) / len(line_items)
            if line_items
            else 0.0
        )
        return {
            "available": True,
            "device": self.device,
            "error": None,
            "raw_text": raw_text,
            "lines": line_items,
            "mean_confidence": round(mean_conf, 4),
        }


def _bbox_iou(box_a: List[int], box_b: List[int]) -> float:
    ix1, iy1 = max(box_a[0], box_b[0]), max(box_a[1], box_b[1])
    ix2, iy2 = min(box_a[2], box_b[2]), min(box_a[3], box_b[3])
    if ix2 <= ix1 or iy2 <= iy1:
        return 0.0
    inter = float((ix2 - ix1) * (iy2 - iy1))
    area_a = float(max(1, (box_a[2] - box_a[0]) * (box_a[3] - box_a[1])))
    area_b = float(max(1, (box_b[2] - box_b[0]) * (box_b[3] - box_b[1])))
    return inter / (area_a + area_b - inter)


def _count_vietnamese_diacritics(text: str) -> int:
    nfd = unicodedata.normalize("NFD", text)
    return sum(1 for ch in nfd if unicodedata.category(ch) == "Mn") + sum(
        1 for ch in text if ch in "đĐ"
    )


def merge_ocr_results(
    tesseract_result: Dict[str, Any], vietocr_result: Dict[str, Any]
) -> Dict[str, Any]:
    """Compare and merge Tesseract and VietOCR outputs per text-line region.

    Rules:
      - Never copies Tesseract confidence onto VietOCR.
      - Prefers VietOCR for Vietnamese text with diacritics when base characters align.
      - Prefers Tesseract for strict MRZ / pure ASCII digit tokens when structurally cleaner.
      - Records disagreements explicitly when engines diverge without strong evidence.
    """
    tess_lines: List[OCRLineItem] = tesseract_result.get("lines", [])
    viet_lines: List[OCRLineItem] = vietocr_result.get("lines", [])

    if not vietocr_result.get("available") or not viet_lines:
        merged_lines = [
            {
                "text": li.text,
                "bbox": li.bbox,
                "confidence": li.confidence,
                "selected_engine": "tesseract",
                "tesseract_text": li.text,
                "tesseract_confidence": li.confidence,
                "vietocr_text": None,
                "vietocr_confidence": None,
                "agreement": None,
            }
            for li in tess_lines
        ]
        return {
            "lines": merged_lines,
            "merged_text": "\n".join(x["text"] for x in merged_lines),
            "tesseract_text": tesseract_result.get("raw_text", ""),
            "vietocr_text": "",
            "mrz_raw_text": tesseract_result.get("mrz_raw_text", ""),
            "engines_used": ["tesseract"] if tesseract_result.get("available") else [],
            "disagreements": [],
        }

    used_viet = set()
    merged_lines: List[Dict[str, Any]] = []
    disagreements: List[Dict[str, Any]] = []

    for t_li in tess_lines:
        best_idx = -1
        best_iou = 0.0
        for idx, v_li in enumerate(viet_lines):
            if idx in used_viet:
                continue
            iou = _bbox_iou(t_li.bbox, v_li.bbox)
            if iou > best_iou:
                best_iou = iou
                best_idx = idx

        if best_idx >= 0 and best_iou >= 0.35:
            used_viet.add(best_idx)
            v_li = viet_lines[best_idx]
            norm_t = normalize_vietnamese(t_li.text)
            norm_v = normalize_vietnamese(v_li.text)
            sim = difflib.SequenceMatcher(None, norm_t, norm_v).ratio()

            is_mrz_like = "<<" in t_li.text or t_li.text.startswith("IDVNM")
            is_numeric_or_date = bool(
                re.fullmatch(r"[\d/\-.: ]+", t_li.text.strip())
            )

            if is_mrz_like:
                chosen_text = t_li.text
                chosen_engine = "tesseract"
                chosen_conf = t_li.confidence
            elif is_numeric_or_date:
                # Prefer exact 12-digit or DD/MM/YYYY match
                t_valid_num = bool(
                    re.search(r"\b\d{12}\b|\b\d{2}/\d{2}/\d{4}\b", t_li.text)
                )
                v_valid_num = bool(
                    re.search(r"\b\d{12}\b|\b\d{2}/\d{2}/\d{4}\b", v_li.text)
                )
                if t_valid_num and not v_valid_num:
                    chosen_text, chosen_engine, chosen_conf = (
                        t_li.text,
                        "tesseract",
                        t_li.confidence,
                    )
                elif v_valid_num and not t_valid_num:
                    chosen_text, chosen_engine, chosen_conf = (
                        v_li.text,
                        "vietocr",
                        v_li.confidence,
                    )
                elif sim >= 0.85:
                    chosen_text = t_li.text if t_li.confidence >= v_li.confidence else v_li.text
                    chosen_engine = "dual_consensus"
                    chosen_conf = min(0.99, round((t_li.confidence + v_li.confidence) / 2.0 + 0.05, 4))
                else:
                    chosen_text = t_li.text if t_li.confidence >= v_li.confidence else v_li.text
                    chosen_engine = "tesseract" if t_li.confidence >= v_li.confidence else "vietocr"
                    chosen_conf = round(max(t_li.confidence, v_li.confidence) * 0.85, 4)
                    disagreements.append(
                        {"bbox": t_li.bbox, "similarity": round(sim, 3)}
                    )
            else:
                # Vietnamese text line: favor VietOCR when base characters align or diacritics are richer
                t_diac = _count_vietnamese_diacritics(t_li.text)
                v_diac = _count_vietnamese_diacritics(v_li.text)
                if sim >= 0.72:
                    if v_diac >= t_diac and len(v_li.text.strip()) >= 2:
                        chosen_text = v_li.text
                        chosen_engine = "vietocr+tesseract_verified"
                        chosen_conf = min(
                            0.98, round(0.55 * v_li.confidence + 0.45 * t_li.confidence + 0.04, 4)
                        )
                    else:
                        chosen_text = t_li.text
                        chosen_engine = "tesseract+vietocr_verified"
                        chosen_conf = min(
                            0.98, round(0.55 * t_li.confidence + 0.45 * v_li.confidence + 0.04, 4)
                        )
                else:
                    # Significant disagreement between engines
                    if v_li.confidence > t_li.confidence + 0.12:
                        chosen_text, chosen_engine = v_li.text, "vietocr"
                        chosen_conf = round(v_li.confidence * 0.82, 4)
                    else:
                        chosen_text, chosen_engine = t_li.text, "tesseract"
                        chosen_conf = round(t_li.confidence * 0.82, 4)
                    disagreements.append(
                        {"bbox": t_li.bbox, "similarity": round(sim, 3)}
                    )

            merged_lines.append(
                {
                    "text": chosen_text,
                    "bbox": t_li.bbox,
                    "confidence": chosen_conf,
                    "selected_engine": chosen_engine,
                    "tesseract_text": t_li.text,
                    "tesseract_confidence": t_li.confidence,
                    "vietocr_text": v_li.text,
                    "vietocr_confidence": v_li.confidence,
                    "agreement": round(sim, 4),
                }
            )
        else:
            merged_lines.append(
                {
                    "text": t_li.text,
                    "bbox": t_li.bbox,
                    "confidence": t_li.confidence,
                    "selected_engine": "tesseract",
                    "tesseract_text": t_li.text,
                    "tesseract_confidence": t_li.confidence,
                    "vietocr_text": None,
                    "vietocr_confidence": None,
                    "agreement": None,
                }
            )

    # Include any extra text lines detected solely by VietOCR
    for idx, v_li in enumerate(viet_lines):
        if idx not in used_viet:
            merged_lines.append(
                {
                    "text": v_li.text,
                    "bbox": v_li.bbox,
                    "confidence": v_li.confidence,
                    "selected_engine": "vietocr",
                    "tesseract_text": None,
                    "tesseract_confidence": None,
                    "vietocr_text": v_li.text,
                    "vietocr_confidence": v_li.confidence,
                    "agreement": None,
                }
            )

    merged_lines.sort(key=lambda item: (item["bbox"][1], item["bbox"][0]))
    engines_used = []
    if tesseract_result.get("available"):
        engines_used.append("tesseract")
    if vietocr_result.get("available"):
        engines_used.append("vietocr")

    return {
        "lines": merged_lines,
        "merged_text": "\n".join(x["text"] for x in merged_lines),
        "tesseract_text": tesseract_result.get("raw_text", ""),
        "vietocr_text": vietocr_result.get("raw_text", ""),
        "mrz_raw_text": tesseract_result.get("mrz_raw_text", ""),
        "engines_used": engines_used,
        "disagreements": disagreements,
    }


# ==============================================================================
# 6. TD1 ICAO 9303 MRZ PARSER & CHECK-DIGIT VALIDATOR
# ==============================================================================

_MRZ_WEIGHTS = (7, 3, 1)


def _mrz_char_value(ch: str) -> int:
    if "0" <= ch <= "9":
        return ord(ch) - ord("0")
    if "A" <= ch <= "Z":
        return ord(ch) - ord("A") + 10
    if ch == "<":
        return 0
    return 0


def compute_mrz_check_digit(field_str: str) -> str:
    """Compute ICAO 9303 Mod-10 check digit using repeating weights 7, 3, 1."""
    total = sum(
        _mrz_char_value(ch) * _MRZ_WEIGHTS[i % 3] for i, ch in enumerate(field_str)
    )
    return str(total % 10)


def _repair_mrz_line_context(line: str, line_idx: int) -> str:
    """Apply strict slot-aware character normalization on TD1 MRZ lines without inventing characters."""
    cleaned = re.sub(r"[^A-Z0-9<]", "", line.upper())
    if len(cleaned) < 24:
        return cleaned
    chars = list(cleaned[:30])
    if line_idx == 0:
        # Line 1: IDVNM (0..4 alpha), 5..14 (9-char doc no + 1 check), 15..26 (12-digit ID), 29 (check)
        for pos in range(min(5, len(chars))):
            if chars[pos] == "0":
                chars[pos] = "O"
            elif chars[pos] == "1":
                chars[pos] = "I"
        for pos in list(range(5, 15)) + list(range(15, 27)) + [29]:
            if pos < len(chars) and chars[pos] != "<":
                chars[pos] = _repair_numeric_ocr_tokens(chars[pos])
    elif line_idx == 1:
        # Line 2: 0..6 (YYMMDD + check), 7 (M/F/<), 8..14 (YYMMDD + check), 15..17 (VNM), 29 (composite check)
        for pos in list(range(0, 7)) + list(range(8, 15)) + [29]:
            if pos < len(chars) and chars[pos] != "<":
                chars[pos] = _repair_numeric_ocr_tokens(chars[pos])
        for pos in range(15, min(18, len(chars))):
            if chars[pos] == "0":
                chars[pos] = "O"
    elif line_idx == 2:
        # Line 3: Surname<<GivenNames<<< (letters and '<' only)
        for pos in range(len(chars)):
            if chars[pos] == "0":
                chars[pos] = "O"
            elif chars[pos] == "1":
                chars[pos] = "I"
            elif chars[pos] == "5":
                chars[pos] = "S"
            elif chars[pos] == "8":
                chars[pos] = "B"
    return "".join(chars)


def parse_and_validate_mrz(
    merged_text: str, dedicated_mrz_text: str = ""
) -> Dict[str, Any]:
    """Detect, normalize, validate structure & ICAO 9303 check digits for Vietnam TD1 3-line MRZ."""
    # Prioritize clean merged_text lines first, then dedicated MRZ crop as fallback
    combined_source = f"{merged_text}\n{dedicated_mrz_text}".strip()
    candidate_lines: List[str] = []
    for raw_ln in combined_source.splitlines():
        compact = re.sub(r"\s+", "", raw_ln.upper())
        # Strip stray 1-2 leading noise characters before known TD1 anchors
        m_l1 = re.search(r"((?:IDVNM|I<VNM|1DVNM)[A-Z0-9<]{20,26})", compact)
        if m_l1:
            compact = m_l1.group(1)
        else:
            m_l2 = re.search(r"(\d{6}[0-9O][MF<]\d{6}[0-9O]VNM[A-Z0-9<]{8,13})", compact)
            if m_l2:
                compact = m_l2.group(1)
            else:
                m_l3 = re.search(r"([A-Z]{2,}<<[A-Z0-9<]{10,26})", compact)
                if m_l3:
                    compact = m_l3.group(1)

        if len(compact) >= 24 and (
            "<<" in compact
            or compact.startswith(("IDVNM", "I<VNM", "1DVNM"))
            or re.match(r"^\d{6}[0-9O][MF<]\d{6}[0-9O]VNM", compact)
        ):
            candidate_lines.append(compact)

    if not candidate_lines:
        return {
            "mrz": {
                "raw": None,
                "normalized": None,
                "lines": [],
                "valid_format": None,
                "checksum_valid": None,
                "confidence": 0.0,
            },
            "cross_check": {},
        }

    line1: Optional[str] = None
    line2: Optional[str] = None
    line3: Optional[str] = None

    # Prefer exact 30-char candidates first
    ordered_candidates = sorted(
        candidate_lines, key=lambda s: (0 if len(s) == 30 else 1)
    )
    for ln in ordered_candidates:
        if line1 is None and ln.startswith(("IDVNM", "I<VNM", "1DVNM")):
            line1 = ln
        elif line2 is None and re.match(r"^\d{6}[0-9O][MF<]\d{6}[0-9O]VNM", ln):
            line2 = ln
        elif (
            line3 is None
            and "<<" in ln
            and not ln.startswith(("IDVNM", "I<VNM", "1DVNM"))
            and not re.search(r"\d{6}", ln)
        ):
            line3 = ln

    raw_lines = [x for x in (line1, line2, line3) if x is not None]
    if len(raw_lines) < 2:
        return {
            "mrz": {
                "raw": "\n".join(raw_lines) if raw_lines else None,
                "normalized": None,
                "lines": raw_lines,
                "valid_format": False,
                "checksum_valid": False,
                "confidence": 0.25,
            },
            "cross_check": {},
        }

    norm_lines: List[str] = []
    for idx, r_ln in enumerate((line1, line2, line3)):
        if r_ln is not None:
            norm_lines.append(_repair_mrz_line_context(r_ln, idx))

    valid_format = (
        len(norm_lines) == 3
        and all(len(ln) == 30 for ln in norm_lines)
        and norm_lines[0].startswith("IDVNM")
        and bool(re.match(r"^\d{7}[MF<]\d{7}VNM<{11}\d$", norm_lines[1]))
        and bool(re.match(r"^[A-Z<]{30}$", norm_lines[2]))
    )

    checksum_checks: List[bool] = []
    cross_check: Dict[str, Any] = {}

    if len(norm_lines) >= 1 and len(norm_lines[0]) == 30:
        l1 = norm_lines[0]
        doc9, doc9_chk = l1[5:14], l1[14]
        if doc9.isdigit() and doc9_chk.isdigit():
            checksum_checks.append(compute_mrz_check_digit(doc9) == doc9_chk)
        full_id_cand, full_id_chk = l1[15:27], l1[29]
        if full_id_cand.isdigit() and len(full_id_cand) == 12:
            cross_check["id_number"] = full_id_cand
            if full_id_chk.isdigit():
                checksum_checks.append(compute_mrz_check_digit(full_id_cand) == full_id_chk)

    if len(norm_lines) >= 2 and len(norm_lines[1]) == 30:
        l2 = norm_lines[1]
        dob6, dob_chk = l2[0:6], l2[6]
        sex_ch = l2[7]
        exp6, exp_chk = l2[8:14], l2[14]
        comp_chk = l2[29]
        if dob6.isdigit() and dob_chk.isdigit():
            checksum_checks.append(compute_mrz_check_digit(dob6) == dob_chk)
            cross_check["dob_yymmdd"] = dob6
        if exp6.isdigit() and exp_chk.isdigit():
            checksum_checks.append(compute_mrz_check_digit(exp6) == exp_chk)
            cross_check["expiry_yymmdd"] = exp6
        if sex_ch in ("M", "F"):
            cross_check["gender"] = "Nam" if sex_ch == "M" else "Nữ"
        if len(norm_lines) == 3 and len(norm_lines[0]) == 30 and comp_chk.isdigit():
            composite_str = (
                norm_lines[0][5:30] + l2[0:7] + l2[8:15] + l2[18:29]
            )
            checksum_checks.append(compute_mrz_check_digit(composite_str) == comp_chk)

    if len(norm_lines) == 3 and len(norm_lines[2]) == 30:
        l3 = norm_lines[2]
        parts = [p for p in l3.split("<<") if p]
        if parts:
            surname = parts[0].replace("<", " ").strip()
            given = " ".join(p.replace("<", " ").strip() for p in parts[1:]).strip()
            full_mrz_name = f"{surname} {given}".strip()
            if full_mrz_name:
                cross_check["full_name_ascii"] = re.sub(r"\s+", " ", full_mrz_name)

    checksum_valid = bool(checksum_checks) and all(checksum_checks)
    if valid_format and checksum_valid:
        mrz_conf = 0.98
    elif valid_format:
        mrz_conf = 0.72
    elif len(norm_lines) == 3:
        mrz_conf = 0.50
    else:
        mrz_conf = 0.30

    return {
        "mrz": {
            "raw": "\n".join(raw_lines),
            "normalized": "\n".join(norm_lines),
            "lines": norm_lines,
            "valid_format": valid_format,
            "checksum_valid": checksum_valid,
            "confidence": mrz_conf,
        },
        "cross_check": cross_check,
    }


# ==============================================================================
# 7. CARD TYPE & SIDE SCORING CLASSIFIER (OLD CCCD VS NEW CĂN CƯỚC, FRONT VS BACK)
# ==============================================================================


def classify_id_card_side(
    ocr_text: str,
    mrz_info: Optional[Dict[str, Any]] = None,
    lines: Optional[List[Dict[str, Any]]] = None,
) -> Dict[str, Any]:
    """Classify document_type (CITIZEN_ID_CARD | IDENTITY_CARD | UNKNOWN) and side (front | back | unknown)
    using multi-signal weighted scoring (keywords, fuzzy matching, field structure, and MRZ detection).
    """
    norm = normalize_vietnamese(ocr_text)
    has_mrz = bool(
        mrz_info
        and (
            mrz_info.get("valid_format")
            or len(mrz_info.get("lines", [])) >= 2
            or "idvnm" in norm
        )
    )

    scores = {
        (DocumentType.CITIZEN_ID_CARD, CardSide.FRONT): 0.0,
        (DocumentType.CITIZEN_ID_CARD, CardSide.BACK): 0.0,
        (DocumentType.IDENTITY_CARD, CardSide.FRONT): 0.0,
        (DocumentType.IDENTITY_CARD, CardSide.BACK): 0.0,
    }

    # --- 1. Old CCCD Front Signals ---
    if match_keyword(norm, ["can cuoc cong dan", "citizen identity card"]):
        scores[(DocumentType.CITIZEN_ID_CARD, CardSide.FRONT)] += 4.5
    elif fuzzy_match_keyword(ocr_text, ["can cuoc cong dan"], 0.80)["matched"]:
        scores[(DocumentType.CITIZEN_ID_CARD, CardSide.FRONT)] += 3.5

    for kw, w in (
        ("que quan", 3.0),
        ("place of origin", 3.0),
        ("noi thuong tru", 3.0),
        ("co gia tri den", 1.5),
        ("ho va ten", 1.5),
        ("so / no", 1.5),
        ("ngay sinh", 1.2),
        ("quoc tich", 1.0),
        ("gioi tinh", 1.0),
    ):
        if kw in norm:
            scores[(DocumentType.CITIZEN_ID_CARD, CardSide.FRONT)] += w

    # --- 2. Old CCCD Back Signals ---
    if match_keyword(norm, ["dac diem nhan dang", "personal identification"]):
        scores[(DocumentType.CITIZEN_ID_CARD, CardSide.BACK)] += 4.5
    elif fuzzy_match_keyword(ocr_text, ["dac diem nhan dang"], 0.78)["matched"]:
        scores[(DocumentType.CITIZEN_ID_CARD, CardSide.BACK)] += 3.5

    for kw, w in (
        ("ngon tro trai", 2.5),
        ("ngon tro phai", 2.5),
        ("left index finger", 2.0),
        ("right index finger", 2.0),
        ("cuc truong cuc canh sat", 2.5),
        ("date month year", 1.5),
    ):
        if kw in norm:
            scores[(DocumentType.CITIZEN_ID_CARD, CardSide.BACK)] += w

    if has_mrz and "noi dang ky khai sinh" not in norm and "bo cong an" not in norm:
        scores[(DocumentType.CITIZEN_ID_CARD, CardSide.BACK)] += 2.5

    # --- 3. New Identity Card (Căn cước) Front Signals ---
    if (
        match_keyword(norm, ["can cuoc", "identity card"])
        and "cong dan" not in norm
        and "citizen identity" not in norm
    ):
        scores[(DocumentType.IDENTITY_CARD, CardSide.FRONT)] += 4.0

    for kw, w in (
        ("so dinh danh ca nhan", 4.0),
        ("personal identification number", 3.5),
        ("ho chu dem va ten khai sinh", 3.5),
        ("ngay thang nam sinh", 2.0),
        ("quoc tich", 1.0),
        ("gioi tinh", 1.0),
    ):
        if kw in norm:
            scores[(DocumentType.IDENTITY_CARD, CardSide.FRONT)] += w
    if fuzzy_match_keyword(ocr_text, ["so dinh danh ca nhan"], 0.78)["matched"]:
        scores[(DocumentType.IDENTITY_CARD, CardSide.FRONT)] += 2.5

    # --- 4. New Identity Card (Căn cước) Back Signals ---
    for kw, w in (
        ("noi dang ky khai sinh", 4.5),
        ("place of birth", 2.5),
        ("noi cu tru", 3.5),
        ("ngay thang nam cap", 2.5),
        ("date of issue", 2.0),
        ("ngay thang nam het han", 2.5),
        ("date of expiry", 2.0),
        ("bo cong an", 3.0),
        ("ministry of public security", 2.5),
    ):
        if kw in norm:
            scores[(DocumentType.IDENTITY_CARD, CardSide.BACK)] += w
    if fuzzy_match_keyword(ocr_text, ["noi dang ky khai sinh", "noi cu tru"], 0.78)["matched"]:
        scores[(DocumentType.IDENTITY_CARD, CardSide.BACK)] += 2.0

    if has_mrz and (
        "noi dang ky khai sinh" in norm
        or "noi cu tru" in norm
        or "bo cong an" in norm
        or "ministry of public" in norm
    ):
        scores[(DocumentType.IDENTITY_CARD, CardSide.BACK)] += 3.0

    # --- Cross-penalties to avoid Old vs New confusion ---
    if "que quan" in norm or "noi thuong tru" in norm:
        scores[(DocumentType.IDENTITY_CARD, CardSide.FRONT)] -= 3.0
    if "so dinh danh ca nhan" in norm or "khai sinh" in norm:
        scores[(DocumentType.CITIZEN_ID_CARD, CardSide.FRONT)] -= 3.0
    if "dac diem nhan dang" in norm:
        scores[(DocumentType.IDENTITY_CARD, CardSide.BACK)] -= 3.5
    if "noi dang ky khai sinh" in norm or "bo cong an" in norm:
        scores[(DocumentType.CITIZEN_ID_CARD, CardSide.BACK)] -= 3.5

    ranked = sorted(scores.items(), key=lambda item: item[1], reverse=True)
    (best_doc, best_side), top_score = ranked[0]
    (_, _), second_score = ranked[1]

    if top_score < 2.5:
        return {
            "document_type": DocumentType.UNKNOWN.value,
            "document_name": None,
            "side": CardSide.UNKNOWN.value,
            "confidence": 0.0,
            "scores": {f"{k[0].value}:{k[1].value}": round(v, 2) for k, v in scores.items()},
        }

    margin = max(0.0, top_score - second_score)
    conf = min(0.99, round((top_score / (top_score + max(0.5, second_score))) * min(1.0, (top_score + margin) / 7.5), 4))
    return {
        "document_type": best_doc.value,
        "document_name": DOCUMENT_NAMES[best_doc],
        "side": best_side.value,
        "confidence": conf,
        "scores": {f"{k[0].value}:{k[1].value}": round(v, 2) for k, v in scores.items()},
    }


# ==============================================================================
# 8. SPATIAL + REGEX FIELD EXTRACTION & ANTI-HALLUCINATION VALIDATION
# ==============================================================================


def _validate_and_normalize_date(raw_date: Optional[str]) -> Optional[str]:
    """Validate calendar date and normalize strictly to DD/MM/YYYY without guessing."""
    if not raw_date:
        return None
    repaired = _repair_numeric_ocr_tokens(raw_date.strip())
    m = re.search(r"\b(\d{1,2})\s*[/\-.]\s*(\d{1,2})\s*[/\-.]\s*(\d{4})\b", repaired)
    if not m:
        return None
    day, month, year = int(m.group(1)), int(m.group(2)), int(m.group(3))
    if not (1900 <= year <= 2100 and 1 <= month <= 12):
        return None
    max_day = calendar.monthrange(year, month)[1]
    if not (1 <= day <= max_day):
        return None
    return f"{day:02d}/{month:02d}/{year:04d}"


def _validate_id_number(raw_id: Optional[str]) -> Optional[str]:
    """Validate 12-digit Vietnamese CCCD / Personal Identification Number."""
    if not raw_id:
        return None
    repaired = _repair_numeric_ocr_tokens(re.sub(r"\s+", "", raw_id))
    m = re.search(r"\b(\d{12})\b", repaired)
    if not m:
        return None
    digits = m.group(1)
    # First 3 digits: province code 001..096 or overseas birth code
    prov = int(digits[:3])
    if prov == 0:
        return None
    return digits


def _validate_gender(raw_gender: Optional[str]) -> Optional[str]:
    if not raw_gender:
        return None
    norm = normalize_vietnamese(raw_gender)
    if re.search(r"\b(nam|male)\b", norm):
        return "Nam"
    if re.search(r"\b(nu|female)\b", norm):
        return "Nữ"
    return None


def _validate_nationality(raw_nat: Optional[str]) -> Optional[str]:
    if not raw_nat:
        return None
    norm = normalize_vietnamese(raw_nat)
    if "viet nam" in norm or "vietnam" in norm:
        return "Việt Nam"
    return None


def _clean_text_value(val: Optional[str]) -> Optional[str]:
    """Trim trailing field labels or stray punctuation from extracted Vietnamese text values."""
    if not val:
        return None
    norm = normalize_vietnamese(val)
    m_bound = re.search(_NEXT_LABEL_BOUNDARY, norm)
    if m_bound and m_bound.start() > 0:
        # Map character offset conservatively back to original string
        cut_ratio = m_bound.start() / max(1, len(norm))
        approx_idx = max(1, int(round(len(val) * cut_ratio)))
        val = val[:approx_idx]
    cleaned = re.sub(r"\s+", " ", val).strip(" :.-,;|/")
    return cleaned if len(cleaned) >= 2 else None


def _validate_full_name(raw_name: Optional[str]) -> Optional[str]:
    cleaned = _clean_text_value(raw_name)
    if not cleaned:
        return None
    # Strip any accidental bilingual label fragments
    cleaned = re.sub(
        r"^(?:h[ọo]\s+v[àa]\s+t[êe]n|full\s+name|khai\s+sinh)\s*[:.\-]?\s*",
        "",
        cleaned,
        flags=re.IGNORECASE,
    ).strip()
    # Must contain only alphabetic letters and spaces, at least 2 words
    if re.search(r"\d", cleaned):
        return None
    words = [w for w in cleaned.split() if len(w) >= 1]
    if len(words) < 2:
        return None
    norm = normalize_vietnamese(cleaned)
    if any(
        stop in norm
        for stop in ("ngay sinh", "gioi tinh", "quoc tich", "can cuoc", "cong hoa")
    ):
        return None
    return cleaned.upper()


def _find_line_bbox_for_value(
    lines: List[Dict[str, Any]], value: str, label_keywords: List[str]
) -> Optional[List[int]]:
    """Locate bounding box of the line containing the extracted value (or its immediate label)."""
    norm_val = normalize_vietnamese(value)
    if not norm_val:
        return None
    # Pass 1: direct line match containing value tokens
    val_tokens = [t for t in norm_val.split() if len(t) >= 2][:4]
    for item in lines:
        ln_norm = normalize_vietnamese(item["text"])
        if norm_val in ln_norm or (
            val_tokens and all(tok in ln_norm for tok in val_tokens)
        ):
            return list(item["bbox"])

    # Pass 2: line immediately following the label line
    for idx, item in enumerate(lines):
        if match_keyword(item["text"], label_keywords):
            if idx + 1 < len(lines):
                return list(lines[idx + 1]["bbox"])
            return list(item["bbox"])
    return None

_LEADING_FIELD_LABELS_RE = re.compile(
    r"^(?:"
    r"qu[êe]\s+qu[áa]n\s*(?:/\s*place\s+of\s+origin)?|"
    r"n[ơo]i\s+th[ườu][ờo]ng\s+tr[úu]\s*(?:/\s*place\s+of\s+residence)?|"
    r"n[ơo]i\s+c[ưư]\s+tr[úu]\s*(?:/\s*place\s+of\s+residence)?|"
    r"n[ơo]i\s+đ[ăa]ng\s+k[ýy]\s+khai\s+sinh\s*(?:/\s*place\s+of\s+birth(?:\s+registration)?)?|"
    r"đ[ặa]c\s+đi[ểe]m\s+nh[ậa]n\s+d[ạa]ng\s*(?:/\s*personal\s+identification)?"
    r")\s*[:.\-]?\s*",
    re.IGNORECASE,
)

def _spatial_extract_multilines(
    lines: List[Dict[str, Any]],
    label_keywords: List[str],
    stop_keywords: List[str],
    max_follow_lines: int = 2,
) -> Optional[Tuple[str, List[int], float]]:
    """Extract multi-line field values using spatial segment ordering (same-row right + below lines)."""
    for idx, item in enumerate(lines):
        if match_keyword(item["text"], label_keywords) or fuzzy_match_keyword(
            item["text"], label_keywords, 0.80
        )["matched"]:
            collected_parts: List[str] = []
            boxes: List[List[int]] = []
            confs: List[float] = []

            same_line = item["text"].strip()
            tail = _LEADING_FIELD_LABELS_RE.sub("", same_line).strip(" :.-")
            if tail == same_line and ":" in same_line:
                tail = same_line.split(":", 1)[1].strip()
            # Ignore stray 1-2 char punctuation noise after label
            if (
                tail
                and tail != same_line
                and len(re.sub(r"[^a-zA-Z0-9À-ỹ]", "", tail)) >= 3
                and not match_keyword(tail, stop_keywords)
            ):
                collected_parts.append(tail)
                boxes.append(item["bbox"])
                confs.append(item["confidence"])

            lbl_y_mid = (item["bbox"][1] + item["bbox"][3]) / 2.0
            lbl_x_right = item["bbox"][2]
            lbl_x_left = item["bbox"][0]

            follow_count = 0
            for nxt in lines[idx + 1 :]:
                nxt_y_mid = (nxt["bbox"][1] + nxt["bbox"][3]) / 2.0
                # Case 1: Value segment on the SAME horizontal row to the RIGHT of the label
                if abs(nxt_y_mid - lbl_y_mid) <= 22 and nxt["bbox"][0] >= lbl_x_right - 10:
                    if match_keyword(nxt["text"], stop_keywords):
                        continue
                    collected_parts.append(nxt["text"].strip())
                    boxes.append(nxt["bbox"])
                    confs.append(nxt["confidence"])
                    continue

                # Case 2: Continuation lines BELOW the label (aligned horizontally with field column)
                if nxt["bbox"][1] - item["bbox"][3] > 105:
                    break
                # Ignore left-side expiry column on old CCCD (x2 < lbl_x_left - 20)
                if nxt["bbox"][2] < lbl_x_left - 20:
                    continue
                if match_keyword(nxt["text"], stop_keywords) or fuzzy_match_keyword(
                    nxt["text"], stop_keywords, 0.82
                )["matched"]:
                    break
                if "<<" in nxt["text"] or match_keyword(
                    nxt["text"], ["cuc truong", "bo cong an", "ministry of public"]
                ):
                    break

                collected_parts.append(nxt["text"].strip())
                boxes.append(nxt["bbox"])
                confs.append(nxt["confidence"])
                follow_count += 1
                if follow_count >= max_follow_lines:
                    break

            joined = _clean_text_value(" ".join(collected_parts))
            if joined:
                merged_box = [
                    min(b[0] for b in boxes),
                    min(b[1] for b in boxes),
                    max(b[2] for b in boxes),
                    max(b[3] for b in boxes),
                ]
                avg_conf = sum(confs) / len(confs) if confs else item["confidence"]
                return joined, merged_box, round(avg_conf, 4)
    return None

def _extract_same_row_split_fields(
    lines: List[Dict[str, Any]], card_width: int
) -> Dict[str, Tuple[str, List[int], float]]:
    """Handle horizontal split rows on Vietnamese ID cards:
    - Old CCCD: [Giới tính / Sex: Nam]   |   [Quốc tịch / Nationality: Việt Nam]
    - New Căn cước: [Ngày, tháng, năm sinh: DD/MM/YYYY]   |   [Giới tính / Sex: Nam]
    """
    extracted: Dict[str, Tuple[str, List[int], float]] = {}
    for item in lines:
        txt = item["text"]
        norm = normalize_vietnamese(txt)
        if "gioi tinh" in norm or "sex" in norm:
            g = _validate_gender(txt)
            if g:
                extracted["gender"] = (g, item["bbox"], item["confidence"])
        if "quoc tich" in norm or "nationality" in norm:
            nat = _validate_nationality(txt)
            if nat:
                extracted["nationality"] = (nat, item["bbox"], item["confidence"])
        if "ngay" in norm and "sinh" in norm:
            d = _validate_and_normalize_date(txt)
            if d:
                extracted["date_of_birth"] = (d, item["bbox"], item["confidence"])
    return extracted


def extract_fields_for_side(
    doc_type: DocumentType,
    side: CardSide,
    merged_ocr: Dict[str, Any],
    page_index: int,
    card_shape: Tuple[int, int],
) -> Dict[str, Any]:
    """Extract and validate all applicable fields for a single classified card side."""
    merged_text: str = merged_ocr.get("merged_text", "")
    lines: List[Dict[str, Any]] = merged_ocr.get("lines", [])
    card_h, card_w = card_shape[:2]

    fields: Dict[str, Any] = {}
    field_confidence: Dict[str, float] = {}
    field_sources: Dict[str, Any] = {}
    warnings: List[str] = []

    def _record(
        key: str,
        value: Any,
        conf: float,
        label_display: str,
        bbox: Optional[List[int]],
    ) -> None:
        if value is None:
            return
        fields[key] = value
        final_conf = round(max(0.0, min(0.99, conf)), 4)
        field_confidence[key] = final_conf
        field_sources[key] = {
            "value": value,
            "confidence": final_conf,
            "source": {
                "page": page_index,
                "side": side.value,
                "field_label": label_display,
                "bbox": bbox,
                "coordinate_space": "processed_card",
            },
        }

    all_stop_labels = [
        "so / no",
        "so dinh danh ca nhan",
        "ho va ten",
        "ho chu dem va ten khai sinh",
        "ngay sinh",
        "ngay thang nam sinh",
        "gioi tinh",
        "quoc tich",
        "que quan",
        "noi thuong tru",
        "noi cu tru",
        "noi dang ky khai sinh",
        "co gia tri den",
        "dac diem nhan dang",
        "ngay thang nam",
        "ngay thang nam cap",
        "ngay thang nam het han",
    ]

    split_hits = _extract_same_row_split_fields(lines, card_w)

    # ------------------------------------------------------------------
    # CASE A: CITIZEN_ID_CARD (CCCD mẫu cũ) — FRONT
    # ------------------------------------------------------------------
    if doc_type == DocumentType.CITIZEN_ID_CARD and side == CardSide.FRONT:
        res_id = extract_field_with_regex(
            merged_text, OLD_CCCD_FRONT_PATTERNS["id_number"]
        )
        val_id = _validate_id_number(res_id["value"])
        if val_id:
            bbox = _find_line_bbox_for_value(lines, val_id, ["so / no", "so cccd"])
            _record("id_number", val_id, 0.96 if res_id["pattern_index"] == 0 else 0.88, "Số / No.", bbox)

        res_name = extract_field_with_regex(
            merged_text, OLD_CCCD_FRONT_PATTERNS["full_name"]
        )
        val_name = _validate_full_name(res_name["value"])
        if val_name:
            bbox = _find_line_bbox_for_value(lines, val_name, ["ho va ten", "full name"])
            _record("full_name", val_name, 0.94, "Họ và tên / Full name", bbox)

        if "date_of_birth" in split_hits:
            d_val, d_box, d_conf = split_hits["date_of_birth"]
            _record("date_of_birth", d_val, d_conf, "Ngày sinh / Date of birth", d_box)
        else:
            res_dob = extract_field_with_regex(
                merged_text, OLD_CCCD_FRONT_PATTERNS["date_of_birth"]
            )
            val_dob = _validate_and_normalize_date(res_dob["value"])
            if val_dob:
                bbox = _find_line_bbox_for_value(lines, val_dob, ["ngay sinh", "date of birth"])
                _record("date_of_birth", val_dob, 0.93, "Ngày sinh / Date of birth", bbox)

        if "gender" in split_hits:
            g_val, g_box, g_conf = split_hits["gender"]
            _record("gender", g_val, g_conf, "Giới tính / Sex", g_box)
        else:
            res_g = extract_field_with_regex(
                merged_text, OLD_CCCD_FRONT_PATTERNS["gender"]
            )
            val_g = _validate_gender(res_g["value"])
            if val_g:
                bbox = _find_line_bbox_for_value(lines, val_g, ["gioi tinh", "sex"])
                _record("gender", val_g, 0.92, "Giới tính / Sex", bbox)

        if "nationality" in split_hits:
            n_val, n_box, n_conf = split_hits["nationality"]
            _record("nationality", n_val, n_conf, "Quốc tịch / Nationality", n_box)
        else:
            res_n = extract_field_with_regex(
                merged_text, OLD_CCCD_FRONT_PATTERNS["nationality"]
            )
            val_n = _validate_nationality(res_n["value"])
            if val_n:
                bbox = _find_line_bbox_for_value(lines, val_n, ["quoc tich", "nationality"])
                _record("nationality", val_n, 0.93, "Quốc tịch / Nationality", bbox)

        sp_origin = _spatial_extract_multilines(
            lines, ["que quan", "place of origin"], all_stop_labels, max_follow_lines=2
        )
        if sp_origin:
            _record(
                "place_of_origin",
                sp_origin[0],
                sp_origin[2],
                "Quê quán / Place of origin",
                sp_origin[1],
            )
        else:
            res_orig = extract_field_with_regex(
                merged_text, OLD_CCCD_FRONT_PATTERNS["place_of_origin"]
            )
            val_orig = _clean_text_value(res_orig["value"])
            if val_orig:
                bbox = _find_line_bbox_for_value(lines, val_orig, ["que quan", "place of origin"])
                _record("place_of_origin", val_orig, 0.86, "Quê quán / Place of origin", bbox)

        sp_perm = _spatial_extract_multilines(
            lines,
            ["noi thuong tru", "place of residence"],
            all_stop_labels,
            max_follow_lines=2,
        )
        if sp_perm:
            _record(
                "permanent_residence",
                sp_perm[0],
                sp_perm[2],
                "Nơi thường trú / Place of residence",
                sp_perm[1],
            )
        else:
            res_perm = extract_field_with_regex(
                merged_text, OLD_CCCD_FRONT_PATTERNS["permanent_residence"]
            )
            val_perm = _clean_text_value(res_perm["value"])
            if val_perm:
                bbox = _find_line_bbox_for_value(
                    lines, val_perm, ["noi thuong tru", "place of residence"]
                )
                _record(
                    "permanent_residence",
                    val_perm,
                    0.86,
                    "Nơi thường trú / Place of residence",
                    bbox,
                )

    # ------------------------------------------------------------------
    # CASE B: CITIZEN_ID_CARD (CCCD mẫu cũ) — BACK
    # ------------------------------------------------------------------
    elif doc_type == DocumentType.CITIZEN_ID_CARD and side == CardSide.BACK:
        sp_ident = _spatial_extract_multilines(
            lines,
            ["dac diem nhan dang", "personal identification"],
            all_stop_labels,
            max_follow_lines=2,
        )
        if sp_ident:
            _record(
                "personal_identification",
                sp_ident[0],
                sp_ident[2],
                "Đặc điểm nhận dạng / Personal identification",
                sp_ident[1],
            )
        else:
            res_ident = extract_field_with_regex(
                merged_text, OLD_CCCD_BACK_PATTERNS["personal_identification"]
            )
            val_ident = _clean_text_value(res_ident["value"])
            if val_ident:
                bbox = _find_line_bbox_for_value(
                    lines, val_ident, ["dac diem nhan dang", "personal identification"]
                )
                _record(
                    "personal_identification",
                    val_ident,
                    0.85,
                    "Đặc điểm nhận dạng / Personal identification",
                    bbox,
                )

        res_issue = extract_field_with_regex(
            merged_text, OLD_CCCD_BACK_PATTERNS["issue_date"]
        )
        val_issue = _validate_and_normalize_date(res_issue["value"])
        if val_issue:
            bbox = _find_line_bbox_for_value(
                lines, val_issue, ["ngay thang nam", "date month year"]
            )
            _record(
                "issue_date",
                val_issue,
                0.92,
                "Ngày, tháng, năm / Date, month, year",
                bbox,
            )

        mrz_parsed = parse_and_validate_mrz(
            merged_text, merged_ocr.get("mrz_raw_text", "")
        )
        fields["mrz"] = mrz_parsed["mrz"]
        fields["_mrz_cross_check"] = mrz_parsed["cross_check"]
        field_confidence["mrz"] = mrz_parsed["mrz"]["confidence"]
        if mrz_parsed["mrz"]["normalized"]:
            mrz_bbox = _find_line_bbox_for_value(lines, "IDVNM", ["idvnm"])
            field_sources["mrz"] = {
                "value": mrz_parsed["mrz"]["normalized"],
                "confidence": mrz_parsed["mrz"]["confidence"],
                "source": {
                    "page": page_index,
                    "side": side.value,
                    "field_label": "MRZ",
                    "bbox": mrz_bbox,
                    "coordinate_space": "processed_card",
                },
            }
            if mrz_parsed["mrz"]["checksum_valid"] is False:
                warnings.append("MRZ check digit validation failed on back side.")
        else:
            warnings.append("MRZ zone could not be reliably detected on back side.")

    # ------------------------------------------------------------------
    # CASE C: IDENTITY_CARD (Căn cước mẫu mới) — FRONT
    # ------------------------------------------------------------------
    elif doc_type == DocumentType.IDENTITY_CARD and side == CardSide.FRONT:
        res_pid = extract_field_with_regex(
            merged_text, NEW_IDENTITY_FRONT_PATTERNS["personal_id_number"]
        )
        val_pid = _validate_id_number(res_pid["value"])
        if val_pid:
            bbox = _find_line_bbox_for_value(
                lines, val_pid, ["so dinh danh ca nhan", "personal identification number"]
            )
            _record(
                "personal_id_number",
                val_pid,
                0.96 if res_pid["pattern_index"] == 0 else 0.88,
                "Số định danh cá nhân / Personal identification number",
                bbox,
            )

        res_name = extract_field_with_regex(
            merged_text, NEW_IDENTITY_FRONT_PATTERNS["full_name"]
        )
        val_name = _validate_full_name(res_name["value"])
        if val_name:
            bbox = _find_line_bbox_for_value(
                lines, val_name, ["ho chu dem va ten khai sinh", "full name"]
            )
            _record(
                "full_name",
                val_name,
                0.94,
                "Họ, chữ đệm và tên khai sinh / Full name",
                bbox,
            )

        if "date_of_birth" in split_hits:
            d_val, d_box, d_conf = split_hits["date_of_birth"]
            _record(
                "date_of_birth",
                d_val,
                d_conf,
                "Ngày, tháng, năm sinh / Date of birth",
                d_box,
            )
        else:
            res_dob = extract_field_with_regex(
                merged_text, NEW_IDENTITY_FRONT_PATTERNS["date_of_birth"]
            )
            val_dob = _validate_and_normalize_date(res_dob["value"])
            if val_dob:
                bbox = _find_line_bbox_for_value(
                    lines, val_dob, ["ngay thang nam sinh", "date of birth"]
                )
                _record(
                    "date_of_birth",
                    val_dob,
                    0.93,
                    "Ngày, tháng, năm sinh / Date of birth",
                    bbox,
                )

        if "gender" in split_hits:
            g_val, g_box, g_conf = split_hits["gender"]
            _record("gender", g_val, g_conf, "Giới tính / Sex", g_box)
        else:
            res_g = extract_field_with_regex(
                merged_text, NEW_IDENTITY_FRONT_PATTERNS["gender"]
            )
            val_g = _validate_gender(res_g["value"])
            if val_g:
                bbox = _find_line_bbox_for_value(lines, val_g, ["gioi tinh", "sex"])
                _record("gender", val_g, 0.92, "Giới tính / Sex", bbox)

        if "nationality" in split_hits:
            n_val, n_box, n_conf = split_hits["nationality"]
            _record("nationality", n_val, n_conf, "Quốc tịch / Nationality", n_box)
        else:
            res_n = extract_field_with_regex(
                merged_text, NEW_IDENTITY_FRONT_PATTERNS["nationality"]
            )
            val_n = _validate_nationality(res_n["value"])
            if val_n:
                bbox = _find_line_bbox_for_value(lines, val_n, ["quoc tich", "nationality"])
                _record("nationality", val_n, 0.93, "Quốc tịch / Nationality", bbox)

    # ------------------------------------------------------------------
    # CASE D: IDENTITY_CARD (Căn cước mẫu mới) — BACK
    # ------------------------------------------------------------------
    elif doc_type == DocumentType.IDENTITY_CARD and side == CardSide.BACK:
        sp_res = _spatial_extract_multilines(
            lines,
            ["noi cu tru", "place of residence"],
            all_stop_labels,
            max_follow_lines=2,
        )
        if sp_res:
            _record(
                "place_of_residence",
                sp_res[0],
                sp_res[2],
                "Nơi cư trú / Place of residence",
                sp_res[1],
            )
        else:
            res_por = extract_field_with_regex(
                merged_text, NEW_IDENTITY_BACK_PATTERNS["place_of_residence"]
            )
            val_por = _clean_text_value(res_por["value"])
            if val_por:
                bbox = _find_line_bbox_for_value(
                    lines, val_por, ["noi cu tru", "place of residence"]
                )
                _record(
                    "place_of_residence",
                    val_por,
                    0.87,
                    "Nơi cư trú / Place of residence",
                    bbox,
                )

        sp_birth = _spatial_extract_multilines(
            lines,
            ["noi dang ky khai sinh", "place of birth"],
            all_stop_labels,
            max_follow_lines=2,
        )
        if sp_birth:
            _record(
                "place_of_birth_registration",
                sp_birth[0],
                sp_birth[2],
                "Nơi đăng ký khai sinh / Place of birth",
                sp_birth[1],
            )
        else:
            res_pob = extract_field_with_regex(
                merged_text, NEW_IDENTITY_BACK_PATTERNS["place_of_birth_registration"]
            )
            val_pob = _clean_text_value(res_pob["value"])
            if val_pob:
                bbox = _find_line_bbox_for_value(
                    lines, val_pob, ["noi dang ky khai sinh", "place of birth"]
                )
                _record(
                    "place_of_birth_registration",
                    val_pob,
                    0.87,
                    "Nơi đăng ký khai sinh / Place of birth",
                    bbox,
                )

        res_iss = extract_field_with_regex(
            merged_text, NEW_IDENTITY_BACK_PATTERNS["issue_date"]
        )
        val_iss = _validate_and_normalize_date(res_iss["value"])
        if val_iss:
            bbox = _find_line_bbox_for_value(
                lines, val_iss, ["ngay thang nam cap", "date of issue"]
            )
            _record(
                "issue_date",
                val_iss,
                0.92,
                "Ngày, tháng, năm cấp / Date of issue",
                bbox,
            )

        res_exp = extract_field_with_regex(
            merged_text, NEW_IDENTITY_BACK_PATTERNS["expiry_date"]
        )
        val_exp = _validate_and_normalize_date(res_exp["value"])
        if val_exp:
            bbox = _find_line_bbox_for_value(
                lines, val_exp, ["ngay thang nam het han", "date of expiry"]
            )
            _record(
                "expiry_date",
                val_exp,
                0.92,
                "Ngày, tháng, năm hết hạn / Date of expiry",
                bbox,
            )

        mrz_parsed = parse_and_validate_mrz(
            merged_text, merged_ocr.get("mrz_raw_text", "")
        )
        fields["mrz"] = mrz_parsed["mrz"]
        fields["_mrz_cross_check"] = mrz_parsed["cross_check"]
        field_confidence["mrz"] = mrz_parsed["mrz"]["confidence"]
        if mrz_parsed["mrz"]["normalized"]:
            mrz_bbox = _find_line_bbox_for_value(lines, "IDVNM", ["idvnm"])
            field_sources["mrz"] = {
                "value": mrz_parsed["mrz"]["normalized"],
                "confidence": mrz_parsed["mrz"]["confidence"],
                "source": {
                    "page": page_index,
                    "side": side.value,
                    "field_label": "MRZ",
                    "bbox": mrz_bbox,
                    "coordinate_space": "processed_card",
                },
            }
            if mrz_parsed["mrz"]["checksum_valid"] is False:
                warnings.append("MRZ check digit validation failed on back side.")
        else:
            warnings.append("MRZ zone could not be reliably detected on back side.")

    return {
        "fields": fields,
        "field_confidence": field_confidence,
        "field_sources": field_sources,
        "warnings": warnings,
    }


def _verify_front_back_same_person(
    front_fields: Dict[str, Any], back_fields: Dict[str, Any]
) -> Tuple[bool, List[str]]:
    """Verify front and back belong to the same cardholder using MRZ cross-check without copying data."""
    warnings: List[str] = []
    mrz_cc: Dict[str, Any] = back_fields.get("_mrz_cross_check") or {}
    if not mrz_cc:
        return True, warnings

    front_id = front_fields.get("id_number") or front_fields.get("personal_id_number")
    mrz_id = mrz_cc.get("id_number")
    if front_id and mrz_id and front_id != mrz_id:
        warnings.append(
            "Front ID number does not match back MRZ ID number. Front and back may belong to different cards."
        )
        return False, warnings

    front_name = front_fields.get("full_name")
    mrz_name = mrz_cc.get("full_name_ascii")
    if front_name and mrz_name:
        norm_front = normalize_vietnamese(front_name).upper()
        norm_mrz = re.sub(r"\s+", " ", mrz_name.upper()).strip()
        sim = difflib.SequenceMatcher(None, norm_front, norm_mrz).ratio()
        if sim < 0.72:
            warnings.append(
                "Front full name does not match back MRZ holder name. Front and back may belong to different cards."
            )
            return False, warnings

    front_dob = front_fields.get("date_of_birth")
    mrz_dob = mrz_cc.get("dob_yymmdd")
    if front_dob and mrz_dob and len(mrz_dob) == 6:
        parts = front_dob.split("/")
        if len(parts) == 3:
            expected_yymmdd = f"{parts[2][2:]}{parts[1]}{parts[0]}"
            if expected_yymmdd != mrz_dob:
                warnings.append(
                    "Front date of birth does not match back MRZ date of birth."
                )
                return False, warnings

    return True, warnings


# ==============================================================================
# 9. INPUT LOADER (IMAGES & MULTI-PAGE PDF) + MAIN PIPELINE ORCHESTRATOR
# ==============================================================================


def _load_pages_from_bytes(
    data: bytes, filename: str, dpi: int = 250
) -> List[Tuple[int, np.ndarray]]:
    """Decode image bytes or multi-page PDF bytes into a list of (0-based page_index, BGR ndarray)."""
    ext = Path(filename).suffix.lower()
    if ext == ".pdf" or data[:5] == b"%PDF-":
        pages: List[Tuple[int, np.ndarray]] = []
        if fitz is not None:
            with fitz.open(stream=data, filetype="pdf") as doc:
                if len(doc) > MAX_PDF_PAGES:
                    raise ValueError(
                        f"PDF exceeds maximum allowed pages ({MAX_PDF_PAGES})."
                    )
                for idx, page in enumerate(doc):
                    pix = page.get_pixmap(dpi=dpi)
                    mode = "RGBA" if pix.alpha else "RGB"
                    pil_img = Image.frombytes(mode, (pix.width, pix.height), pix.samples)
                    if mode == "RGBA":
                        pil_img = pil_img.convert("RGB")
                    bgr = cv2.cvtColor(np.array(pil_img), cv2.COLOR_RGB2BGR)
                    pages.append((idx, bgr))
            return pages
        if convert_from_bytes is not None:
            pil_pages = convert_from_bytes(data, dpi=dpi, last_page=MAX_PDF_PAGES)
            for idx, pil_img in enumerate(pil_pages):
                bgr = cv2.cvtColor(np.array(pil_img.convert("RGB")), cv2.COLOR_RGB2BGR)
                pages.append((idx, bgr))
            return pages
        raise RuntimeError(
            "PDF input received, but neither PyMuPDF (fitz) nor pdf2image is installed."
        )

    # Image decoding with EXIF orientation normalization
    pil_img = Image.open(io.BytesIO(data))
    pil_img = ImageOps.exif_transpose(pil_img).convert("RGB")
    bgr = cv2.cvtColor(np.array(pil_img), cv2.COLOR_RGB2BGR)
    return [(0, bgr)]


class VietnamIDCardChecker:
    """End-to-end Vietnam ID card checker & extractor."""

    def __init__(
        self,
        engine_config: Optional[EngineConfig] = None,
        quality_config: Optional[QualityConfig] = None,
    ) -> None:
        self.engine_config = engine_config or EngineConfig()
        self.quality_config = quality_config or QualityConfig()
        self.tesseract = TesseractEngine(self.engine_config)
        self.vietocr = VietOCREngine(self.engine_config)

    def _run_ocr_pipeline_on_image(
        self,
        page_index: int,
        bgr_img: np.ndarray,
        card_detected: bool,
        quad_pts: Optional[List[List[int]]],
    ) -> Dict[str, Any]:
        oriented_bgr, rotation_angle = auto_orient_card(bgr_img)
        variants = build_ocr_image_variants(
            oriented_bgr, target_width=self.quality_config.reference_width_px
        )
        processed_card = variants["color_sharp"]

        sharpness_info = check_image_sharpness(processed_card, self.quality_config)
        tess_res = self.tesseract.recognize(variants)
        candidate_boxes = [li.bbox for li in tess_res.get("lines", [])]
        viet_res = self.vietocr.recognize(variants, candidate_boxes)
        merged_ocr = merge_ocr_results(tess_res, viet_res)

        mean_ocr_conf = (
            sum(x["confidence"] for x in merged_ocr["lines"]) / len(merged_ocr["lines"])
            if merged_ocr["lines"]
            else 0.0
        )
        quality_info = check_image_quality(
            processed_card, ocr_confidence=mean_ocr_conf, config=self.quality_config
        )

        prelim_mrz = parse_and_validate_mrz(
            merged_ocr["merged_text"], merged_ocr.get("mrz_raw_text", "")
        )["mrz"]
        side_cls = classify_id_card_side(
            merged_ocr["merged_text"],
            mrz_info=prelim_mrz,
            lines=merged_ocr["lines"],
        )

        doc_type = DocumentType(side_cls["document_type"])
        side = CardSide(side_cls["side"])
        extraction = extract_fields_for_side(
            doc_type=doc_type,
            side=side,
            merged_ocr=merged_ocr,
            page_index=page_index,
            card_shape=processed_card.shape[:2],
        )

        return {
            "page": page_index,
            "card_detected": card_detected,
            "card_quad": quad_pts,
            "rotation_applied_deg": rotation_angle,
            "quality": quality_info,
            "sharpness": sharpness_info,
            "classification": side_cls,
            "document_type": doc_type,
            "side": side,
            "merged_ocr": merged_ocr,
            "mean_ocr_confidence": round(mean_ocr_conf, 4),
            "extraction": extraction,
        }

    def _analyze_single_page(
        self, page_index: int, raw_bgr: np.ndarray
    ) -> Dict[str, Any]:
        """Process a single image/page through preprocessing, quality check, dual OCR, and extraction."""
        warped_bgr, card_detected, quad_pts = detect_and_warp_card(raw_bgr)
        oriented_bgr, rotation_angle = auto_orient_card(warped_bgr)
        variants = build_ocr_image_variants(
            oriented_bgr, target_width=self.quality_config.reference_width_px
        )
        processed_card = variants["color_sharp"]

        sharpness_info = check_image_sharpness(processed_card, self.quality_config)
        tess_res = self.tesseract.recognize(variants)
        candidate_boxes = [li.bbox for li in tess_res.get("lines", [])]
        viet_res = self.vietocr.recognize(variants, candidate_boxes)
        merged_ocr = merge_ocr_results(tess_res, viet_res)

        mean_ocr_conf = (
            sum(x["confidence"] for x in merged_ocr["lines"]) / len(merged_ocr["lines"])
            if merged_ocr["lines"]
            else 0.0
        )
        quality_info = check_image_quality(
            processed_card, ocr_confidence=mean_ocr_conf, config=self.quality_config
        )

        prelim_mrz = parse_and_validate_mrz(
            merged_ocr["merged_text"], merged_ocr.get("mrz_raw_text", "")
        )["mrz"]
        side_cls = classify_id_card_side(
            merged_ocr["merged_text"],
            mrz_info=prelim_mrz,
            lines=merged_ocr["lines"],
        )

        doc_type = DocumentType(side_cls["document_type"])
        side = CardSide(side_cls["side"])
        extraction = extract_fields_for_side(
            doc_type=doc_type,
            side=side,
            merged_ocr=merged_ocr,
            page_index=page_index,
            card_shape=processed_card.shape[:2],
        )

        return {
            "page": page_index,
            "card_detected": card_detected,
            "card_quad": quad_pts,
            "rotation_applied_deg": rotation_angle,
            "quality": quality_info,
            "sharpness": sharpness_info,
            "classification": side_cls,
            "document_type": doc_type,
            "side": side,
            "merged_ocr": merged_ocr,
            "mean_ocr_confidence": round(mean_ocr_conf, 4),
            "extraction": extraction,
        }

    def process(
        self,
        inputs: Sequence[Union[str, Path, bytes, Tuple[str, bytes], np.ndarray, Image.Image]],
        include_ocr_text: bool = True,
        allowed_page_indices: Optional[Sequence[int]] = None,
    ) -> Dict[str, Any]:
        """Process one or more images / multi-page PDFs and return unified schema JSON."""
        errors: List[str] = []
        warnings: List[str] = []

        if not self.tesseract.available and not self.vietocr.available:
            errors.append(
                "No OCR engine is available (both Tesseract and VietOCR failed to initialize)."
            )
            return self._build_response(
                success=False,
                status=CheckStatus.UNREADABLE,
                document_type=DocumentType.UNKNOWN,
                front_detected=False,
                back_detected=False,
                page_anchor={"front": [], "back": []},
                image_quality=ImageQualityStatus.BLURRY.value,
                overall_confidence=0.0,
                data=IDCardExtractedDataModel().model_dump(),
                field_confidence={},
                field_sources={},
                ocr_text={"front": None, "back": None},
                errors=errors,
                warnings=warnings,
            )

        if not self.vietocr.available and self.vietocr.init_error:
            warnings.append(
                f"VietOCR unavailable; running in Tesseract-only fallback mode ({self.vietocr.init_error})."
            )
        if not self.tesseract.available and self.tesseract.init_error:
            warnings.append(
                f"Tesseract unavailable; running in VietOCR-only fallback mode ({self.tesseract.init_error})."
            )

        # Normalize all input items into sequential 0-based page list
        page_images: List[Tuple[int, np.ndarray]] = []
        global_idx = 0
        for item in inputs:
            try:
                if isinstance(item, (str, Path)):
                    p = Path(item)
                    if not p.exists() or not p.is_file():
                        errors.append(f"Input file not found: {p.name}")
                        continue
                    raw_bytes = p.read_bytes()
                    loaded = _load_pages_from_bytes(
                        raw_bytes, p.name, dpi=self.engine_config.pdf_render_dpi
                    )
                    for _, img in loaded:
                        page_images.append((global_idx, img))
                        global_idx += 1
                elif isinstance(item, tuple) and len(item) == 2:
                    fname, raw_bytes = item
                    loaded = _load_pages_from_bytes(
                        raw_bytes, str(fname), dpi=self.engine_config.pdf_render_dpi
                    )
                    for _, img in loaded:
                        page_images.append((global_idx, img))
                        global_idx += 1
                elif isinstance(item, bytes):
                    loaded = _load_pages_from_bytes(
                        item, "upload.bin", dpi=self.engine_config.pdf_render_dpi
                    )
                    for _, img in loaded:
                        page_images.append((global_idx, img))
                        global_idx += 1
                elif isinstance(item, np.ndarray):
                    page_images.append((global_idx, item.copy()))
                    global_idx += 1
                elif isinstance(item, Image.Image):
                    bgr = cv2.cvtColor(np.array(item.convert("RGB")), cv2.COLOR_RGB2BGR)
                    page_images.append((global_idx, bgr))
                    global_idx += 1
            except Exception as exc:
                errors.append(f"Failed to decode input page: {exc}")

        if allowed_page_indices is not None:
            allowed_set = set(allowed_page_indices)
            page_images = [p for p in page_images if p[0] in allowed_set]

        if not page_images:
            errors.append("No valid image or PDF page provided.")
            return self._build_response(
                success=False,
                status=CheckStatus.MISSING_BOTH_SIDES,
                document_type=DocumentType.UNKNOWN,
                front_detected=False,
                back_detected=False,
                page_anchor={"front": [], "back": []},
                image_quality=ImageQualityStatus.BLURRY.value,
                overall_confidence=0.0,
                data=IDCardExtractedDataModel().model_dump(),
                field_confidence={},
                field_sources={},
                ocr_text={"front": None, "back": None},
                errors=errors,
                warnings=warnings,
            )

        analyzed_pages = [
            self._analyze_single_page(p_idx, bgr) for p_idx, bgr in page_images
        ]

        # Separate front, back, unknown, and blurry candidates
        front_pages = [
            p for p in analyzed_pages if p["side"] == CardSide.FRONT
        ]
        back_pages = [
            p for p in analyzed_pages if p["side"] == CardSide.BACK
        ]
        unknown_pages = [
            p for p in analyzed_pages if p["side"] == CardSide.UNKNOWN
        ]
        blurry_pages = [
            p
            for p in analyzed_pages
            if p["quality"]["status"] == ImageQualityStatus.BLURRY.value
        ]

        if len(front_pages) > 1:
            warnings.append(
                f"Multiple front-side candidates detected ({len(front_pages)}); selecting highest-confidence page."
            )
        if len(back_pages) > 1:
            warnings.append(
                f"Multiple back-side candidates detected ({len(back_pages)}); selecting highest-confidence page."
            )

        best_front = (
            max(front_pages, key=lambda p: p["classification"]["confidence"])
            if front_pages
            else None
        )
        best_back = (
            max(back_pages, key=lambda p: p["classification"]["confidence"])
            if back_pages
            else None
        )

        # Determine dominant document type
        doc_votes: Dict[DocumentType, float] = {}
        for p in (best_front, best_back):
            if p and p["document_type"] != DocumentType.UNKNOWN:
                dt_k = p["document_type"]
                doc_votes[dt_k] = doc_votes.get(dt_k, 0.0) + p["classification"]["confidence"]

        if doc_votes:
            dominant_doc_type = max(doc_votes.items(), key=lambda kv: kv[1])[0]
        else:
            dominant_doc_type = DocumentType.UNKNOWN

        # Warn if front and back were classified as different card generations
        if (
            best_front
            and best_back
            and best_front["document_type"] != best_back["document_type"]
        ):
            warnings.append(
                f"Card generation mismatch: front classified as {best_front['document_type'].value}, "
                f"back classified as {best_back['document_type'].value}."
            )

        page_anchor = {
            "front": [best_front["page"]] if best_front else [],
            "back": [best_back["page"]] if best_back else [],
        }
        front_detected = best_front is not None
        back_detected = best_back is not None

        # Merge extracted fields strictly from their respective sides (zero cross-side hallucination)
        merged_data = IDCardExtractedDataModel().model_dump()
        merged_field_conf: Dict[str, float] = {}
        merged_field_sources: Dict[str, Any] = {}

        if best_front:
            for k, v in best_front["extraction"]["fields"].items():
                if not k.startswith("_") and k in merged_data:
                    merged_data[k] = v
            merged_field_conf.update(best_front["extraction"]["field_confidence"])
            merged_field_sources.update(best_front["extraction"]["field_sources"])
            warnings.extend(best_front["extraction"]["warnings"])

        if best_back:
            for k, v in best_back["extraction"]["fields"].items():
                if not k.startswith("_") and k in merged_data:
                    merged_data[k] = v
            merged_field_conf.update(best_back["extraction"]["field_confidence"])
            merged_field_sources.update(best_back["extraction"]["field_sources"])
            warnings.extend(best_back["extraction"]["warnings"])

        # Cross-side identity verification (front vs back MRZ)
        same_person = True
        if best_front and best_back:
            same_person, cross_warnings = _verify_front_back_same_person(
                best_front["extraction"]["fields"],
                best_back["extraction"]["fields"],
            )
            warnings.extend(cross_warnings)
            if not same_person:
                errors.append(
                    "Front and back images appear to belong to two different individuals/cards."
                )

        # Chronological sanity check on dates
        if merged_data.get("date_of_birth") and merged_data.get("issue_date"):
            try:
                dob_dt = dt.datetime.strptime(merged_data["date_of_birth"], "%d/%m/%Y").date()
                iss_dt = dt.datetime.strptime(merged_data["issue_date"], "%d/%m/%Y").date()
                if iss_dt <= dob_dt:
                    warnings.append("Issue date is earlier than or equal to date of birth.")
            except ValueError:
                pass

        # Overall image quality aggregation
        active_qualities = [
            p["quality"]["status"]
            for p in (best_front, best_back)
            if p is not None
        ]
        if not active_qualities:
            active_qualities = [p["quality"]["status"] for p in analyzed_pages]

        if ImageQualityStatus.BLURRY.value in active_qualities:
            overall_quality = ImageQualityStatus.BLURRY.value
        elif ImageQualityStatus.ACCEPTABLE.value in active_qualities:
            overall_quality = ImageQualityStatus.ACCEPTABLE.value
        else:
            overall_quality = ImageQualityStatus.SHARP.value

        # Priority-ordered status resolution (Section XI):
        # 1. Blurry check (distinguish blurry back/front from missing back/front!)
        # 2. Unknown document check
        # 3. Missing front / back / both sides check
        # 4. Unreadable / different-person conflict check
        # 5. Valid
        status: CheckStatus
        if blurry_pages and (
            overall_quality == ImageQualityStatus.BLURRY.value
            or (front_detected and not back_detected and len(analyzed_pages) >= 2)
            or (back_detected and not front_detected and len(analyzed_pages) >= 2)
        ):
            status = CheckStatus.BLURRY
            blurry_sides = []
            for bp in blurry_pages:
                s_label = (
                    "mặt trước"
                    if bp["side"] == CardSide.FRONT
                    else ("mặt sau" if bp["side"] == CardSide.BACK else f"trang {bp['page']}")
                )
                blurry_sides.append(s_label)
            errors.append(
                f"Ảnh căn cước bị mờ hoặc nhòe ({', '.join(blurry_sides)}). Vui lòng chụp lại."
            )
        elif dominant_doc_type == DocumentType.UNKNOWN and not front_detected and not back_detected:
            status = CheckStatus.UNKNOWN_DOCUMENT
            errors.append(
                "Tài liệu không thuộc định dạng Căn cước công dân hoặc Căn cước Việt Nam được hỗ trợ."
            )
        elif not front_detected and not back_detected:
            status = CheckStatus.MISSING_BOTH_SIDES
            errors.append("Không nhận diện được cả mặt trước và mặt sau của thẻ căn cước.")
        elif not front_detected:
            status = CheckStatus.MISSING_FRONT_SIDE
            errors.append("Thiếu ảnh mặt trước của thẻ căn cước.")
        elif not back_detected:
            status = CheckStatus.MISSING_BACK_SIDE
            errors.append("Thiếu ảnh mặt sau của thẻ căn cước.")
        elif not same_person:
            status = CheckStatus.UNREADABLE
        else:
            # Verify minimum essential fields were actually extracted
            essential_id = (
                merged_data.get("id_number")
                if dominant_doc_type == DocumentType.CITIZEN_ID_CARD
                else merged_data.get("personal_id_number")
            )
            if not essential_id and not merged_data.get("full_name"):
                status = CheckStatus.UNREADABLE
                errors.append(
                    "Đã nhận diện hai mặt thẻ nhưng không thể đọc được thông tin định danh đáng tin cậy."
                )
            else:
                status = CheckStatus.VALID

        is_complete = (
            status == CheckStatus.VALID
            and front_detected
            and back_detected
            and same_person
        )

        # Compute overall confidence from extracted fields and classification confidence
        conf_values = list(merged_field_conf.values())
        if best_front:
            conf_values.append(best_front["classification"]["confidence"])
        if best_back:
            conf_values.append(best_back["classification"]["confidence"])
        overall_conf = (
            round(sum(conf_values) / len(conf_values), 4) if conf_values else 0.0
        )

        ocr_text_out = {
            "front": (
                best_front["merged_ocr"]["merged_text"]
                if (include_ocr_text and best_front)
                else None
            ),
            "back": (
                best_back["merged_ocr"]["merged_text"]
                if (include_ocr_text and best_back)
                else None
            ),
        }

        # Deduplicate warnings while preserving order
        dedup_warnings = list(dict.fromkeys(warnings))

        return self._build_response(
            success=(status == CheckStatus.VALID),
            status=status,
            document_type=dominant_doc_type,
            front_detected=front_detected,
            back_detected=back_detected,
            page_anchor=page_anchor,
            image_quality=overall_quality,
            overall_confidence=overall_conf,
            data=merged_data,
            field_confidence=merged_field_conf,
            field_sources=merged_field_sources,
            ocr_text=ocr_text_out,
            errors=errors,
            warnings=dedup_warnings,
        )

    @staticmethod
    def _build_response(
        *,
        success: bool,
        status: CheckStatus,
        document_type: DocumentType,
        front_detected: bool,
        back_detected: bool,
        page_anchor: Dict[str, List[int]],
        image_quality: str,
        overall_confidence: float,
        data: Dict[str, Any],
        field_confidence: Dict[str, float],
        field_sources: Dict[str, Any],
        ocr_text: Dict[str, Optional[str]],
        errors: List[str],
        warnings: List[str],
    ) -> Dict[str, Any]:
        payload = {
            "success": success,
            "status": status.value,
            "document_type": document_type.value,
            "document_name": DOCUMENT_NAMES.get(document_type),
            "front_detected": front_detected,
            "back_detected": back_detected,
            "pageAnchor": page_anchor,
            "validation": {
                "is_complete": success and front_detected and back_detected,
                "image_quality": image_quality,
                "confidence": overall_confidence,
            },
            "data": data,
            "field_confidence": field_confidence,
            "field_sources": field_sources,
            "ocr_text": ocr_text,
            "errors": errors,
            "warnings": warnings,
        }
        return _to_json_safe(payload)


# ==============================================================================
# 10. PUBLIC INTEGRATION INTERFACE (COMPATIBLE WITH ai/classifier.py)
# ==============================================================================

_DEFAULT_CHECKER: Optional[VietnamIDCardChecker] = None


def get_default_checker() -> VietnamIDCardChecker:
    global _DEFAULT_CHECKER
    if _DEFAULT_CHECKER is None:
        _DEFAULT_CHECKER = VietnamIDCardChecker()
    return _DEFAULT_CHECKER


def check_id_card(
    files: Union[str, Path, bytes, np.ndarray, Image.Image, Sequence[Any]],
    include_ocr_text: bool = True,
    save_json_dir: Optional[Union[str, Path]] = None,
) -> Dict[str, Any]:
    """Primary public function to validate and extract Vietnam ID card information."""
    items = (
        [files]
        if isinstance(files, (str, Path, bytes, np.ndarray, Image.Image))
        else list(files)
    )
    result = get_default_checker().process(items, include_ocr_text=include_ocr_text)
    if save_json_dir is not None:
        saved_path = save_result_to_json(result, output_dir=save_json_dir)
        result["exported_json_path"] = str(saved_path)
    return result


def extract_id_card_information(
    files: Union[str, Path, bytes, np.ndarray, Image.Image, Sequence[Any]],
    include_ocr_text: bool = True,
) -> Dict[str, Any]:
    """Alias matching standard extractor naming conventions."""
    return check_id_card(files, include_ocr_text=include_ocr_text)


def process_id_card(
    files: Union[str, Path, bytes, np.ndarray, Image.Image, Sequence[Any]],
    include_ocr_text: bool = True,
) -> Dict[str, Any]:
    """Alias matching pipeline processor naming conventions."""
    return check_id_card(files, include_ocr_text=include_ocr_text)


def process_from_classifier_result(
    file_path: Union[str, Path],
    classifier_result: Dict[str, Any],
    include_ocr_text: bool = True,
) -> Dict[str, Any]:
    """Seamless integration bridge with `ai/classifier.py`'s `classify_file()` output.

    Filters pages where `documentClass == "NATIONAL_ID"`, converts 1-based pageAnchor
    from `classifier.py` to 0-based indices, and runs full ID card extraction.
    """
    pages = classifier_result.get("pages", [])
    national_id_indices = [
        int(p["pageAnchor"]) - 1
        for p in pages
        if p.get("documentClass") == "NATIONAL_ID" and int(p.get("pageAnchor", 0)) >= 1
    ]
    if not national_id_indices:
        return get_default_checker()._build_response(
            success=False,
            status=CheckStatus.UNKNOWN_DOCUMENT,
            document_type=DocumentType.UNKNOWN,
            front_detected=False,
            back_detected=False,
            page_anchor={"front": [], "back": []},
            image_quality=ImageQualityStatus.ACCEPTABLE.value,
            overall_confidence=0.0,
            data=IDCardExtractedDataModel().model_dump(),
            field_confidence={},
            field_sources={},
            ocr_text={"front": None, "back": None},
            errors=["Classifier did not detect any NATIONAL_ID page in the document."],
            warnings=[],
        )
    return get_default_checker().process(
        [Path(file_path)],
        include_ocr_text=include_ocr_text,
        allowed_page_indices=national_id_indices,
    )

def save_result_to_json(
    result: Dict[str, Any],
    output_dir: Union[str, Path] = ".",
    filename: Optional[str] = None,
) -> Path:
    """Export extraction result to <id_number>.json (or <personal_id_number>.json)."""
    out_folder = Path(output_dir)
    out_folder.mkdir(parents=True, exist_ok=True)

    if filename:
        target_name = filename if filename.endswith(".json") else f"{filename}.json"
    else:
        data = result.get("data") or {}
        card_id = data.get("id_number") or data.get("personal_id_number")
        if card_id and re.fullmatch(r"\d{12}", str(card_id)):
            target_name = f"{card_id}.json"
        else:
            ts = dt.datetime.now().strftime("%Y%m%d_%H%M%S")
            target_name = f"unknown_id_{ts}.json"

    out_path = out_folder / target_name
    safe_payload = _to_json_safe(result)
    out_path.write_text(
        json.dumps(safe_payload, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    return out_path


# ==============================================================================
# 11. FASTAPI ROUTER & STANDALONE APP ENDPOINT
# ==============================================================================

router = APIRouter(prefix="/api/v1/extractor", tags=["ID Card Extractor"])


def _validate_upload_file(upload: UploadFile, content: bytes) -> None:
    filename = upload.filename or ""
    ext = Path(filename).suffix.lower()
    content_type = (upload.content_type or "").lower()

    if ext and ext not in SUPPORTED_EXTENSIONS:
        raise HTTPException(
            status_code=400,
            detail=f"Unsupported file extension '{ext}'. Allowed: JPG, JPEG, PNG, WEBP, PDF.",
        )
    if content_type and content_type not in SUPPORTED_MIME_TYPES and not ext:
        raise HTTPException(
            status_code=400,
            detail=f"Unsupported MIME type '{content_type}'.",
        )
    if len(content) == 0:
        raise HTTPException(status_code=400, detail=f"Uploaded file '{filename}' is empty.")
    if len(content) > MAX_UPLOAD_FILE_BYTES:
        raise HTTPException(
            status_code=413,
            detail=f"File '{filename}' exceeds max allowed size ({MAX_UPLOAD_FILE_BYTES} bytes).",
        )


@router.post("/id-card", response_model=IDCardCheckResponse)
async def extract_id_card_endpoint(
    files: List[UploadFile] = File(..., description="One or more ID card images or multi-page PDFs"),
    include_ocr_text: bool = Form(True),
) -> JSONResponse:
    """Validate and extract Vietnam Citizen ID Card (CCCD) or Identity Card (Căn cước)."""
    if not files:
        raise HTTPException(status_code=400, detail="At least one file must be uploaded.")

    temp_paths: List[Path] = []
    try:
        for upload in files:
            raw_bytes = await upload.read()
            _validate_upload_file(upload, raw_bytes)
            suffix = Path(upload.filename or "upload.jpg").suffix.lower() or ".jpg"
            with tempfile.NamedTemporaryFile(delete=False, suffix=suffix) as tmp:
                tmp.write(raw_bytes)
                temp_paths.append(Path(tmp.name))

        loop = asyncio.get_running_loop()
        result = await asyncio.wait_for(
            loop.run_in_executor(
                None,
                lambda: get_default_checker().process(
                    temp_paths, include_ocr_text=include_ocr_text
                ),
            ),
            timeout=PROCESSING_TIMEOUT_SECONDS,
        )
        return JSONResponse(status_code=200, content=_to_json_safe(result))
    except HTTPException:
        raise
    except asyncio.TimeoutError:
        logger.error("ID card extraction timed out after %.1fs", PROCESSING_TIMEOUT_SECONDS)
        raise HTTPException(
            status_code=504, detail="ID card processing timed out."
        )
    except Exception as exc:
        logger.error("Unhandled error during ID card extraction: %s", type(exc).__name__)
        raise HTTPException(
            status_code=500, detail="Internal error while processing ID card document."
        )
    finally:
        for tp in temp_paths:
            try:
                if tp.exists():
                    tp.unlink()
            except OSError:
                pass


def create_app() -> FastAPI:
    application = FastAPI(
        title="Vietnam ID Card OCR & Validation API",
        version="1.0.0",
    )
    application.include_router(router)
    return application


app = create_app()


# ==============================================================================
# 12. CLI RUNNER & SELF-VERIFICATION SUITE
# ==============================================================================

if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser(
        description="Vietnam ID Card OCR & Validation CLI"
    )
    parser.add_argument(
        "files",
        nargs="*",
        help="Paths to ID card images (front/back) or multi-page PDF",
    )
    parser.add_argument(
        "--no-ocr-text",
        action="store_true",
        help="Omit raw merged ocr_text from JSON output",
    )
    parser.add_argument(
        "--self-test",
        action="store_true",
        help="Run built-in unit tests (MRZ checksums, fuzzy matching, regex, status logic)",
    )
    parser.add_argument(
        "--out-dir",
        default=".",
        help="Directory to export <id_number>.json (default: current directory)",
    )
    parser.add_argument(
        "--no-save",
        action="store_true",
        help="Do not write output JSON file to disk",
    )
    args = parser.parse_args()

    if args.self_test:
        # Unit test 1: TD1 MRZ checksum validation
        sample_mrz = (
         "IDVNM2000123457001200012345<<8\n"
         "0001018M3001019VNM<<<<<<<<<<<8\n"
         "NGUYEN<<VAN<A<<<<<<<<<<<<<<<<<"
     )
        parsed = parse_and_validate_mrz(sample_mrz)
        assert parsed["mrz"]["valid_format"] is True, "TD1 format check failed"
        assert parsed["mrz"]["checksum_valid"] is True, "TD1 checksum check failed"

        # Unit test 2: Accent-insensitive fuzzy matching
        fm = fuzzy_match_keyword("SO DINH DANH CA NHAN", ["Số định danh cá nhân"])
        assert fm["matched"] is True, "Fuzzy accent-insensitive match failed"

        # Unit test 3: Side & generation classification
        cls_old_front = classify_id_card_side(
            "CĂN CƯỚC CÔNG DÂN Số / No.: 001200012345 Họ và tên: NGUYỄN VĂN A Quê quán: Hà Nội Nơi thường trú: Hà Nội"
        )
        assert cls_old_front["document_type"] == "CITIZEN_ID_CARD"
        assert cls_old_front["side"] == "front"

        cls_new_back = classify_id_card_side(
            "Nơi cư trú: Hà Nội Nơi đăng ký khai sinh: Hà Nội Ngày, tháng, năm cấp: 01/01/2025 Ngày, tháng, năm hết hạn: 01/01/2035 BỘ CÔNG AN\n"
            + sample_mrz
        )
        assert cls_new_back["document_type"] == "IDENTITY_CARD"
        assert cls_new_back["side"] == "back"
        print("All built-in unit assertions passed successfully.")
        sys.exit(0)

    if not args.files:
        parser.print_help()
        sys.exit(1)

    save_dir = None if args.no_save else args.out_dir
    output = check_id_card(
        args.files,
        include_ocr_text=not args.no_ocr_text,
        save_json_dir=save_dir,
    )
    print(json.dumps(output, ensure_ascii=False, indent=2))
    if not args.no_save and output.get("exported_json_path"):
        print(f"\n[SAVED] Da xuat ket qua ra file: {output['exported_json_path']}", file=sys.stderr)