# Hướng dẫn bắt buộc cho AI khi làm việc trong repository

Tài liệu này là quy trình làm việc chung cho mọi AI coding agent khi hỗ trợ dự án **Retail Loan Document Completeness Checker**. AI phải đọc và tuân thủ tài liệu này trước khi phân tích, sửa code, viết test hoặc đưa ra kết luận về một task.

## 1. Bối cảnh dự án

- Dự án: `Retail Loan Document Completeness Checker`.
- Người thực hiện Backend: **Đào Quốc Thanh**.
- Vai trò chính: Backend Developer.
- Công nghệ chính: Java, Spring Boot, Maven, Spring Data JPA/Hibernate, SQL Server, REST API, file storage và Camunda/Zeebe.
- Task Backend hiện tại thường liên quan đến Jira project `UNIT02` và các task/subtask như `SUB-104.3`.
- Báo cáo cá nhân không push lên GitHub nằm trong:

```text
private-notes/BAO_CAO_TIEN_DO_SUB-104.3_DAO_QUOC_THANH.md
```

Nếu task mới có Jira key khác, tạo hoặc dùng file báo cáo tương ứng theo mẫu:

```text
private-notes/BAO_CAO_TIEN_DO_<JIRA_KEY>_DAO_QUOC_THANH.md
```

Không ghi password, access token, API key, connection string có credential hoặc dữ liệu khách hàng thật vào repository, báo cáo hoặc log minh chứng.

## 2. Quy trình bắt buộc trước khi bắt đầu task

AI không được bắt đầu code ngay khi chỉ mới đọc tên task. Phải thực hiện các bước sau và báo lại kết quả ngắn gọn cho người dùng.

### Bước 1: Kiểm tra workspace và trạng thái repository

1. Xác nhận thư mục làm việc hiện tại.
2. Đọc `README.md`, `pom.xml`, `.gitignore`, `docs/` và các file hướng dẫn agent hiện có.
3. Kiểm tra branch, working tree và các thay đổi chưa commit.
4. Không xóa, reset hoặc ghi đè thay đổi hiện có của người dùng.
5. Xác định các file/class/module có khả năng bị ảnh hưởng bởi task.

Nếu working tree đang có thay đổi, AI phải phân biệt thay đổi có sẵn của người dùng với thay đổi do AI tạo ra trước khi chỉnh sửa.

### Bước 2: Đọc lại tài liệu dự án liên quan

AI phải tìm và đọc các tài liệu đã được cung cấp trong workspace hoặc repository trước khi lập kế hoạch. Các tài liệu thường có gồm:

- `UNIT_Vietnam_Retail_Loan_Document_Completeness_Checker_RFP.pdf`
- `Huong dan thuc hien HKDN.pdf`
- `HKDN_UNIT.docx`
- `README.md`
- `docs/api-contract.md`
- Các schema, tài liệu thiết kế, file JSON contract, BPMN hoặc tài liệu kỹ thuật liên quan.

Quy tắc đọc tài liệu:

- Chỉ đọc các tài liệu liên quan đến task nhưng phải kiểm tra đầy đủ danh sách tài liệu trước khi quyết định tài liệu nào liên quan.
- Nếu tài liệu nằm ở thư mục cha của repository, tìm trong workspace hiện tại và đọc nếu có quyền truy cập.
- Nếu không thể đọc PDF/DOCX hoặc file bị thiếu, phải nói rõ tài liệu nào chưa đọc được; không tự suy đoán nội dung.
- Ghi lại các yêu cầu, giới hạn, thuật ngữ và tiêu chí nghiệm thu lấy từ tài liệu.

### Bước 3: Đọc task Jira

AI phải đọc đầy đủ các thông tin Jira được cung cấp, gồm:

- Jira key, tiêu đề và mô tả.
- User story/acceptance criteria.
- Subtask, dependency, người phụ trách và trạng thái nếu có.
- Story point, sprint và deadline nếu có.
- Comment, attachment, linked issue và các ràng buộc kỹ thuật nếu người dùng cung cấp.

Nguồn Jira có thể là link, ảnh chụp màn hình, nội dung copy từ Jira hoặc kết nối Jira được người dùng cấp. AI không được tự nhận là đã truy cập tài khoản Jira nếu không có quyền/kết nối thực tế. Nếu thiếu mô tả hoặc acceptance criteria, phải nêu rõ phần còn thiếu và yêu cầu người dùng cung cấp trước khi chốt Plan.

### Bước 4: Đối chiếu task với codebase

AI phải xác định:

- Code hiện có đã đáp ứng được phần nào.
- Phần nào còn thiếu hoặc đang sai.
- Các caller, API consumer, entity, repository, service, configuration và test bị ảnh hưởng.
- Dependency bên ngoài như SQL Server, Camunda, OCR, Python AI, Frontend hoặc Docker.
- Rủi ro tương thích phiên bản và rủi ro dữ liệu.

Không được kết luận task đã hoàn thành chỉ dựa vào việc code biên dịch thành công.

## 3. Plan bắt buộc trước khi triển khai

Trước khi sửa code, AI phải trình bày một Plan có cấu trúc sau:

### 3.1. Task này là gì

- Viết lại mục tiêu bằng ngôn ngữ dễ hiểu.
- Nêu đầu vào, đầu ra và người/ hệ thống sử dụng kết quả.
- Liên hệ task với user story, RFP và các task phụ thuộc.

### 3.2. Tiêu chí hoàn thành

Liệt kê acceptance criteria có thể kiểm chứng. Mỗi tiêu chí cần có cách xác minh cụ thể bằng test, request/response, query database, log hoặc ảnh minh chứng.

### 3.3. Phạm vi và ngoài phạm vi

Nêu rõ file/module/API nào sẽ thay đổi và phần nào không làm trong task này. Không tự mở rộng sang feature khác nếu Jira chưa yêu cầu.

### 3.4. Hướng giải quyết kỹ thuật

Mô tả kiến trúc, luồng dữ liệu, thay đổi database/API, validation, xử lý lỗi, bảo mật, transaction, dependency và phương án tương thích với code hiện có.

### 3.5. Quy trình triển khai từng bước

Mỗi bước phải có:

1. Mục tiêu.
2. File/class/config sẽ đọc hoặc sửa.
3. Kết quả cần đạt.
4. Cách kiểm tra sau bước đó.

### 3.6. Kế hoạch kiểm thử

Phải bao gồm khi phù hợp:

- Unit test.
- Integration/repository test.
- Controller/API test.
- Validation và error cases.
- Security boundary.
- Manual test bằng Postman/Swagger.
- Kiểm tra database, file storage, audit log và message/workflow.
- Test với dependency thật hoặc ghi rõ dependency nào đang mock/tắt.

### 3.7. Rủi ro và phương án xử lý

Ghi rõ rủi ro về dữ liệu, bảo mật, tương thích dependency, migration, concurrency, rollback, môi trường và các phần chưa thể kiểm thử.

Nếu task có điểm mơ hồ làm thay đổi đáng kể hướng triển khai, AI phải dừng ở Plan và hỏi người dùng trước khi code.

## 4. Quy tắc khi triển khai

- Ưu tiên thay đổi nhỏ nhất nhưng hoàn chỉnh, phù hợp với cấu trúc hiện có.
- Dùng `apply_patch` khi chỉnh sửa file.
- Không tự ý chạy lệnh phá hủy như `git reset --hard`, `git checkout --`, xóa database hoặc xóa thư mục lớn.
- Sau khi task đã đạt acceptance criteria và hoàn tất kiểm thử, phải chuẩn bị commit và push các file code/tài liệu thuộc task lên đúng feature branch theo quy trình ở mục 6. Không push khi task chưa được kiểm tra hoặc còn lỗi chưa được ghi nhận.
- Không tự ý push dữ liệu nhạy cảm, `private-notes/`, `uploads/`, `target/` hoặc file local không thuộc task.
- Không thêm dependency chỉ để giải quyết vài dòng code.
- Không hard-code credential hoặc dữ liệu nhạy cảm.
- Giữ validation ở boundary, xử lý lỗi và cleanup dữ liệu/file khi có thể phát sinh dữ liệu mồ côi.
- Khi sửa một method hoặc contract, phải tìm caller, test, DTO và tài liệu liên quan.
- Nếu phát hiện lỗi ngoài phạm vi task, ghi nhận thành risk hoặc follow-up thay vì âm thầm mở rộng phạm vi.

## 5. Quy trình cập nhật báo cáo tiến độ sau mỗi task

Sau khi hoàn thành một phần việc có thể kiểm chứng, AI phải cập nhật file báo cáo cá nhân trong `private-notes/`. Không đợi đến cuối kỳ mới ghi lại vì sẽ mất minh chứng và số liệu.

Mỗi task phải có các mục sau:

### 5.1. Thông tin task

- Jira key, tiêu đề, sprint, người thực hiện, branch.
- Thời gian thực hiện và trạng thái trước/sau.
- Liên kết hoặc nguồn thông tin Jira đã sử dụng.

### 5.2. Yêu cầu và Plan

- Task yêu cầu gì.
- Acceptance criteria.
- Phạm vi, ngoài phạm vi và dependency.
- Plan đã thống nhất trước khi code.

### 5.3. Công việc đã thực hiện

- File/class/config/schema đã thay đổi.
- Quyết định kỹ thuật quan trọng và lý do.
- Các lệnh cài đặt/chạy cần thiết, nhưng không ghi credential.
- Dữ liệu mẫu hoặc request mẫu đã dùng.

### 5.4. Kiểm thử và minh chứng

Với mỗi test case, ghi:

- Mã test.
- Mục tiêu và dữ liệu đầu vào.
- Lệnh/request đã chạy.
- Kết quả mong đợi.
- Kết quả thực tế.
- HTTP status, số lượng bản ghi, hash, thời gian hoặc số liệu liên quan nếu có.
- Trạng thái `Pass`, `Fail`, `Blocked` hoặc `Not run`.
- Đường dẫn ảnh/log/query minh chứng nếu có.

Không ghi `Pass` nếu mới chỉ suy luận mà chưa có bằng chứng kiểm tra.

### 5.5. Khó khăn và cách giải quyết

Mỗi lỗi cần ghi theo mẫu:

```markdown
### Vấn đề: <mô tả ngắn>

- Triệu chứng:
- Nguyên nhân:
- Cách kiểm tra nguyên nhân:
- Cách giải quyết:
- Kết quả sau khi sửa:
- Rủi ro hoặc việc còn lại:
```

### 5.6. Kinh nghiệm và việc tiếp theo

- Kinh nghiệm kỹ thuật.
- Kinh nghiệm làm việc với Jira/GitHub/đồng đội.
- Điều nên làm tốt hơn ở task sau.
- Follow-up, risk, dependency chưa hoàn thành.
- Điều kiện để chuyển task sang Done.

### 5.7. Ảnh minh chứng

Nếu ảnh được cung cấp trong hội thoại nhưng không có file cục bộ, AI phải ghi danh mục ảnh cần chèn và chú thích ảnh trong báo cáo. Không tạo đường dẫn giả. Nếu người dùng cung cấp file ảnh cục bộ, ưu tiên lưu trong thư mục private bị ignore hoặc tham chiếu bằng đường dẫn tương đối.

## 6. Quy trình kết thúc task

Trước khi báo hoàn thành, AI phải thực hiện checklist:

1. Đối chiếu từng acceptance criteria.
2. Chạy test tự động phù hợp.
3. Chạy manual test nếu task có API/UI/integration.
4. Kiểm tra log, database, file storage hoặc workflow nếu task có side effect.
5. Kiểm tra không có secret trong diff, log, báo cáo và file mẫu.
6. Kiểm tra tài liệu API/schema/README nếu contract đã thay đổi.
7. Cập nhật báo cáo cá nhân.
8. Kiểm tra `private-notes/` vẫn bị ignore:

```powershell
git check-ignore -v private-notes/
```

9. Xác nhận GitHub handoff:

   - Backend dùng branch theo quy ước `feature/be-<JIRA_KEY>-<short-name>`.
   - Frontend dùng `feature/fe-<JIRA_KEY>-<short-name>`.
   - AI/OCR dùng `feature/ai-<JIRA_KEY>-<short-name>`.
   - `develop` là nhánh tích hợp; `main` là nhánh chính thức.
   - Không push trực tiếp vào `main` hoặc `develop` nếu chưa có quy định/review của nhóm.
   - Kiểm tra `git status`, `git diff`, secret, file ignored và chỉ stage các file thuộc task.
   - Tạo commit có message chứa Jira key, ví dụ:

```powershell
git add <cac-file-thuoc-task>
git commit -m "[SUB-104.3] Implement dossier REST APIs"
git push -u origin <feature-branch>
```

   - Sau khi push, cập nhật Jira bằng commit/branch/Pull Request và kết quả kiểm thử. Nếu nhóm dùng Pull Request, target mặc định là `develop`.
   - Nếu branch hiện tại không đúng quy ước, không tự ý đổi tên hoặc push nhầm branch; phải báo người dùng/nhóm để xác nhận branch đích trước.

10. Báo cáo rõ:

- Đã hoàn thành gì.
- Test nào pass/fail/not run.
- Bằng chứng chính.
- Rủi ro còn lại.
- Việc tiếp theo.

## 7. Mẫu Plan cho task mới

AI nên bắt đầu mỗi task bằng mẫu ngắn sau sau khi đã đọc tài liệu:

```markdown
## Plan triển khai <JIRA_KEY>

### 1. Task là gì

### 2. Tài liệu/Jira đã đọc

### 3. Acceptance criteria

### 4. Phạm vi và ngoài phạm vi

### 5. Hướng giải quyết

### 6. Các bước triển khai

### 7. Kế hoạch kiểm thử

### 8. Rủi ro và dependency

### 9. Minh chứng cần thu thập

### 10. Tiêu chí kết thúc
```

## 8. Mẫu cập nhật báo cáo cho task mới

```markdown
## Nhật ký task <JIRA_KEY> - <Tên task>

### Thông tin chung

### Yêu cầu và acceptance criteria

### Plan đã thống nhất

### Công việc đã thực hiện

### Kiểm thử và kết quả

### Khó khăn và cách giải quyết

### Minh chứng

### Kinh nghiệm rút ra

### Trạng thái và việc tiếp theo
```

AI phải giữ lại lịch sử các task cũ, không xóa minh chứng cũ khi cập nhật báo cáo. Nếu thông tin chưa được kiểm chứng, ghi rõ `Chưa xác minh` hoặc `Chưa chạy` thay vì điền theo suy đoán.
