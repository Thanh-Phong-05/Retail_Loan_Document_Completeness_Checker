import { useState } from "react";
import { useNavigate } from "react-router-dom";
import {
  ArrowRight,
  Check,
  ChevronRight,
  Clock3,
  FolderOpen,
  Lock,
  Sparkles,
} from "lucide-react";
import AppLayout from "../components/AppLayout";
import ProgressStepper from "../components/ProgressStepper";
import {
  LOAN_PACKAGES,
  PRODUCT_TYPES,
} from "../constants/statusCodes";
import { useAuth } from "../context/AuthContext";
import { useDossiers } from "../context/DossierContext";

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

export default function SelectPackagePage() {
  const { user } = useAuth();
  const { draftLoan, setDraftLoan } = useDossiers();
  const navigate = useNavigate();

  const [applicationId, setApplicationId] = useState(
    draftLoan.applicationId || "APP-2026-007"
  );
  const [applicantName, setApplicantName] = useState(
    draftLoan.applicantName || user?.fullName || "Nguyễn Văn A"
  );
  const [loanAmount, setLoanAmount] = useState(
    draftLoan.loanAmount || "100.000.000 VNĐ"
  );
  const [termAndBranch, setTermAndBranch] = useState(
    draftLoan.termAndBranch || "24 tháng · CN TP. Hồ Chí Minh"
  );
  const [error, setError] = useState("");

  const branchDisplay = termAndBranch.includes("·")
    ? termAndBranch.split("·")[1].replace("CN", "").trim()
    : termAndBranch || "TP. Hồ Chí Minh";

  const handleGenerateId = () => {
    const rand = String(Math.floor(100 + Math.random() * 900));
    setApplicationId(`APP-2026-${rand}`);
    setError("");
  };

  const handleContinue = (e) => {
    e.preventDefault();
    const cleanId = applicationId.trim();
    if (!cleanId || !/^[A-Za-z0-9_-]{1,50}$/.test(cleanId)) {
      setError(
        "Mã hồ sơ chỉ được chứa chữ cái, số, dấu '-' hoặc '_' (tối đa 50 ký tự)."
      );
      return;
    }
    if (!applicantName.trim()) {
      setError("Vui lòng nhập đầy đủ họ và tên khách hàng vay.");
      return;
    }

    setDraftLoan({
      applicationId: cleanId,
      applicantName: applicantName.trim(),
      productType: PRODUCT_TYPES.UNSECURED_SALARIED_LOAN,
      loanAmount: loanAmount.trim() || "100.000.000 VNĐ",
      termAndBranch: termAndBranch.trim() || "24 tháng · CN TP. Hồ Chí Minh",
    });

    navigate("/customer/upload");
  };

  return (
    <AppLayout>
      <div className="flex flex-col gap-6 w-full max-w-[1600px] mx-auto">
        {/* Breadcrumbs */}
        <nav className="flex items-center gap-2 text-[12px] text-[#64748B]">
          <span>Hồ sơ vay</span>
          <ChevronRight className="size-3" />
          <span>{applicationId || "APP-2026-001"}</span>
          <ChevronRight className="size-3" />
          <span className="font-medium text-[#0F172A]">Chọn gói vay</span>
        </nav>

        {/* Page Heading */}
        <div className="flex flex-wrap items-center justify-between gap-4">
          <div className="flex flex-col gap-1.5">
            <h1 className="text-[28px] sm:text-[30px] font-normal tracking-[-0.8px] text-[#0F172A]">
              Chọn Gói Vay &amp; Khởi Tạo Hồ Sơ
            </h1>
            <p className="text-[14px] text-[#64748B]">
              Lựa chọn sản phẩm vay tiêu dùng phù hợp và thiết lập thông tin cơ
              bản trước khi tải chứng từ.
            </p>
          </div>
          <div className="inline-flex items-center gap-2 rounded-full bg-white border border-[#E2E8F0] px-3 py-1.5 text-[12px] font-medium text-[#475569]">
            <span className="size-1.5 rounded-full bg-[#64748B]" />
            <span>Bước 1 / 3</span>
          </div>
        </div>

        {/* 3-Step Progress */}
        <ProgressStepper currentStep={1} />

        {/* Two Column Workspace */}
        <div className="grid grid-cols-1 xl:grid-cols-[1fr_340px] gap-6 items-start">
          {/* Left Form Card */}
          <form
            onSubmit={handleContinue}
            className="bg-white border border-[#E2E8F0] rounded-[12px] overflow-hidden"
          >
            <div className="px-7 py-[22px] border-b border-[#E2E8F0] flex items-center justify-between gap-4">
              <div>
                <h2 className="text-[17px] font-semibold text-[#0F172A]">
                  1. Chọn sản phẩm vay vốn{" "}
                  <span className="text-[#0284C7]">*</span>
                </h2>
                <p className="text-[12px] text-[#64748B] mt-1">
                  Hệ thống sẽ tự động áp bộ quy tắc kiểm tra danh mục chứng từ
                  (DMN) theo gói vay bạn chọn.
                </p>
              </div>
              <span className="text-[11px] text-[#64748B] shrink-0">
                * Bắt buộc
              </span>
            </div>

            <div className="p-7 flex flex-col gap-5">
              {/* Loan Packages List */}
              <div className="flex flex-col gap-3.5">
                {LOAN_PACKAGES.map((pkg) => {
                  const isLockedActive = pkg.enabled;
                  return (
                    <div
                      key={pkg.code}
                      aria-disabled={!isLockedActive}
                      className={`rounded-[10px] px-5 py-[18px] flex flex-col gap-2.5 transition-all ${
                        isLockedActive
                          ? "bg-[#F0F9FF] border-[1.5px] border-[#0284C7]"
                          : "bg-[#F8FAFC] border border-dashed border-[#E2E8F0] opacity-65 cursor-not-allowed select-none"
                      }`}
                    >
                      <div className="flex flex-wrap items-center justify-between gap-2">
                        <div className="flex items-center gap-2.5">
                          {isLockedActive ? (
                            <span className="size-[18px] rounded-full bg-[#0284C7] flex items-center justify-center shrink-0">
                              <span className="size-2 rounded-full bg-white" />
                            </span>
                          ) : (
                            <span className="size-[18px] rounded-full bg-[#E2E8F0] border border-[#CBD5E1] shrink-0 flex items-center justify-center">
                              <Lock className="size-2.5 text-[#64748B]" />
                            </span>
                          )}
                          <span
                            className={`text-[15px] font-semibold ${
                              isLockedActive
                                ? "text-[#0F172A]"
                                : "text-[#64748B]"
                            }`}
                          >
                            {pkg.title}
                          </span>
                        </div>

                        <span
                          className={`rounded-full px-2.5 py-1 text-[11px] font-medium ${
                            isLockedActive
                              ? "bg-[#0284C7] text-white"
                              : "bg-[#E2E8F0] text-[#475569]"
                          }`}
                        >
                          {pkg.badge}
                        </span>
                      </div>

                      <div
                        className={`text-[12px] font-medium ${
                          isLockedActive ? "text-[#0284C7]" : "text-[#64748B]"
                        }`}
                      >
                        {pkg.terms}
                      </div>

                      <p className="text-[12px] text-[#64748B]">
                        {pkg.requiredDocs}
                      </p>
                    </div>
                  );
                })}
              </div>

              {/* Scope Notice Banner matching Figma 1091:2 */}
              <div className="bg-[#F8FAFC] border border-[#E2E8F0] rounded-[8px] px-3.5 py-2.5 text-[11px] leading-[1.55] text-[#475569]">
                <strong>Lưu ý:</strong> Hiện tại hệ thống AI/OCR và bộ quy tắc
                DMN áp dụng kiểm tra tự động 4 nhóm chứng từ cho gói{" "}
                <span className="font-medium text-[#0F172A]">
                  Vay tín chấp tiêu dùng theo lương (UNSECURED_SALARIED_LOAN)
                </span>
                . Hai gói vay còn lại mang tính minh họa cho giai đoạn mở rộng.
              </div>

              {/* Section 2: Application Metadata */}
              <div className="pt-2 flex flex-col gap-4">
                <h3 className="text-[15px] font-semibold text-[#0F172A]">
                  2. Thông tin khởi tạo hồ sơ vay
                </h3>

                <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                  <div className="flex flex-col gap-1.5">
                    <label
                      htmlFor="pkg-app-id"
                      className="text-[12px] font-medium text-[#0F172A]"
                    >
                      Mã hồ sơ (Application ID){" "}
                      <span className="text-[#0284C7]">*</span>
                    </label>
                    <div className="flex items-center gap-2">
                      <input
                        id="pkg-app-id"
                        type="text"
                        value={applicationId}
                        onChange={(e) => {
                          setApplicationId(e.target.value);
                          setError("");
                        }}
                        className="flex-1 h-[44px] rounded-[6px] border border-[#CBD5E1] px-3.5 text-[13px] text-[#0F172A] focus:outline-none focus:border-[#0284C7]"
                      />
                      <button
                        type="button"
                        onClick={handleGenerateId}
                        title="Tạo mã hồ sơ mới"
                        className="h-[44px] px-3 rounded-[6px] border border-[#E2E8F0] bg-[#F8FAFC] hover:bg-[#F1F5F9] text-[11px] font-medium text-[#475569] inline-flex items-center gap-1 shrink-0"
                      >
                        <Sparkles className="size-3.5 text-[#0284C7]" />
                        <span>Tạo mã</span>
                      </button>
                    </div>
                    <span className="text-[11px] text-[#64748B]">
                      Mã định danh duy nhất cho bộ hồ sơ vay
                    </span>
                  </div>

                  <div className="flex flex-col gap-1.5">
                    <label
                      htmlFor="pkg-applicant"
                      className="text-[12px] font-medium text-[#0F172A]"
                    >
                      Họ và tên khách hàng vay{" "}
                      <span className="text-[#0284C7]">*</span>
                    </label>
                    <input
                      id="pkg-applicant"
                      type="text"
                      value={applicantName}
                      onChange={(e) => {
                        setApplicantName(e.target.value);
                        setError("");
                      }}
                      className="h-[44px] rounded-[6px] border border-[#CBD5E1] px-3.5 text-[13px] text-[#0F172A] focus:outline-none focus:border-[#0284C7]"
                    />
                    <span className="text-[11px] text-[#64748B]">
                      Trùng khớp với CCCD/CMND
                    </span>
                  </div>

                  <div className="flex flex-col gap-1.5">
                    <label
                      htmlFor="pkg-amount"
                      className="text-[12px] font-medium text-[#0F172A]"
                    >
                      Số tiền đề nghị vay (VNĐ){" "}
                      <span className="text-[#0284C7]">*</span>
                    </label>
                    <input
                      id="pkg-amount"
                      type="text"
                      value={loanAmount}
                      onChange={(e) => setLoanAmount(e.target.value)}
                      className="h-[44px] rounded-[6px] border border-[#CBD5E1] px-3.5 text-[13px] text-[#0F172A] focus:outline-none focus:border-[#0284C7]"
                    />
                    <span className="text-[11px] text-[#64748B]">
                      Hạn mức gói: 10 triệu – 500 triệu VNĐ
                    </span>
                  </div>

                  <div className="flex flex-col gap-1.5">
                    <label
                      htmlFor="pkg-branch"
                      className="text-[12px] font-medium text-[#0F172A]"
                    >
                      Kỳ hạn vay &amp; Chi nhánh tiếp nhận
                    </label>
                    <input
                      id="pkg-branch"
                      type="text"
                      value={termAndBranch}
                      onChange={(e) => setTermAndBranch(e.target.value)}
                      className="h-[44px] rounded-[6px] border border-[#CBD5E1] px-3.5 text-[13px] text-[#0F172A] focus:outline-none focus:border-[#0284C7]"
                    />
                    <span className="text-[11px] text-[#64748B]">
                      Đơn vị xử lý và thẩm định tín dụng
                    </span>
                  </div>
                </div>
              </div>

              {error && (
                <p className="text-[12px] font-medium text-[#DC2626]">{error}</p>
              )}
            </div>

            {/* Form Footer */}
            <div className="px-7 py-5 border-t border-[#E2E8F0] flex items-center justify-between gap-4">
              <button
                type="button"
                onClick={() => navigate("/customer/my-dossiers")}
                className="h-[44px] px-4 rounded-[6px] border border-[#E2E8F0] bg-white hover:bg-[#F8FAFC] text-[12px] font-semibold text-[#475569] transition-colors"
              >
                Quay lại
              </button>
              <button
                type="submit"
                className="h-[44px] px-[18px] rounded-[6px] bg-[#0284C7] hover:bg-[#0369A1] text-[12px] font-semibold text-white inline-flex items-center gap-2 transition-colors"
              >
                <span>Tiếp tục tải hồ sơ</span>
                <ArrowRight className="size-4" />
              </button>
            </div>
          </form>

          {/* Right Guidance Column (304px) */}
          <aside className="flex flex-col gap-5">
            {/* Summary Card */}
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
                  {applicationId || "APP-2026-007"}
                </span>
              </div>

              <div className="flex flex-col gap-3.5 text-[11px]">
                <div className="flex items-center justify-between gap-2">
                  <span className="text-[#64748B]">Loại vay</span>
                  <span className="font-medium text-[#475569] text-right">
                    Vay tín chấp theo lương
                  </span>
                </div>
                <div className="flex items-center justify-between gap-2">
                  <span className="text-[#64748B]">Số tiền yêu cầu</span>
                  <span className="font-medium text-[#475569] text-right">
                    {loanAmount}
                  </span>
                </div>
                <div className="flex items-center justify-between gap-2">
                  <span className="text-[#64748B]">Chi nhánh</span>
                  <span className="font-medium text-[#475569] text-right">
                    {branchDisplay}
                  </span>
                </div>
              </div>
            </div>

            {/* Checklist Card */}
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

            {/* What's Next */}
            <div className="px-1 flex items-start gap-2.5">
              <Clock3 className="size-[18px] text-[#0284C7] shrink-0 mt-0.5" />
              <div className="flex flex-col gap-1.5">
                <span className="text-[12px] font-semibold text-[#475569]">
                  Tiếp theo là gì?
                </span>
                <p className="text-[11px] leading-[1.65] text-[#64748B]">
                  Đội ngũ của chúng tôi sẽ xem xét hồ sơ của bạn trong 1–2 ngày
                  làm việc. Bạn có thể theo dõi tiến độ trong hàng đợi xác minh.
                </p>
              </div>
            </div>
          </aside>
        </div>
      </div>
    </AppLayout>
  );
}
