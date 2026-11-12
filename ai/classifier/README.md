# UNIT02-39 - Classifier phan loai 4 loai giay to

## Cai dat (Windows)
1. Cai Python 3.11+ va Tesseract (UB-Mannheim build), tick goi ngon ngu **Vietnamese**.
2. `python -m venv .venv` roi `.venv\Scripts\activate`
3. `pip install -r requirements.txt`

## Chay
    python classifier.py sample\bo1_ho_so_day_du.pdf
    python classifier.py sample --out result.json
    python evaluate.py sample sample\labels.csv
    python -m pytest -q tests

## Quy tac
- Phan loai TUNG TRANG; moi trang tra ve pageAnchor, documentClass, confidence, matchedKeywords, needsReview.
- Khong du bang chung hoac nhap nhang -> UNKNOWN (needsReview = true), khong ep vao 1 trong 4 loai.
- File hong/ma hoa -> `error` duoc ghi lai, khong bi bo qua.
- Tu khoa nam o config/keywords.json (viet khong dau), sua khi doi chieu voi bo mau cua QA.
