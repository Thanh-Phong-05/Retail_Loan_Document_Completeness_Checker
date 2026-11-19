import { useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import {
  CheckCircle2,
  ChevronLeft,
  ChevronRight,
  ClipboardCheck,
  FileWarning,
  FolderOpen,
  Search,
} from "lucide-react";
import AppLayout from "../components/AppLayout";
import { CompletenessStatusBadge } from "../components/StatusBadge";
import { getLoanProductTitle } from "../constants/statusCodes";
import { useDossiers } from "../context/DossierContext";

function formatDateVi(dateStr) {
  if (!dateStr) return "—";
  const parts = dateStr.split("-");
  if (parts.length === 3) return `${parts[2]}/${parts[1]}/${parts[0]}`;
  return dateStr;
}

const PAGE_SIZE = 5;

export default function DashboardPage({ queueOnly = false }) {
  const { dossiers } = useDossiers();
  const navigate = useNavigate();

  const [search, setSearch] = useState("");
  const [statusFilter, setStatusFilter] = useState(
    queueOnly ? "REVIEW_NEEDED" : "ALL"
  );
  const [page, setPage] = useState(1);

  const counts = useMemo(() => {
    return {
      ALL: dossiers.length,
      READY_FOR_CREDIT_REVIEW: dossiers.filter(
        (d) => d.completenessStatus === "READY_FOR_CREDIT_REVIEW"
      ).length,
      REVIEW_NEEDED: dossiers.filter(
        (d) => d.completenessStatus === "REVIEW_NEEDED"
      ).length,
      INCOMPLETE: dossiers.filter((d) => d.completenessStatus === "INCOMPLETE")
        .length,
    };
  }, [dossiers]);

  const filtered = useMemo(() => {
    const q = search.trim().toLowerCase();
    return dossiers.filter((d) => {
      const matchStatus =
        statusFilter === "ALL" || d.completenessStatus === statusFilter;
      const matchQuery =
        !q ||
        d.applicationId.toLowerCase().includes(q) ||
        (d.applicantName || "").toLowerCase().includes(q);
      return matchStatus && matchQuery;
    });
  }, [dossiers, search, statusFilter]);

  const totalPages = Math.max(1, Math.ceil(filtered.length / PAGE_SIZE));
  const safePage = Math.min(page, totalPages);
  const visibleRows = filtered.slice(
    (safePage - 1) * PAGE_SIZE,
    safePage * PAGE_SIZE
  );

  const pad2 = (n) => String(n).padStart(2, "0");

  const tabs = [
    { key: "ALL", label: `Tất cả (${counts.ALL})` },
    {
      key: "READY_FOR_CREDIT_REVIEW",
      label: `Sẵn sàng phê duyệt (${pad2(counts.READY_FOR_CREDIT_REVIEW)})`,
    },
    {
      key: "REVIEW_NEEDED",
      label: `Cần rà soát (${pad2(counts.REVIEW_NEEDED)})`,
    },
    {
      key: "INCOMPLETE",
      label: `Thiếu hồ sơ (${pad2(counts.INCOMPLETE)})`,
    },
  ];

  return (
    <AppLayout>
      <div className="flex flex-col gap-[26px] w-full max-w-[1600px] mx-auto">
        {/* Breadcrumbs */}
        <nav className="flex items-center gap-2 text-[12px] text-[#64748B]">
          <span>Hồ sơ vay</span>
          <ChevronRight className="size-3" />
          <span className="font-medium text-[#0F172A]">
            {queueOnly ? "Hàng đợi xác minh" : "Danh sách hồ sơ"}
          </span>
        </nav>

        {/* Page Heading */}
        <div className="flex flex-wrap items-center justify-between gap-4">
          <div className="flex flex-col gap-1.5">
            <h1 className="text-[28px] sm:text-[30px] font-normal tracking-[-0.8px] text-[#0F172A]">
              {queueOnly ? "Hàng đợi Xác minh Hồ sơ" : "Danh sách Hồ sơ vay"}
            </h1>
            <p className="text-[14px] text-[#64748B]">
              Theo dõi tiến độ tiếp nhận và rà soát tính đầy đủ của hồ sơ vay
              tiêu dùng.
            </p>
          </div>

          <button
            type="button"
            onClick={() => navigate("/admin/upload")}
            className="h-[44px] px-[18px] rounded-[6px] bg-[#0284C7] hover:bg-[#0369A1] text-[12px] font-semibold text-white transition-colors shrink-0"
          >
            + Tải hồ sơ mới
          </button>
        </div>

        {/* 4 Metric Cards */}
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-5">
          <div className="bg-white border border-[#E2E8F0] rounded-[12px] p-5 flex flex-col gap-3">
            <div className="flex items-center justify-between">
              <span className="text-[13px] font-medium text-[#475569]">
                Tổng số hồ sơ
              </span>
              <span className="size-[30px] rounded-[6px] bg-[#F8FAFC] border border-[#E2E8F0] flex items-center justify-center">
                <FolderOpen className="size-4 text-[#475569]" />
              </span>
            </div>
            <span className="text-[30px] font-semibold text-[#0F172A] leading-none">
              {pad2(counts.ALL)}
            </span>
          </div>

          <div className="bg-white border border-[#BBF7D0] rounded-[12px] p-5 flex flex-col gap-3">
            <div className="flex items-center justify-between">
              <span className="text-[13px] font-medium text-[#475569]">
                Sẵn sàng phê duyệt
              </span>
              <span className="size-[30px] rounded-[6px] bg-[#F0FDF4] flex items-center justify-center">
                <CheckCircle2 className="size-4 text-[#15803D]" />
              </span>
            </div>
            <span className="text-[30px] font-semibold text-[#15803D] leading-none">
              {pad2(counts.READY_FOR_CREDIT_REVIEW)}
            </span>
          </div>

          <div className="bg-white border border-[#FDE68A] rounded-[12px] p-5 flex flex-col gap-3">
            <div className="flex items-center justify-between">
              <span className="text-[13px] font-medium text-[#475569]">
                Cần rà soát
              </span>
              <span className="size-[30px] rounded-[6px] bg-[#FFFBEB] flex items-center justify-center">
                <ClipboardCheck className="size-4 text-[#B45309]" />
              </span>
            </div>
            <span className="text-[30px] font-semibold text-[#B45309] leading-none">
              {pad2(counts.REVIEW_NEEDED)}
            </span>
          </div>

          <div className="bg-white border border-[#FECACA] rounded-[12px] p-5 flex flex-col gap-3">
            <div className="flex items-center justify-between">
              <span className="text-[13px] font-medium text-[#475569]">
                Thiếu hồ sơ
              </span>
              <span className="size-[30px] rounded-[6px] bg-[#FEF2F2] flex items-center justify-center">
                <FileWarning className="size-4 text-[#DC2626]" />
              </span>
            </div>
            <span className="text-[30px] font-semibold text-[#DC2626] leading-none">
              {pad2(counts.INCOMPLETE)}
            </span>
          </div>
        </div>

        {/* Table Card */}
        <div className="bg-white border border-[#E2E8F0] rounded-[12px] overflow-hidden">
          {/* Search Toolbar */}
          <div className="px-6 py-5 border-b border-[#E2E8F0] flex flex-wrap items-center justify-between gap-4">
            <div className="w-full sm:w-[492px] h-[42px] rounded-[8px] border border-[#CBD5E1] px-3.5 flex items-center gap-2.5 focus-within:border-[#0284C7]">
              <Search className="size-[17px] text-[#64748B] shrink-0" />
              <input
                type="text"
                value={search}
                onChange={(e) => {
                  setSearch(e.target.value);
                  setPage(1);
                }}
                placeholder="Tìm theo mã hồ sơ hoặc tên người vay..."
                className="w-full text-[13px] text-[#0F172A] placeholder:text-[#94A3B8] focus:outline-none bg-transparent"
              />
            </div>
            <span className="text-[12px] text-[#64748B]">
              {filtered.length} hồ sơ
            </span>
          </div>

          {/* Status Tabs */}
          <div className="px-6 border-b border-[#E2E8F0] flex items-center gap-7 overflow-x-auto">
            {tabs.map((t) => {
              const active = statusFilter === t.key;
              return (
                <button
                  key={t.key}
                  type="button"
                  onClick={() => {
                    setStatusFilter(t.key);
                    setPage(1);
                  }}
                  className={`h-[50px] text-[13px] whitespace-nowrap border-b-2 transition-colors ${
                    active
                      ? "border-[#0284C7] text-[#0284C7] font-semibold"
                      : "border-transparent text-[#64748B] font-medium hover:text-[#0F172A]"
                  }`}
                >
                  {t.label}
                </button>
              );
            })}
          </div>

          {/* Table */}
          <div className="overflow-x-auto">
            <table className="w-full text-left border-collapse">
              <thead>
                <tr className="bg-[#F8FAFC] border-b border-[#E2E8F0] text-[12px] font-semibold text-[#64748B]">
                  <th className="py-3.5 pl-6 pr-4">Mã hồ sơ</th>
                  <th className="py-3.5 px-4">Tên người vay</th>
                  <th className="py-3.5 px-4">Loại vay</th>
                  <th className="py-3.5 px-4">Ngày nộp</th>
                  <th className="py-3.5 px-4">Trạng thái</th>
                  <th className="py-3.5 pl-4 pr-6">Hành động</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-[#E2E8F0] text-[13px]">
                {visibleRows.length === 0 ? (
                  <tr>
                    <td
                      colSpan={6}
                      className="py-10 text-center text-[#64748B]"
                    >
                      Không có hồ sơ nào phù hợp với bộ lọc hiện tại.
                    </td>
                  </tr>
                ) : (
                  visibleRows.map((d) => {
                    const isReady =
                      d.completenessStatus === "READY_FOR_CREDIT_REVIEW";
                    return (
                      <tr
                        key={d.applicationId}
                        className="hover:bg-[#F8FAFC]/80 transition-colors"
                      >
                        <td className="py-5 pl-6 pr-4 font-semibold text-[#0284C7]">
                          <button
                            type="button"
                            onClick={() =>
                              navigate(`/admin/review/${d.applicationId}`)
                            }
                            className="hover:underline"
                          >
                            {d.applicationId}
                          </button>
                        </td>
                        <td className="py-5 px-4 font-medium text-[#0F172A]">
                          {d.applicantName}{" "}
                          <span className="text-[11px] text-[#64748B] font-normal">
                            (v{d.version})
                          </span>
                        </td>
                        <td className="py-5 px-4 text-[#475569]">
                          {getLoanProductTitle(d.productType)}
                        </td>
                        <td className="py-5 px-4 text-[#64748B]">
                          {formatDateVi(d.submissionDate)}
                        </td>
                        <td className="py-5 px-4">
                          <CompletenessStatusBadge
                            status={d.completenessStatus}
                            role="OFFICER"
                          />
                        </td>
                        <td className="py-5 pl-4 pr-6">
                          {isReady ? (
                            <button
                              type="button"
                              onClick={() =>
                                navigate(`/admin/review/${d.applicationId}`)
                              }
                              className="h-[34px] px-3.5 rounded-[6px] border border-[#E2E8F0] bg-[#F8FAFC] hover:bg-[#F1F5F9] text-[12px] font-medium text-[#475569] whitespace-nowrap"
                            >
                              Xem chi tiết
                            </button>
                          ) : (
                            <button
                              type="button"
                              onClick={() =>
                                navigate(`/admin/review/${d.applicationId}`)
                              }
                              className="h-[34px] px-3.5 rounded-[6px] border border-[#BAE6FD] bg-white hover:bg-[#F0F9FF] text-[12px] font-semibold text-[#0284C7] whitespace-nowrap"
                            >
                              Rà soát
                            </button>
                          )}
                        </td>
                      </tr>
                    );
                  })
                )}
              </tbody>
            </table>
          </div>

          {/* Pagination */}
          <div className="px-6 py-4 border-t border-[#E2E8F0] flex flex-wrap items-center justify-between gap-4 text-[12px] text-[#64748B]">
            <span>
              Hiển thị{" "}
              {filtered.length === 0 ? 0 : (safePage - 1) * PAGE_SIZE + 1}–
              {Math.min(safePage * PAGE_SIZE, filtered.length)} trên tổng số{" "}
              {filtered.length} hồ sơ
            </span>

            <div className="flex items-center gap-1.5">
              <button
                type="button"
                disabled={safePage <= 1}
                onClick={() => setPage((p) => Math.max(1, p - 1))}
                className="size-8 rounded-[6px] border border-[#E2E8F0] flex items-center justify-center disabled:opacity-40"
              >
                <ChevronLeft className="size-3.5" />
              </button>
              {Array.from({ length: totalPages }, (_, i) => i + 1).map((p) => (
                <button
                  key={p}
                  type="button"
                  onClick={() => setPage(p)}
                  className={`size-8 rounded-[6px] border text-[12px] font-semibold ${
                    p === safePage
                      ? "bg-[#F0F9FF] border-[#BAE6FD] text-[#0284C7]"
                      : "bg-white border-[#E2E8F0] text-[#475569] hover:bg-[#F8FAFC]"
                  }`}
                >
                  {p}
                </button>
              ))}
              <button
                type="button"
                disabled={safePage >= totalPages}
                onClick={() => setPage((p) => Math.min(totalPages, p + 1))}
                className="size-8 rounded-[6px] border border-[#E2E8F0] flex items-center justify-center disabled:opacity-40"
              >
                <ChevronRight className="size-3.5" />
              </button>
            </div>
          </div>
        </div>
      </div>
    </AppLayout>
  );
}
