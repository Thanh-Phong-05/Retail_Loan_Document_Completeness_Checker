import { useState } from "react";
import { useNavigate } from "react-router-dom";
import {
  ArrowRight,
  Check,
  Landmark,
  LockKeyhole,
  ShieldCheck,
} from "lucide-react";
import { DEMO_USERS, useAuth } from "../context/AuthContext";

const FEATURES = [
  {
    title: "Kiểm tra tự động 4 nhóm chứng từ bắt buộc",
    desc: "CCCD 2 mặt, Hợp đồng lao động, Sao kê lương 03 tháng & Đơn đề nghị vay vốn.",
  },
  {
    title: "Định vị chính xác trang lỗi (Page Anchors)",
    desc: "Tự động phát hiện thiếu chữ ký, khuyết kỳ sao kê hoặc ảnh mờ và dẫn tới đúng trang PDF.",
  },
  {
    title: "Toàn vẹn dữ liệu SHA-256 & Audit Trail",
    desc: "Mỗi phiên bản hồ sơ và quyết định bàn giao tín dụng đều được ghi nhận nhật ký bảo mật.",
  },
];

export default function LoginPage() {
  const { loginAsCustomer, loginAsOfficer } = useAuth();
  const navigate = useNavigate();

  const [activeTab, setActiveTab] = useState("CUSTOMER");
  const [identifier, setIdentifier] = useState(DEMO_USERS.CUSTOMER.identifier);
  const [password, setPassword] = useState("Unit123!");
  const [showPassword, setShowPassword] = useState(false);
  const [remember, setRemember] = useState(true);
  const [error, setError] = useState("");

  const handleSelectTab = (role) => {
    setActiveTab(role);
    setError("");
    if (role === "CUSTOMER") {
      setIdentifier(DEMO_USERS.CUSTOMER.identifier);
    } else {
      setIdentifier(DEMO_USERS.OFFICER.identifier);
    }
  };

  const handleSubmit = (e) => {
    e.preventDefault();
    if (!identifier.trim()) {
      setError("Vui lòng nhập Số CCCD/Email hoặc Mã nhân viên.");
      return;
    }
    if (activeTab === "CUSTOMER") {
      loginAsCustomer(identifier);
      navigate("/customer/select-package");
    } else {
      loginAsOfficer(identifier);
      navigate("/admin/dashboard");
    }
  };

  return (
    <div className="min-h-screen flex flex-col lg:flex-row bg-[#F8FAFC]">
      {/* Left Brand & Security Panel */}
      <aside className="lg:w-[560px] shrink-0 bg-[#0F172A] text-white p-8 lg:p-14 flex flex-col justify-between gap-10">
        <div className="flex flex-col gap-10">
          {/* Brand */}
          <div className="flex items-center gap-2.5">
            <div className="size-8 rounded-[8px] bg-[#0284C7] flex items-center justify-center">
              <Landmark className="size-5 text-white" />
            </div>
            <span className="text-[20px] font-normal tracking-[-0.6px] text-white">
              meridian
            </span>
          </div>

          {/* Hero Copy */}
          <div className="flex flex-col gap-4">
            <div className="inline-flex items-center gap-2 rounded-full bg-[#1E334C] px-3 py-1.5 w-fit">
              <span className="size-1.5 rounded-full bg-[#7DD3FC]" />
              <span className="text-[10px] font-semibold tracking-[0.8px] text-[#7DD3FC] uppercase">
                MERIDIAN DIGITAL LENDING · KHÁCH HÀNG &amp; ADMIN
              </span>
            </div>

            <h1 className="text-[28px] lg:text-[30px] font-semibold leading-[1.25] tracking-[-0.5px] text-white">
              Hệ thống Kiểm tra Tính đầy đủ Hồ sơ Vay Tiêu dùng
            </h1>

            <p className="text-[14px] leading-[1.6] text-[#94A3B8]">
              Nền tảng kết nối Khách hàng vay vốn (Chọn gói vay &amp; Nộp chứng
              từ trực tuyến) và Cán bộ Ngân hàng (Rà soát hồ sơ tự động bằng
              AI/OCR &amp; Camunda 8).
            </p>
          </div>

          {/* 3 Feature Cards */}
          <div className="flex flex-col gap-4">
            {FEATURES.map((f) => (
              <div
                key={f.title}
                className="bg-[#17243A] rounded-[10px] px-[18px] py-4 flex flex-col gap-1.5"
              >
                <h2 className="text-[13px] font-semibold text-white">
                  {f.title}
                </h2>
                <p className="text-[12px] leading-[1.55] text-[#94A3B8]">
                  {f.desc}
                </p>
              </div>
            ))}
          </div>
        </div>

        <div className="flex items-center gap-2 text-[11px] text-[#94A3B8]">
          <ShieldCheck className="size-[15px] shrink-0" />
          <span>Không gian ngân hàng an toàn</span>
        </div>
      </aside>

      {/* Right Login Workspace */}
      <main className="flex-1 flex flex-col items-center justify-center px-6 py-12 gap-6">
        <form
          onSubmit={handleSubmit}
          className="w-full max-w-[480px] bg-white border border-[#E2E8F0] rounded-[14px] p-8 sm:p-10 flex flex-col gap-5 shadow-[0_1px_3px_rgba(15,23,42,0.04)]"
        >
          {/* Card Header */}
          <div className="flex flex-col gap-1.5">
            <h2 className="text-[24px] font-semibold text-[#0F172A] tracking-[-0.4px]">
              Đăng nhập Hệ thống
            </h2>
            <p className="text-[13px] leading-[1.5] text-[#64748B]">
              Chọn vai trò đăng nhập để tiếp tục đăng ký khoản vay hoặc truy cập
              không gian rà soát tín dụng.
            </p>
          </div>

          {/* 2-Role Switcher Tabs (Mock Login) */}
          <div className="bg-[#F8FAFC] border border-[#E2E8F0] rounded-[10px] p-1 grid grid-cols-2 gap-1.5">
            <button
              type="button"
              onClick={() => handleSelectTab("CUSTOMER")}
              className={`rounded-[7px] px-3 py-2.5 text-center transition-all ${
                activeTab === "CUSTOMER"
                  ? "bg-[#0284C7] text-white shadow-xs"
                  : "bg-transparent text-[#475569] hover:bg-white/70"
              }`}
            >
              <div className="text-[12px] font-semibold">
                1. Khách hàng vay vốn
              </div>
              <div
                className={`text-[10px] mt-0.5 ${
                  activeTab === "CUSTOMER" ? "text-[#F0F9FF]" : "text-[#64748B]"
                }`}
              >
                Chọn gói vay &amp; Tải hồ sơ
              </div>
            </button>

            <button
              type="button"
              onClick={() => handleSelectTab("OFFICER")}
              className={`rounded-[7px] px-3 py-2.5 text-center transition-all ${
                activeTab === "OFFICER"
                  ? "bg-[#0284C7] text-white shadow-xs"
                  : "bg-transparent text-[#475569] hover:bg-white/70"
              }`}
            >
              <div className="text-[12px] font-semibold">
                2. Admin / Nhân viên NH
              </div>
              <div
                className={`text-[10px] mt-0.5 ${
                  activeTab === "OFFICER" ? "text-[#F0F9FF]" : "text-[#64748B]"
                }`}
              >
                Dashboard &amp; Duyệt hồ sơ
              </div>
            </button>
          </div>

          {/* Credentials */}
          <div className="flex flex-col gap-4">
            <div className="flex flex-col gap-1.5">
              <label
                htmlFor="login-identifier"
                className="text-[13px] font-medium text-[#0F172A]"
              >
                Số CCCD / Email hoặc Mã nhân viên{" "}
                <span className="text-[#0284C7]">*</span>
              </label>
              <input
                id="login-identifier"
                type="text"
                value={identifier}
                onChange={(e) => setIdentifier(e.target.value)}
                placeholder={
                  activeTab === "CUSTOMER"
                    ? "079099001234"
                    : "alex.morgan@meridianbank.vn"
                }
                className="h-[46px] rounded-[8px] border border-[#CBD5E1] bg-white px-3.5 text-[14px] text-[#0F172A] focus:outline-none focus:border-[#0284C7] focus:ring-2 focus:ring-[#0284C7]/15"
              />
              <p className="text-[11px] leading-[1.45] text-[#64748B]">
                Khách hàng nhập Số CCCD/SĐT · Nhân viên ngân hàng nhập Email nội
                bộ (<span className="font-medium">@meridianbank.vn</span>).
              </p>
            </div>

            <div className="flex flex-col gap-1.5">
              <label
                htmlFor="login-password"
                className="text-[13px] font-medium text-[#0F172A]"
              >
                Mật khẩu / Mã OTP <span className="text-[#0284C7]">*</span>
              </label>
              <div className="h-[46px] rounded-[8px] border border-[#CBD5E1] bg-white px-3.5 flex items-center justify-between gap-2 focus-within:border-[#0284C7] focus-within:ring-2 focus-within:ring-[#0284C7]/15">
                <input
                  id="login-password"
                  type={showPassword ? "text" : "password"}
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  className="w-full text-[14px] text-[#0F172A] focus:outline-none bg-transparent"
                />
                <button
                  type="button"
                  onClick={() => setShowPassword((s) => !s)}
                  className="text-[12px] font-medium text-[#0284C7] shrink-0 hover:underline"
                >
                  {showPassword ? "Ẩn" : "Hiện"}
                </button>
              </div>
            </div>
          </div>

          {error && (
            <p className="text-[12px] font-medium text-[#DC2626]">{error}</p>
          )}

          {/* Remember & Forgot */}
          <div className="flex items-center justify-between gap-2 text-[12px]">
            <button
              type="button"
              onClick={() => setRemember((r) => !r)}
              className="inline-flex items-center gap-2 text-[#475569]"
            >
              <span
                className={`size-4 rounded-[4px] flex items-center justify-center border transition-colors ${
                  remember
                    ? "bg-[#0284C7] border-[#0284C7] text-white"
                    : "bg-white border-[#CBD5E1]"
                }`}
              >
                {remember && <Check className="size-3" strokeWidth={3} />}
              </span>
              <span>Ghi nhớ đăng nhập trên thiết bị này</span>
            </button>

            <button
              type="button"
              onClick={() =>
                window.alert(
                  "Chế độ Mock Login: Bấm trực tiếp 'Đăng nhập & Tiếp tục' với tài khoản đã điền sẵn."
                )
              }
              className="font-medium text-[#0284C7] hover:underline"
            >
              Quên mật khẩu?
            </button>
          </div>

          {/* Submit */}
          <button
            type="submit"
            className="w-full h-[46px] rounded-[8px] bg-[#0284C7] hover:bg-[#0369A1] text-[14px] font-semibold text-white inline-flex items-center justify-center gap-2 transition-colors"
          >
            <span>Đăng nhập &amp; Tiếp tục</span>
            <ArrowRight className="size-4" />
          </button>

          {/* Active Demo Role Hint */}
          <div className="rounded-[8px] bg-[#F0F9FF] border border-[#BAE6FD] px-3.5 py-2.5 text-[11px] text-[#475569] leading-[1.5]">
            <span className="font-semibold text-[#0284C7]">
              Mock Login (Sprint 2):
            </span>{" "}
            {activeTab === "CUSTOMER"
              ? "Đăng nhập dưới tên Nguyễn Văn A → Chọn gói vay → Tải hồ sơ."
              : "Đăng nhập dưới tên Alex Morgan (Loan Officer) → Dashboard & Review Workspace."}
          </div>
        </form>

        <div className="flex items-center gap-2 text-[11px] text-[#64748B] text-center">
          <LockKeyhole className="size-3.5 shrink-0" />
          <span>
            Phiên đăng nhập được bảo vệ bằng TLS 1.3 · Mọi thao tác xác nhận hồ
            sơ đều được lưu vết tại bảng AuditLogs.
          </span>
        </div>
      </main>
    </div>
  );
}
