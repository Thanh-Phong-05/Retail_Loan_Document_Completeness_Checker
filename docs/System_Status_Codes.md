# BẢNG QUY ƯỚC MÃ CHUẨN HỆ THỐNG (`System_Status_Codes.md`)
**Dự án:** Retail Loan Document Completeness Checker (BANK-AI-07)  
**Mã Jira:** UNIT02-27 (Thuộc Epic/US: UNIT02-19 / [US-102])  
**Phạm vi áp dụng:** Đồng bộ từ khóa tiếng Anh cố định giữa các team **AI/OCR**, **Backend**, **Camunda Workflow** và **Frontend**.

---

## 1. Mã phân loại 4 nhóm giấy tờ (`documentClass`)
Sử dụng trong đầu ra của module AI Classifier (`classifier.py`), cột `document_type` trong Database và điều kiện kiểm tra trên DMN:

| Mã quy ước (`documentClass`) | Tên tiếng Việt | Quy tắc nhận diện & Yêu cầu nghiệp vụ |
| :--- | :--- | :--- |
| `NATIONAL_ID` | Căn cước công dân / CMND | Bắt buộc đầy đủ cả 2 mặt trước và mặt sau, rõ nét, không rách góc. |
| `LABOR_CONTRACT` | Hợp đồng lao động | Còn thời hạn hiệu lực tại thời điểm nộp, có đầy đủ dấu mộc đỏ và chữ ký công ty. |
| `BANK_STATEMENT` | Sao kê lương ngân hàng | Đủ kỳ sao kê **03 tháng gần nhất** tính đến ngày nộp hồ sơ. |
| `LOAN_APPLICATION` | Đơn đề nghị vay vốn | Điền đầy đủ các trường thông tin bắt buộc và có chữ ký người vay ở trang cuối. |

---

## 2. Mã trạng thái từng tệp chứng từ (`fileStatus`)
Sử dụng để gắn nhãn kết quả kiểm tra từng file/trang tài liệu sau khi qua bước AI/OCR:

| Mã trạng thái (`fileStatus`) | Mức độ | Mô tả chi tiết điều kiện | Hành động xử lý của hệ thống |
| :--- | :--- | :--- | :--- |
| `VALID` | Đạt chuẩn | Chứng từ hợp lệ, đầy đủ nội dung, hình ảnh rõ nét đạt ngưỡng tin cậy. | Cho phép đi tiếp qua bước tổng hợp đánh giá DMN. |
| `MISSING_BACK_SIDE` | Lỗi thiếu | Thiếu ảnh chụp mặt sau của CCCD/CMND (`is_complete = false`). | Đánh dấu thiếu thành phần giấy tờ $\rightarrow$ đẩy về `INCOMPLETE`. |
| `PERIOD_GAP` | Lỗi thiếu | Sao kê lương bị khuyết kỳ hạn, không đủ 03 tháng liên tiếp gần nhất. | Đánh dấu thiếu thành phần giấy tờ $\rightarrow$ đẩy về `INCOMPLETE`. |
| `BLURRY` | Cảnh báo | Ảnh chụp bị mờ, lóa sáng, nghiêng lệch khiến AI không đọc chắc chắn nội dung. | Gắn cờ `needsReview = true` $\rightarrow$ đẩy sang `REVIEW_NEEDED`. |
| `MISSING_SIGNATURE` | Cảnh báo | Thiếu chữ ký người vay (trên Đơn vay) hoặc thiếu dấu mộc/chữ ký công ty (trên HĐLĐ). | Gắn cờ `needsReview = true` $\rightarrow$ đẩy sang `REVIEW_NEEDED`. |

---

## 3. Mã tổng kết trạng thái hồ sơ (`completenessStatus`)
Sử dụng làm đầu ra của bảng quyết định DMN (`retail_loan_completeness.dmn`), trạng thái lưu bảng `completeness_results` và hiển thị trên Dashboard Frontend:

| Mã tổng kết (`completenessStatus`) | Trạng thái hiển thị | Điều kiện kích hoạt | Luồng xử lý tiếp theo |
| :--- | :--- | :--- | :--- |
| `INCOMPLETE` | Hồ sơ chưa đầy đủ | Thiếu ít nhất 1 trong 4 nhóm giấy tờ hoặc phát sinh lỗi `MISSING_BACK_SIDE`, `PERIOD_GAP`. | Kích hoạt luồng thông báo yêu cầu khách hàng bổ sung chứng từ (tạo phiên bản mới). |
| `REVIEW_NEEDED` | Cần cán bộ rà soát | Đủ 4 nhóm giấy tờ nhưng có ít nhất 1 file ở trạng thái `BLURRY` hoặc `MISSING_SIGNATURE`. | Đẩy vào hàng đợi Tasklist để Cán bộ tín dụng kiểm tra trên màn hình Review Workspace. |
| `READY_FOR_CREDIT_REVIEW` | Sẵn sàng thẩm định tín dụng | Toàn bộ 4 nhóm chứng từ đều đạt `VALID` và đã được Cán bộ tín dụng bấm nút **"Xác nhận"** bàn giao (Controlled Handoff). | Ghi nhận nhật ký vào bảng `AuditLogs` và chuyển hồ sơ sang bộ phận Thẩm định tín dụng. |

---

## 4. Ví dụ JSON chuẩn hóa trao đổi giữa các Service
```json
{
  "dossierId": "DOS-20261115-001",
  "versionId": "VER-01",
  "completenessStatus": "REVIEW_NEEDED",
  "documents": [
    {
      "documentClass": "NATIONAL_ID",
      "fileStatus": "VALID",
      "pageAnchor": 1
    },
    {
      "documentClass": "BANK_STATEMENT",
      "fileStatus": "PERIOD_GAP",
      "pageAnchor": 2
    },
    {
      "documentClass": "LABOR_CONTRACT",
      "fileStatus": "MISSING_SIGNATURE",
      "pageAnchor": 4
    },
    {
      "documentClass": "LOAN_APPLICATION",
      "fileStatus": "VALID",
      "pageAnchor": 5
    }
  ]
}
