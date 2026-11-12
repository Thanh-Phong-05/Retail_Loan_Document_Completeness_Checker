import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).parent.parent))
import pytest
from classifier import classify_text, load_config, normalize, classify_file

CFG = load_config()


def test_normalize_vietnamese():
    assert normalize("CĂN CƯỚC CÔNG DÂN") == "can cuoc cong dan"


@pytest.mark.parametrize("text,expected", [
    ("CỘNG HÒA XÃ HỘI CHỦ NGHĨA VIỆT NAM Căn cước công dân Số định danh cá nhân 012345678901 Ngày sinh", "NATIONAL_ID"),
    ("HỢP ĐỒNG LAO ĐỘNG Người sử dụng lao động Người lao động Mức lương 20.000.000", "LABOR_CONTRACT"),
    ("SAO KÊ TÀI KHOẢN Số dư đầu kỳ Số dư cuối kỳ Ghi có lương tháng 08/2026", "BANK_STATEMENT"),
    ("ĐƠN ĐỀ NGHỊ VAY VỐN Số tiền vay 100.000.000 Mục đích vay tiêu dùng Người vay ký tên", "LOAN_APPLICATION"),
])
def test_four_classes(text, expected):
    assert classify_text(text, CFG)["documentClass"] == expected


def test_unrelated_page_is_unknown():
    assert classify_text("Thực đơn nhà hàng phở bò", CFG)["documentClass"] == "UNKNOWN"


def test_empty_page_is_unknown():
    assert classify_text("", CFG)["documentClass"] == "UNKNOWN"


def test_broken_file_is_reported_not_dropped(tmp_path):
    bad = tmp_path / "hong.pdf"
    bad.write_bytes(b"not a pdf")
    r = classify_file(bad, CFG)
    assert r["error"] is not None and r["pages"] == []
