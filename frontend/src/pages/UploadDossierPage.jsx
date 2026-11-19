import { useRef, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import {
  AlertCircle,
  ArrowRight,
  Check,
  CheckCircle2,
  ChevronRight,
  Clock3,
  CloudUpload,
  FileText,
  FolderOpen,
  Sparkles,
  UserRound,
  X,
} from "lucide-react";
import AppLayout from "../components/AppLayout";
import ProgressStepper from "../components/ProgressStepper";
import { PRODUCT_TYPES } from "../constants/statusCodes";
import { useAuth } from "../context/AuthContext";
import { useDossiers } from "../context/DossierContext";

const MAX_FILES = 20;
const MAX_FILE_BYTES = 10 * 1024 * 1024; // 10MB
const ACCEPTED_EXTENSIONS = ["pdf", "jpg", "jpeg", "png"];

const BEFORE_SUBMIT_ITEMS = [
  {
    title: "CCCD / CMND",
    desc: "Đủ 2 mặt (trước/sau), rõ số và không rách góc.",
  },
  {
    title: "Hợp đồng lao động",
    desc: "Còn hiệu lực, có dấu mộc và chữ ký công ty.",
  },
  {
    title: "Sao kê lương",
    desc: "Đủ 3 tháng gần nhất tính đến ngày nộp hồ sơ.",
  },
  {
    title: "Đơn đăng ký vay",
    desc: "Đầy đủ thông tin và có chữ ký người vay ở trang cuối.",
  },
];

function formatBytes(bytes) {
  if (!bytes) return "0 KB";
  const mb = bytes / (1024 * 1024);
  if (mb >= 0.1) return `${mb.toFixed(1).replace(".", ",")} MB`;
  return `${Math.max(1, Math.round(bytes / 1024))} KB`;
}

function guessLabelFromName(name = "") {
  const l = name.toLowerCase();
  if (l.includes("cccd") || l.includes("cmnd")) return "CCCD 2 mặt";
  if (l.includes("hop_dong") || l.includes("hdld")) return "Hợp đồng lao động";
  if (l.includes("sao_ke") || l.includes("saoke")) return "Sao kê lương";
  if (l.includes("don_") || l.includes("loan")) return "Đơn đăng ký vay";
  return "Chứng từ đính kèm";
}

export default function UploadDossierPage() {
  const { user } = useAuth();
  const { draftLoan, setDraftLoan, getDossier, submitUpload } = useDossiers();
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const fileInputRef = useRef(null);

  const queryAppId = searchParams.get("applicationId");
  const isSupplementMode =
    searchParams.get("mode") === "supplement" || Boolean(queryAppId);
  const existingDossier = queryAppId ? getDossier(queryAppId) : null;

  const isOfficer = user?.role === "OFFICER";

  const [applicationId, setApplicationId] = useState(
    queryAppId || draftLoan.applicationId || "APP-2026-007"
  );
  const [applicantName, setApplicantName] = useState(
    existingDossier?.applicantName ||
      draftLoan.applicantName ||
      (isOfficer ? "Nguyễn Văn A" : user?.fullName || "Nguyễn Văn A")
  );
  const [files, setFiles] = useState([]);
  const [dragActive, setDragActive] = useState(false);
  const [error, setError] = useState("");
  const [draftSavedNotice, setDraftSavedNotice] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [uploadedResult, setUploadedResult] = useState(null);

  const totalBytes = files.reduce((sum, f) => sum + (f.size || 0), 0);

  const addValidatedFiles = (incomingList) => {
    setError("");
    setDraftSavedNotice(false);
    const incoming = Array.from(incomingList || []);
    if (incoming.length === 0) return;

    if (files.length + incoming.length > MAX_FILES) {
      setError(`Tối đa chỉ được đính kèm ${MAX_FILES} tệp cho mỗi phiên bản.`);
      return;
    }

    const validToAdd = [];
    for (const file of incoming) {
      const ext = file.name.split(".").pop()?.toLowerCase() || "";
      if (!ACCEPTED_EXTENSIONS.includes(ext)) {
        setError(
          `Tệp "${file.name}" không đúng định dạng. Chỉ chấp nhận PDF, JPG, JPEG hoặc PNG.`
        );
        return;
      }
      if (file.size > MAX_FILE_BYTES) {
        setError(
          `Tệp "${file.name}" vượt quá giới hạn 10 MB/tệp theo quy định.`
        );
        return;
      }
      validToAdd.push(file);
    }

    setFiles((prev) => [...prev, ...validToAdd]);
  };

  const handleDrop = (e) => {
    e.preventDefault();
    setDragActive(false);
    addValidatedFiles(e.dataTransfer.files);
  };

  const handleRemoveFile = (index) => {
    setFiles((prev) => prev.filter((_, idx) => idx !== index));
  };

  const handleAddSampleBundle = () => {
    const mk = (name, text, type = "application/pdf") =>
      new File([text], name, { type });
    const samples = [
      mk("cccd_nguyen_van_a.pdf", "Sample CCCD front and back"),
      mk("hop_dong_lao_dong.pdf", "Sample labor contract with stamp"),
      mk("sao_ke_luong_3_thang.pdf", "Sample 3 months salary statement"),
      mk("don_de_nghi_vay_von.pdf", "Sample signed loan application"),
    ];
    setFiles(samples);
    setError("");
  };

  const handleSaveDraft = () => {
    setDraftLoan({
      ...draftLoan,
      applicationId: applicationId.trim() || draftLoan.applicationId,
      applicantName: applicantName.trim() || draftLoan.applicantName,
      productType: PRODUCT_TYPES.UNSECURED_SALARIED_LOAN,
    });
    setDraftSavedNotice(true);
  };

  const handleSubmit = async (e) => {
    e.preventDefault();
    setError("");
    setDraftSavedNotice(false);

    const cleanId = applicationId.trim();
    if (!cleanId || !/^[A-Za-z0-9_-]{1,50}$/.test(cleanId)) {
      setError(
        "Mã hồ sơ không hợp lệ (chỉ gồm chữ cái, số, '_' hoặc '-', tối đa 50 ký tự)."
      );
      return;
    }
    if (!applicantName.trim()) {
      setError("Vui lòng nhập họ và tên người vay.");
      return;
    }
    if (files.length === 0) {
      setError("Vui lòng đính kèm ít nhất 1 tệp chứng từ trước khi gửi.");
      return;
    }

    setSubmitting(true);
    try {
      const created = await submitUpload({
        applicationId: cleanId,
        productType: PRODUCT_TYPES.UNSECURED_SALARIED_LOAN,
        applicantName: applicantName.trim(),
        loanAmount: existingDossier?.loanAmount || draftLoan.loanAmount,
        branch: existingDossier?.branch,
        files,
      });
      setUploadedResult(created);
    } catch (err) {
      setError(
        err?.response?.data?.message ||
          err?.message ||
          "Không thể gửi hồ sơ. Vui lòng kiểm tra lại kết nối Backend."
      );
    } finally {
      setSubmitting(false);
    }
  };

  const branchDisplay =
    existingDossier?.branch ||
    (draftLoan.termAndBranch?.includes("·")
      ? draftLoan.termAndBranch.split("·")[1].replace("CN", "").trim()
      : "TP. Hồ Chí Minh");

  return (
    <AppLayout>
      <div className="flex flex-col gap-[26px] w-full max-w-[1600px] mx-auto">
        {/* Breadcrumbs */}
        <nav className="flex items-center gap-2 text-[12px] text-[#64748B]">
          <span>Hồ sơ vay</span>
          <ChevronRight className="size-3" />
          <span>{applicationId || "APP-2026-001"}</span>
          <ChevronRight className="size-3" />
          <span className="font-medium text-[#0F172A]">
            {isSupplementMode ? "Nộp bổ sung hồ sơ" : "Tải hồ sơ"}
          </span>
        </nav>

        {/* Page Heading */}
        <div className="flex flex-wrap items-center justify-between gap-4">
          <div className="flex flex-col gap-1.5">
            <h1 className="text-[28px] sm:text-[30px] font-normal tracking-[-0.8px] text-[#0F172A]">
              {isSupplementMode
                ? `Nộp Bổ Sung Hồ Sơ ${applicationId}`
                : "Tải Hồ Sơ Vay"}
            </h1>
            <p className="text-[14px] text-[#64748B]">
              Thêm thông tin người vay và hồ sơ đính kèm để bắt đầu xác minh.
            </p>
          </div>
          <div className="inline-flex items-center gap-2 rounded-full bg-white border border-[#E2E8F0] px-3 py-1.5 text-[12px] font-medium text-[#475569]">
            <span className="size-1.5 rounded-full bg-[#64748B]" />
            <span>
              {isSupplementMode
                ? `Phiên bản mới v${(existingDossier?.version || 1) + 1}`
                : "Hồ sơ nháp"}
            </span>
          </div>
        </div>

        {/* Stepper */}
        <ProgressStepper currentStep={2} />

        {isSupplementMode && (
          <div className="rounded-[10px] border border-[#FDE68A] bg-[#FFFBEB] px-4 py-3 flex items-start gap-3 text-[12px] text-[#92400E]">
            <AlertCircle className="size-4 shrink-0 mt-0.5 text-[#B45309]" />
            <div>
              <strong>Chế độ nộp bổ sung (Tạo phiên bản mới):</strong> Mã hồ sơ{" "}
              <span className="font-semibold">{applicationId}</span> đã được
              khóa cố định. Vui lòng tải lại đầy đủ bộ 4 chứng từ đã bổ sung/sửa
              lỗi để hệ thống AI &amp; DMN chấm điểm lại toàn diện cho phiên bản
              mới.
            </div>
          </div>
        )}

        {uploadedResult ? (
          <div className="bg-white border border-[#BBF7D0] rounded-[12px] p-7 flex flex-col gap-5">
            <div className="flex items-start gap-3">
              <CheckCircle2 className="size-6 text-[#15803D] shrink-0 mt-0.5" />
              <div>
                <h2 className="text-[18px] font-semibold text-[#0F172A]">
                  Đã tiếp nhận hồ sơ {uploadedResult.applicationId} (Phiên bản v
                  {uploadedResult.version})
                </h2>
                <p className="text-[13px] text-[#64748B] mt-1">
                  Hệ thống đã lưu tệp và tạo mã băm SHA-256 kiểm toàn vẹn cho{" "}
                  <strong>{uploadedResult.documents?.length || 0}</strong> chứng
                  từ.
                </p>
              </div>
            </div>

            <div className="flex flex-col gap-2">
              {(uploadedResult.documents || []).map((doc) => (
                <div
                  key={doc.fileId}
                  className="border border-[#E2E8F0] rounded-[8px] px-3.5 py-2.5 flex flex-wrap items-center justify-between gap-2 text-[12px] bg-[#F8FAFC]"
                >
                  <div className="font-medium text-[#0F172A]">
                    {doc.fileName}{" "}
                    <span className="text-[#0284C7] font-semibold">
                      ({doc.docType})
                    </span>
                  </div>
                  <div className="font-mono text-[11px] text-[#64748B] truncate max-w-[360px]">
                    SHA-256: {doc.fileHashSha256}
                  </div>
                </div>
              ))}
            </div>

            <div className="flex flex-wrap items-center gap-3 pt-2">
              {isOfficer ? (
                <button
                  type="button"
                  onClick={() =>
                    navigate(`/admin/review/${uploadedResult.applicationId}`)
                  }
                  className="h-[42px] px-4 rounded-[8px] bg-[#0284C7] hover:bg-[#0369A1] text-[13px] font-semibold text-white inline-flex items-center gap-2"
                >
                  <span>Mở trong Review Workspace</span>
                  <ArrowRight className="size-4" />
                </button>
              ) : (
                <button
                  type="button"
                  onClick={() => navigate("/customer/my-dossiers")}
                  className="h-[42px] px-4 rounded-[8px] bg-[#0284C7] hover:bg-[#0369A1] text-[13px] font-semibold text-white inline-flex items-center gap-2"
                >
                  <span>Xem trong Hồ sơ vay của tôi</span>
                  <ArrowRight className="size-4" />
                </button>
              )}

              <button
                type="button"
                onClick={() => {
                  setUploadedResult(null);
                  setFiles([]);
                }}
                className="h-[42px] px-4 rounded-[8px] border border-[#E2E8F0] bg-white text-[13px] font-medium text-[#475569] hover:bg-[#F8FAFC]"
              >
                Tải thêm hồ sơ khác
              </button>
            </div>
          </div>
        ) : (
          /* Two Column Workspace */
          <div className="grid grid-cols-1 xl:grid-cols-[1fr_340px] gap-6 items-start">
            {/* Left Upload Form */}
            <form
              onSubmit={handleSubmit}
              className="bg-white border border-[#E2E8F0] rounded-[12px] overflow-hidden"
            >
              <div className="px-7 py-[23px] border-b border-[#E2E8F0] flex items-center justify-between gap-4">
                <div>
                  <h2 className="text-[17px] font-semibold text-[#0F172A]">
                    Người vay &amp; hồ sơ
                  </h2>
                  <p className="text-[12px] text-[#64748B] mt-1">
                    Tất cả hồ sơ bắt buộc, tập hợp tại một nơi.
                  </p>
                </div>
                <span className="text-[11px] text-[#64748B] shrink-0">
                  * Bắt buộc
                </span>
              </div>

              <div className="p-7 flex flex-col gap-6">
                {/* Application ID & Applicant Name */}
                <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                  <div className="flex flex-col gap-2">
                    <label
                      htmlFor="upload-applicant-name"
                      className="text-[13px] font-medium text-[#0F172A]"
                    >
                      Tên người vay <span className="text-[#0284C7]">*</span>
                    </label>
                    <div className="h-[46px] rounded-[6px] border border-[#CBD5E1] px-3.5 flex items-center gap-2.5 focus-within:border-[#0284C7]">
                      <UserRound className="size-[17px] text-[#64748B] shrink-0" />
                      <input
                        id="upload-applicant-name"
                        type="text"
                        value={applicantName}
                        onChange={(e) => setApplicantName(e.target.value)}
                        className="w-full text-[14px] text-[#0F172A] focus:outline-none bg-transparent"
                      />
                    </div>
                    <p className="text-[11px] text-[#64748B]">
                      Nhập đầy đủ họ và tên như trên giấy tờ tùy thân của người
                      vay.
                    </p>
                  </div>

                  <div className="flex flex-col gap-2">
                    <label
                      htmlFor="upload-app-id"
                      className="text-[13px] font-medium text-[#0F172A]"
                    >
                      Mã hồ sơ (Application ID){" "}
                      <span className="text-[#0284C7]">*</span>
                    </label>
                    <input
                      id="upload-app-id"
                      type="text"
                      readOnly={isSupplementMode}
                      value={applicationId}
                      onChange={(e) => setApplicationId(e.target.value)}
                      className={`h-[46px] rounded-[6px] border px-3.5 text-[14px] focus:outline-none ${
                        isSupplementMode
                          ? "bg-[#F8FAFC] border-[#E2E8F0] text-[#475569] cursor-not-allowed"
                          : "bg-white border-[#CBD5E1] text-[#0F172A] focus:border-[#0284C7]"
                      }`}
                    />
                    <p className="text-[11px] text-[#64748B]">
                      {isSupplementMode
                        ? "Khóa mã hồ sơ để tạo phiên bản bổ sung."
                        : "Gói vay: UNSECURED_SALARIED_LOAN (Vay theo lương)."}
                    </p>
                  </div>
                </div>

                {/* Drag & Drop Area */}
                <div className="flex flex-col gap-3">
                  <div className="flex flex-wrap items-center justify-between gap-2">
                    <label className="text-[13px] font-medium text-[#0F172A]">
                      Hồ sơ đính kèm <span className="text-[#0284C7]">*</span>
                    </label>
                    <button
                      type="button"
                      onClick={handleAddSampleBundle}
                      className="inline-flex items-center gap-1 text-[11px] font-medium text-[#0284C7] hover:underline"
                    >
                      <Sparkles className="size-3.5" />
                      <span>Thêm nhanh 4 tệp mẫu (Demo)</span>
                    </button>
                  </div>

                  <div
                    role="button"
                    tabIndex={0}
                    onClick={() => fileInputRef.current?.click()}
                    onKeyDown={(e) => {
                      if (e.key === "Enter" || e.key === " ") {
                        fileInputRef.current?.click();
                      }
                    }}
                    onDragOver={(e) => {
                      e.preventDefault();
                      setDragActive(true);
                    }}
                    onDragLeave={() => setDragActive(false)}
                    onDrop={handleDrop}
                    className={`h-[180px] rounded-[8px] border border-dashed flex flex-col items-center justify-center gap-3 px-4 text-center transition-colors cursor-pointer ${
                      dragActive
                        ? "bg-[#E0F2FE] border-[#0284C7]"
                        : "bg-[#F0F9FF] border-[#7DD3FC] hover:bg-[#E0F2FE]/60"
                    }`}
                  >
                    <div className="size-[44px] rounded-[8px] bg-white border border-[#BAE6FD] flex items-center justify-center">
                      <CloudUpload className="size-6 text-[#0284C7]" />
                    </div>
                    <div className="flex flex-col gap-1">
                      <p className="text-[14px] font-medium text-[#0F172A]">
                        Kéo thả tệp vào đây hoặc{" "}
                        <span className="font-semibold text-[#0284C7]">
                          chọn tệp
                        </span>
                      </p>
                      <p className="text-[11px] text-[#64748B]">
                        PDF, JPG, JPEG hoặc PNG · Tối đa 10 MB/tệp · Hỗ trợ tải
                        nhiều tệp (tối đa 20 tệp)
                      </p>
                    </div>
                    <input
                      ref={fileInputRef}
                      type="file"
                      multiple
                      accept=".pdf,.jpg,.jpeg,.png"
                      className="hidden"
                      onChange={(e) => {
                        addValidatedFiles(e.target.files);
                        e.target.value = "";
                      }}
                    />
                  </div>
                </div>

                {/* Attached Files List */}
                <div className="flex flex-col gap-2.5">
                  <div className="flex items-center justify-between text-[13px]">
                    <div className="flex items-center gap-2">
                      <span className="font-semibold text-[#0F172A]">
                        Tệp đã đính kèm
                      </span>
                      <span className="bg-[#F8FAFC] border border-[#E2E8F0] rounded-[4px] px-1.5 py-0.5 text-[10px] font-semibold text-[#475569]">
                        {files.length}
                      </span>
                    </div>
                    <span className="text-[11px] text-[#64748B]">
                      Tổng {formatBytes(totalBytes)}
                    </span>
                  </div>

                  {files.length === 0 ? (
                    <div className="border border-[#E2E8F0] rounded-[8px] px-4 py-6 text-center text-[12px] text-[#64748B] bg-[#F8FAFC]">
                      Chưa có tệp nào được chọn. Kéo thả tệp vào khung phía trên
                      hoặc bấm &ldquo;Thêm nhanh 4 tệp mẫu (Demo)&rdquo;.
                    </div>
                  ) : (
                    <div className="flex flex-col gap-2.5">
                      {files.map((file, idx) => {
                        const ext =
                          file.name.split(".").pop()?.toUpperCase() || "PDF";
                        return (
                          <div
                            key={`${file.name}-${idx}`}
                            className="bg-white border border-[#E2E8F0] rounded-[8px] p-3 flex items-center gap-3"
                          >
                            <div className="w-9 h-10 rounded-[5px] bg-[#F8FAFC] flex flex-col items-center justify-center gap-0.5 shrink-0">
                              <FileText className="size-4 text-[#64748B]" />
                              <span className="text-[8px] font-medium text-[#64748B]">
                                {ext}
                              </span>
                            </div>

                            <div className="flex-1 min-w-0">
                              <div className="text-[12px] font-medium text-[#0F172A] truncate">
                                {file.name}
                              </div>
                              <div className="text-[11px] text-[#64748B] mt-0.5">
                                {formatBytes(file.size)} ·{" "}
                                {guessLabelFromName(file.name)}
                              </div>
                            </div>

                            <div className="inline-flex items-center gap-1.5 text-[11px] text-[#15803D] shrink-0">
                              <CheckCircle2 className="size-4" />
                              <span>Đã tải lên</span>
                            </div>

                            <button
                              type="button"
                              onClick={() => handleRemoveFile(idx)}
                              className="p-1 text-[#64748B] hover:text-[#DC2626] rounded"
                              title="Xóa tệp"
                            >
                              <X className="size-4" />
                            </button>
                          </div>
                        );
                      })}
                    </div>
                  )}
                </div>

                {draftSavedNotice && (
                  <p className="text-[12px] font-medium text-[#15803D]">
                    Đã lưu nháp thông tin hồ sơ {applicationId}.
                  </p>
                )}

                {error && (
                  <p className="text-[12px] font-medium text-[#DC2626]">
                    {error}
                  </p>
                )}
              </div>

              {/* Form Actions */}
              <div className="px-7 py-5 border-t border-[#E2E8F0] flex items-center justify-between gap-4">
                <button
                  type="button"
                  onClick={handleSaveDraft}
                  className="h-[44px] px-4 rounded-[6px] border border-[#E2E8F0] bg-white hover:bg-[#F8FAFC] text-[12px] font-semibold text-[#475569] transition-colors"
                >
                  Lưu nháp
                </button>
                <button
                  type="submit"
                  disabled={submitting}
                  className="h-[44px] px-[18px] rounded-[6px] bg-[#0284C7] hover:bg-[#0369A1] text-[12px] font-semibold text-white inline-flex items-center gap-2 transition-colors disabled:opacity-60"
                >
                  <span>{submitting ? "Đang gửi kiểm tra..." : "Gửi kiểm tra"}</span>
                  <ArrowRight className="size-4" />
                </button>
              </div>
            </form>

            {/* Right Guidance Column */}
            <aside className="flex flex-col gap-5">
              <div className="bg-white border border-[#E2E8F0] rounded-[12px] p-[22px] flex flex-col gap-5">
                <div className="flex items-center gap-2">
                  <FolderOpen className="size-[18px] text-[#0284C7]" />
                  <h3 className="text-[14px] font-semibold text-[#0F172A]">
                    Tóm tắt hồ sơ
                  </h3>
                </div>

                <div className="flex flex-col gap-1">
                  <span className="text-[11px] text-[#64748B] uppercase">
                    MÃ HỒ SƠ
                  </span>
                  <span className="text-[16px] font-semibold text-[#0F172A]">
                    {applicationId || "APP-2026-001"}
                  </span>
                </div>

                <div className="flex flex-col gap-3.5 text-[11px]">
                  <div className="flex items-center justify-between gap-2">
                    <span className="text-[#64748B]">Loại vay</span>
                    <span className="font-medium text-[#475569]">
                      Vay tín chấp theo lương
                    </span>
                  </div>
                  <div className="flex items-center justify-between gap-2">
                    <span className="text-[#64748B]">Số tiền yêu cầu</span>
                    <span className="font-medium text-[#475569]">
                      {existingDossier?.loanAmount || draftLoan.loanAmount}
                    </span>
                  </div>
                  <div className="flex items-center justify-between gap-2">
                    <span className="text-[#64748B]">Chi nhánh</span>
                    <span className="font-medium text-[#475569]">
                      {branchDisplay}
                    </span>
                  </div>
                </div>
              </div>

              <div className="bg-white border border-[#E2E8F0] rounded-[12px] p-[22px] flex flex-col gap-4">
                <div className="flex flex-col gap-1.5">
                  <h3 className="text-[14px] font-semibold text-[#0F172A]">
                    Trước khi gửi
                  </h3>
                  <p className="text-[12px] leading-[1.55] text-[#64748B]">
                    Đảm bảo hồ sơ của bạn bao gồm các giấy tờ cần thiết sau.
                  </p>
                </div>

                <div className="flex flex-col gap-3.5">
                  {BEFORE_SUBMIT_ITEMS.map((item) => (
                    <div key={item.title} className="flex items-start gap-2.5">
                      <Check
                        className="size-4 text-[#15803D] shrink-0 mt-0.5"
                        strokeWidth={2.5}
                      />
                      <div className="flex flex-col gap-1">
                        <span className="text-[12px] font-medium text-[#475569]">
                          {item.title}
                        </span>
                        <span className="text-[11px] leading-[1.45] text-[#64748B]">
                          {item.desc}
                        </span>
                      </div>
                    </div>
                  ))}
                </div>
              </div>

              <div className="px-1 flex items-start gap-2.5">
                <Clock3 className="size-[18px] text-[#0284C7] shrink-0 mt-0.5" />
                <div className="flex flex-col gap-1.5">
                  <span className="text-[12px] font-semibold text-[#475569]">
                    Tiếp theo là gì?
                  </span>
                  <p className="text-[11px] leading-[1.65] text-[#64748B]">
                    Đội ngũ của chúng tôi sẽ xem xét hồ sơ của bạn trong 1–2
                    ngày làm việc. Bạn có thể theo dõi tiến độ trong hàng đợi
                    xác minh.
                  </p>
                </div>
              </div>
            </aside>
          </div>
        )}
      </div>
    </AppLayout>
  );
}
