# Kết quả kiểm thử DMN - Đánh giá tính đầy đủ hồ sơ vay (`retail_loan_completeness.dmn`)

- **Decision ID:** `retail-loan-completeness-dmn`
- **Decision Name:** Đánh giá tính đầy đủ hồ sơ vay
- **Hit Policy:** `FIRST`
- **Môi trường kiểm thử:** Camunda 8.10.9 (`c8run` local)

| Test Case | Decision Instance Key | `nationalIdStatus` | `laborContractStatus` | `bankStatementStatus` | `loanApplicationStatus` | Rule khớp | Kết quả thực tế (`completenessStatus`) | Trạng thái |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **TC-01** | `2251799813687362-1` | `MISSING_BACK_SIDE` | `VALID` | `VALID` | `VALID` | Rule 1 | `INCOMPLETE` | PASS |
| **TC-02** | `2251799813687514-1` | `VALID` | `MISSING_SIGNATURE` | `VALID` | `VALID` | Rule 6 | `REVIEW_NEEDED` | PASS |
| **TC-03** | `2251799813687554-1` | `VALID` | `VALID` | `VALID` | `VALID` | Rule 9 | `READY_FOR_CREDIT_REVIEW` | PASS |