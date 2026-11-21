import { useState } from "react";
import { NavLink, useNavigate } from "react-router-dom";
import {
  ArrowLeftRight,
  ArrowUpRight,
  Bell,
  ChevronDown,
  ClipboardCheck,
  Database,
  FolderOpen,
  Headset,
  HelpCircle,
  Landmark,
  LockKeyhole,
  LogOut,
  RotateCcw,
  ShieldCheck,
  Upload,
} from "lucide-react";
import { useAuth } from "../context/AuthContext";
import { useDossiers } from "../context/DossierContext";

function padCount(num) {
  return String(num).padStart(2, "0");
}

export default function AppLayout({ children }) {
  const { user, switchRole, logout } = useAuth();
  const { dossiers, useMockApi, setUseMockApi, syncError, resetDemoData } =
    useDossiers();
  const navigate = useNavigate();
  const [menuOpen, setMenuOpen] = useState(false);

  const isCustomer = user?.role === "CUSTOMER";

  const customerDossiers = dossiers.filter(
    (d) =>
      d.applicantName?.toLowerCase() === user?.fullName?.toLowerCase() ||
      d.applicantName === "Nguyễn Văn A"
  );
  const queueCount = dossiers.filter(
    (d) => d.completenessStatus !== "READY_FOR_CREDIT_REVIEW"
  ).length;

  const navItems = isCustomer
    ? [
        {
          to: "/customer/select-package",
          label: "1. Chọn gói vay",
          icon: FolderOpen,
        },
        {
          to: "/customer/upload",
          label: "2. Tải hồ sơ đính kèm",
          icon: Upload,
        },
        {
          to: "/customer/my-dossiers",
          label: "Hồ sơ vay của tôi",
          icon: ClipboardCheck,
          badge: padCount(customerDossiers.length),
        },
      ]
    : [
        {
          to: "/admin/dashboard",
          label: "Danh sách hồ sơ",
          icon: FolderOpen,
          badge: padCount(dossiers.length),
        },
        {
          to: "/admin/upload",
          label: "Tải hồ sơ mới",
          icon: Upload,
        },
        {
          to: "/admin/queue",
          label: "Hàng đợi xác minh",
          icon: ClipboardCheck,
          badge: padCount(queueCount),
        },
      ];

  const handleSwitchRole = () => {
    const nextUser = switchRole();
    setMenuOpen(false);
    if (nextUser.role === "CUSTOMER") {
      navigate("/customer/select-package");
    } else {
      navigate("/admin/dashboard");
    }
  };

  const handleLogout = () => {
    setMenuOpen(false);
    logout();
    navigate("/login");
  };

  const handleResetDemo = () => {
    setMenuOpen(false);
    resetDemoData();
  };

  return (
    <div className="min-h-screen flex bg-[#F8FAFC] text-[#0F172A]">
      {/* Dark Left Sidebar (248px) */}
      <aside className="w-[248px] shrink-0 bg-[#0F172A] text-white flex flex-col justify-between px-4 pt-7 pb-6 sticky top-0 h-screen">
        <div className="flex flex-col gap-[34px]">
          {/* Brand */}
          <div className="flex items-center gap-2.5 px-3">
            <div className="size-8 rounded-[8px] bg-[#0284C7] flex items-center justify-center shrink-0">
              <Landmark className="size-5 text-white" />
            </div>
            <span className="text-[20px] font-normal tracking-[-0.6px] text-white">
              meridian
            </span>
          </div>

          {/* Navigation Section */}
          <div className="flex flex-col gap-1.5">
            <div className="px-3 pb-1 text-[10px] font-semibold tracking-[1.3px] text-[#94A3B8] uppercase">
              {isCustomer ? "KHÁCH HÀNG VAY" : "CHO VAY"}
            </div>
            {navItems.map((item) => {
              const Icon = item.icon;
              return (
                <NavLink
                  key={item.to}
                  to={item.to}
                  className={({ isActive }) =>
                    `h-[44px] px-3 rounded-[8px] flex items-center gap-3 transition-colors ${
                      isActive
                        ? "bg-[#1E334C] text-white font-semibold"
                        : "bg-[#0F172A] text-[#CBD5E1] font-normal hover:bg-[#17243A]"
                    }`
                  }
                >
                  <Icon className="size-[19px] shrink-0" />
                  <span className="text-[13px] flex-1 truncate">
                    {item.label}
                  </span>
                  {item.badge !== undefined && (
                    <span className="bg-[#26364D] text-[#CBD5E1] text-[10px] font-medium px-1.5 py-0.5 rounded-[4px]">
                      {item.badge}
                    </span>
                  )}
                </NavLink>
              );
            })}
          </div>
        </div>

        {/* Sidebar Footer */}
        <div className="flex flex-col gap-[22px]">
          <div className="bg-[#17243A] rounded-[8px] p-4 flex flex-col gap-2.5">
            <Headset className="size-5 text-[#7DD3FC]" />
            <div className="text-[13px] font-semibold text-white">
              Cần hỗ trợ?
            </div>
            <p className="text-[11px] leading-[1.6] text-[#94A3B8]">
              Đội ngũ vận hành luôn sẵn lòng hỗ trợ bạn với hồ sơ vay.
            </p>
            <button
              type="button"
              onClick={() =>
                window.alert(
                  "Hotline Hỗ trợ Tín dụng Meridian Bank: 1900 1234 (Nhánh 2)."
                )
              }
              className="inline-flex items-center gap-1.5 text-[12px] font-medium text-[#7DD3FC] hover:underline w-fit"
            >
              <span>Liên hệ hỗ trợ</span>
              <ArrowUpRight className="size-3.5" />
            </button>
          </div>

          <div className="flex items-center gap-2 px-3 text-[11px] text-[#94A3B8]">
            <ShieldCheck className="size-[15px] shrink-0 text-[#94A3B8]" />
            <span>Không gian ngân hàng an toàn</span>
          </div>
        </div>
      </aside>

      {/* Right Main Area */}
      <div className="flex-1 min-w-0 flex flex-col">
        {/* Top Header (80px) */}
        <header className="h-[80px] bg-white border-b border-[#E2E8F0] px-6 lg:px-10 flex items-center shrink-0 sticky top-0 z-30">
          <div className="w-full max-w-[1600px] mx-auto flex items-center justify-between gap-4">
          <div className="flex items-center gap-3 min-w-0">
            <span className="text-[14px] font-semibold text-[#0F172A] truncate">
              {isCustomer
                ? "Cổng Đăng ký Vay vốn Cá nhân"
                : "Khu vực làm việc cho vay"}
            </span>
            <span className="text-[11px] font-medium text-[#64748B] tracking-wide uppercase shrink-0">
              {isCustomer ? "KHÁCH HÀNG" : "HOẠT ĐỘNG"}
            </span>
          </div>

          <div className="flex items-center gap-5 shrink-0">
            <button
              type="button"
              className="text-[#64748B] hover:text-[#0F172A] transition-colors"
              title="Trợ giúp"
            >
              <HelpCircle className="size-5" />
            </button>
            <button
              type="button"
              className="text-[#64748B] hover:text-[#0F172A] transition-colors relative"
              title="Thông báo"
            >
              <Bell className="size-5" />
              {queueCount > 0 && (
                <span className="absolute -top-0.5 -right-0.5 size-2 rounded-full bg-[#0284C7]" />
              )}
            </button>

            {/* User Account Dropdown */}
            <div className="relative">
              <button
                type="button"
                onClick={() => setMenuOpen((o) => !o)}
                className="flex items-center gap-2.5 text-left"
              >
                <div className="size-9 rounded-full bg-[#F0F9FF] border border-[#BAE6FD] flex items-center justify-center text-[12px] font-semibold text-[#0284C7] shrink-0">
                  {user?.initials || "VA"}
                </div>
                <div className="leading-tight">
                  <div className="text-[13px] font-semibold text-[#0F172A] whitespace-nowrap">
                    {user?.fullName}
                  </div>
                  <div className="text-[11px] text-[#64748B] whitespace-nowrap">
                    {user?.subtitle}
                  </div>
                </div>
                <ChevronDown className="size-3.5 text-[#64748B] shrink-0" />
              </button>

              {menuOpen && (
                <div className="absolute right-0 mt-2 w-64 bg-white border border-[#E2E8F0] rounded-[10px] shadow-lg py-1.5 z-50">
                  <div className="px-3.5 py-2 border-b border-[#F1F5F9]">
                    <div className="text-[11px] text-[#64748B]">
                      Đang đăng nhập ({user?.role})
                    </div>
                    <div className="text-[12px] font-medium text-[#0F172A] truncate">
                      {user?.identifier}
                    </div>
                  </div>
                  <button
                    type="button"
                    onClick={() => {
                      setUseMockApi(!useMockApi);
                      setMenuOpen(false);
                    }}
                    className="w-full px-3.5 py-2 text-left text-[12px] text-[#0F172A] hover:bg-[#F8FAFC] flex items-center justify-between gap-2"
                  >
                    <span className="flex items-center gap-2">
                      <Database
                        className={`size-3.5 ${
                          useMockApi ? "text-[#0284C7]" : "text-[#15803D]"
                        }`}
                      />
                      <span>
                        {useMockApi ? "Chế độ Mock JSON" : "Kết nối API thật"}
                      </span>
                    </span>
                    <span className="text-[10px] font-medium text-[#64748B]">
                      Đổi
                    </span>
                  </button>
                  <button
                    type="button"
                    onClick={handleSwitchRole}
                    className="w-full px-3.5 py-2 text-left text-[12px] text-[#0F172A] hover:bg-[#F8FAFC] flex items-center gap-2"
                  >
                    <ArrowLeftRight className="size-3.5 text-[#0284C7]" />
                    <span>
                      Chuyển sang{" "}
                      {isCustomer ? "Admin / Nhân viên NH" : "Khách hàng vay"}
                    </span>
                  </button>
                  <button
                    type="button"
                    onClick={handleResetDemo}
                    className="w-full px-3.5 py-2 text-left text-[12px] text-[#0F172A] hover:bg-[#F8FAFC] flex items-center gap-2"
                  >
                    <RotateCcw className="size-3.5 text-[#64748B]" />
                    <span>Khôi phục dữ liệu mẫu</span>
                  </button>
                  <button
                    type="button"
                    onClick={handleLogout}
                    className="w-full px-3.5 py-2 text-left text-[12px] text-[#DC2626] hover:bg-[#FEF2F2] flex items-center gap-2 border-t border-[#F1F5F9] mt-1 pt-2"
                  >
                    <LogOut className="size-3.5" />
                    <span>Đăng xuất</span>
                  </button>
                </div>
              )}
            </div>
          </div>
          </div>
        </header>

        {syncError && !useMockApi && (
          <div className="px-6 lg:px-10 mt-4">
            <div className="w-full max-w-[1600px] mx-auto rounded-[8px] border border-[#FECACA] bg-[#FEF2F2] px-4 py-2.5 flex items-center justify-between gap-3 text-[12px] text-[#DC2626]">
              <span>{syncError}</span>
              <button
                type="button"
                onClick={() => setUseMockApi(true)}
                className="font-semibold underline shrink-0"
              >
                Chuyển về Mock JSON
              </button>
            </div>
          </div>
        )}

        {/* Page Content */}
        <main className="flex-1 px-6 lg:px-10 py-7 flex flex-col gap-[26px]">
          <div className="flex-1">{children}</div>

          {/* Security Footer */}
          <footer className="pt-2 pb-4 flex items-center justify-center gap-2 text-[11px] text-[#64748B]">
            <LockKeyhole className="size-[13px] shrink-0" />
            <span>
              Hồ sơ của bạn được mã hóa và chỉ truy cập bởi nhân viên ngân hàng
              được ủy quyền.
            </span>
          </footer>
        </main>
      </div>
    </div>
  );
}
