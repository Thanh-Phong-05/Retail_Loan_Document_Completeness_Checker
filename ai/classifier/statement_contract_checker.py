import re
from dataclasses import dataclass, asdict
from typing import List

@dataclass
class CheckResult:
    status: str
    page_anchor: str
    details: str
    missing_months: List[str] = None

    def to_dict(self):
        return {k: v for k, v in asdict(self).items() if v is not None}

class StatementContractChecker:
    def __init__(self):
        self.month_regex = re.compile(r"(?:tháng\s*)?(\d{1,2})[\s/\-\.](\d{4})", re.IGNORECASE)
        self.signature_keywords = [
            "ký tên", "ky ten", "đóng dấu", "dong dau", 
            "chữ ký", "chu ky", "giám đốc", "giam doc", "người làm đơn"
        ]

    def _extract_months(self, text: str) -> List[tuple]:
        months = set()
        for match in self.month_regex.finditer(text):
            month, year = int(match.group(1)), int(match.group(2))
            if 1 <= month <= 12 and 2000 <= year <= 2030:
                months.add((year, month))
        return sorted(list(months))

    def check_statement(self, pages: List[dict]) -> dict:
        if not pages:
            return CheckResult("INVALID", "", "Không có dữ liệu trang sao kê").to_dict()

        all_text = " ".join([p.get("text", "") for p in pages])
        found_months = self._extract_months(all_text)

        if len(found_months) < 3:
            return CheckResult(
                status="PERIOD_GAP",
                page_anchor=pages[0].get("pageAnchor", "page_1"),
                details=f"Chỉ tìm thấy {len(found_months)} tháng. Không đủ 3 tháng.",
                missing_months=["Cần 3 tháng liên tiếp"]
            ).to_dict()

        gaps = []
        for i in range(len(found_months) - 1):
            curr_year, curr_month = found_months[i]
            next_year, next_month = found_months[i+1]
            
            month_diff = (next_year - curr_year) * 12 + (next_month - curr_month)
            if month_diff > 1:
                for m in range(1, month_diff):
                    gap_month = curr_month + m
                    gap_year = curr_year
                    if gap_month > 12:
                        gap_month -= 12
                        gap_year += 1
                    gaps.append(f"{gap_month:02d}/{gap_year}")

        if gaps:
            return CheckResult(
                status="PERIOD_GAP",
                page_anchor=pages[0].get("pageAnchor", "page_1"),
                details="Sao kê bị thiếu tháng liên tiếp.",
                missing_months=gaps
            ).to_dict()

        return CheckResult("VALID", pages[0].get("pageAnchor", "page_1"), "Đủ 3 tháng liên tiếp").to_dict()

    def check_contract(self, pages: List[dict]) -> dict:
        if not pages:
            return CheckResult("INVALID", "", "Không có dữ liệu trang hợp đồng").to_dict()

        last_page = pages[-1]
        text_lower = last_page.get("text", "").lower()

        has_sig = any(kw in text_lower for kw in self.signature_keywords)
        
        if not has_sig:
            return CheckResult(
                status="MISSING_SIGNATURE",
                page_anchor=last_page.get("pageAnchor", "page_end"),
                details="Không tìm thấy chữ ký hoặc dấu mộc ở trang cuối."
            ).to_dict()

        return CheckResult(
            status="VALID",
            page_anchor=last_page.get("pageAnchor", "page_end"),
            details="Đã có chữ ký/dấu mộc."
        ).to_dict()