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
  X,
} from "lucide-react";
import AppLayout from "../components/AppLayout";
import {
  CompletenessStatusBadge,
  DocStatusBadge,
} from "../components/StatusBadge";
import {
  DOC_TYPE_META,
  normalizeDocType,
} from "../constants/statusCodes";
import { useAuth } from "../context/AuthContext";
import { useDossiers } from "../context/DossierContext";

function formatDateVi(dateStr) {
  if (!dateStr) return "—";
  const parts = dateStr.split("-");
  if (parts.length === 3) return `${parts[2]}/${parts[1]}/${parts[0]}`;
  return dateStr;
}

export default function MyDossiersPage() {
  const { user } = useAuth();
  const { dossiers } = useDossiers();
  const navigate = useNavigate();

  const [search, setSearch] = useState("");
  const [statusFilter, setStatusFilter] = useState("ALL");
  const [selectedDetail, setSelectedDetail] = useState(null);

  const myDossiers = useMemo(() => {
    const targetName = (user?.fullName || "Nguyễn Văn A").toLowerCase();
    return dossiers.filter(
      (d) =>
        d.applicantName?.toLowerCase() === targetName ||
        d.applicantName?.toLowerCase() === "nguyễn văn a"
    );
  }, [dossiers, user]);

  const counts = useMemo(() => {
    return {
      ALL: myDossiers.length,
      INCOMPLETE: myDossiers.filter(
        (d) => d.completenessStatus === "INCOMPLETE"
      ).length,
      REVIEW_NEEDED: myDossiers.filter(
        (d) => d.completenessStatus === "REVIEW_NEEDED"
      ).length,
      READY_FOR_CREDIT_REVIEW: myDossiers.filter(
        (d) => d.completenessStatus === "READY_FOR_CREDIT_REVIEW"
      ).length,
    };
  }, [myDossiers]);

  const filtered = useMemo(() => {
    const q = search.trim().toLowerCase();
    return myDossiers.filter((d) => {
      const matchStatus =
        statusFilter === "ALL" || d.completenessStatus === statusFilter;
      const matchQuery =
        !q ||
        d.applicationId.toLowerCase().includes(q) ||
        (d.summaryNote || "").toLowerCase().includes(q);
      return matchStatus && matchQuery;
    });
  }, [myDossiers, search, statusFilter]);

  const tabs = [
    { key: "ALL", label: `Tất cả (${counts.ALL})` },
    {
      key: "READY_FOR_CREDIT_REVIEW",
      label: `Sẵn sàng thẩm định (${counts.READY_FOR_CREDIT_REVIEW})`,
    },
    { key: "INCOMPLETE", label: `Thiếu hồ sơ (${counts.INCOMPLETE})` },
    { key: "REVIEW_NEEDED", label: `Cần rà soát (${counts.REVIEW_NEEDED})` },
  ];

  const pad2 = (n) => String(n).padStart(2, "0");

  return (
    <AppLayout>
      <div className="flex flex-col gap-[26px] w-full max-w-[1600px] mx-auto">
        {/* Breadcrumbs */}
        <nav className="flex items-center gap-2 text-[12px] text-[#64748B]">
          <span>Khách hàng vay</span>
          <ChevronRight className="size-3" />
          <span className="font-medium text-[#0F172A]">Hồ sơ vay của tôi</span>
        </nav>

        {/* Page Heading */}
        <div className="flex flex-wrap items-center justify-between gap-4">
          <div className="flex flex-col gap-1.5">
            <h1 className="text-[28px] sm:text-[30px] font-normal tracking-[-0.8px] text-[#0F172A]">
              Hồ sơ vay của tôi
            </h1>
            <p className="text-[14px] text-[#64748B]">
              Theo dõi kết quả kiểm tra tự động của các hồ sơ vay bạn đã nộp và
              tải bổ sung giấy tờ khi thiếu.
            </p>
          </div>
          <button
            type="button"
            onClick={() => navigate("/customer/select-package")}
            className="h-[44px] px-[18px] rounded-[6px] bg-[#0284C7] hover:bg-[#0369A1] text-[12px] font-semibold text-white transition-colors shrink-0"
          >
            + Đăng ký gói vay mới
          </button>
        </div>

        {/* 4 Metric Cards */}
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-5">
          <div className="bg-white border border-[#E2E8F0] rounded-[12px] p-5 flex flex-col gap-3">
            <div className="flex items-center justify-between">
              <span className="text-[13px] font-medium text-[#475569]">
                Hồ sơ đã nộp
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
                Sẵn sàng thẩm định
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
                Chưa đầy đủ (cần bù)
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
                onChange={(e) => setSearch(e.target.value)}
                placeholder="Tìm theo mã hồ sơ của bạn (VD: APP-2026-001)..."
                className="w-full text-[13px] text-[#0F172A] placeholder:text-[#94A3B8] focus:outline-none bg-transparent"
              />
            </div>
            <span className="text-[12px] text-[#64748B]">
              {filtered.length} hồ sơ
            </span>
          </div>

          {/* Status Filter Tabs */}
          <div className="px-6 border-b border-[#E2E8F0] flex items-center gap-7 overflow-x-auto">
            {tabs.map((t) => {
              const active = statusFilter === t.key;
              return (
                <button
                  key={t.key}
                  type="button"
                  onClick={() => setStatusFilter(t.key)}
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
                  <th className="py-3.5 px-4">Phiên bản</th>
                  <th className="py-3.5 px-4">Gói vay &amp; Ghi chú kiểm tra</th>
                  <th className="py-3.5 px-4">Ngày nộp</th>
                  <th className="py-3.5 px-4">Trạng thái</th>
                  <th className="py-3.5 pl-4 pr-6">Hành động</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-[#E2E8F0] text-[13px]">
                {filtered.length === 0 ? (
                  <tr>
                    <td
                      colSpan={6}
                      className="py-10 text-center text-[#64748B]"
                    >
                      Không tìm thấy hồ sơ nào phù hợp.
                    </td>
                  </tr>
                ) : (
                  filtered.map((d) => {
                    const isIncomplete = d.completenessStatus === "INCOMPLETE";
                    const isReviewNeeded =
                      d.completenessStatus === "REVIEW_NEEDED";
                    const needsSupplement = isIncomplete || isReviewNeeded;

                    return (
                      <tr
                        key={d.applicationId}
                        className="hover:bg-[#F8FAFC]/80 transition-colors"
                      >
                        <td className="py-5 pl-6 pr-4 font-semibold text-[#0284C7]">
                          <button
                            type="button"
                            onClick={() => setSelectedDetail(d)}
                            className="hover:underline"
                          >
                            {d.applicationId}
                          </button>
                        </td>
                        <td className="py-5 px-4 font-medium text-[#0F172A]">
                          Phiên bản v{d.version} · {d.documents?.length || 0}{" "}
                          tệp
                        </td>
                        <td className="py-5 px-4 text-[#475569]">
                          Vay tín chấp ·{" "}
                          {d.summaryNote || "Đang chờ kết quả rà soát"}
                        </td>
                        <td className="py-5 px-4 text-[#64748B]">
                          {formatDateVi(d.submissionDate)}
                        </td>
                        <td className="py-5 px-4">
                          <CompletenessStatusBadge
                            status={d.completenessStatus}
                            role="CUSTOMER"
                          />
                        </td>
                        <td className="py-5 pl-4 pr-6">
                          {needsSupplement ? (
                            <button
                              type="button"
                              onClick={() =>
                                navigate(
                                  `/customer/upload?applicationId=${encodeURIComponent(
                                    d.applicationId
                                  )}&mode=supplement`
                                )
                              }
                              className={`h-[34px] px-3.5 rounded-[6px] text-[12px] font-semibold transition-colors whitespace-nowrap ${
                                isIncomplete
                                  ? "bg-[#0284C7] hover:bg-[#0369A1] text-white"
                                  : "bg-white border border-[#7DD3FC] hover:bg-[#F0F9FF] text-[#0284C7]"
                              }`}
                            >
                              Nộp bổ sung
                            </button>
                          ) : (
                            <button
                              type="button"
                              onClick={() => setSelectedDetail(d)}
                              className="h-[34px] px-3.5 rounded-[6px] border border-[#E2E8F0] bg-[#F8FAFC] hover:bg-[#F1F5F9] text-[12px] font-medium text-[#475569] whitespace-nowrap"
                            >
                              Xem chi tiết
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

          {/* Pagination Footer */}
          <div className="px-6 py-4 border-t border-[#E2E8F0] flex flex-wrap items-center justify-between gap-4 text-[12px] text-[#64748B]">
            <span>
              Hiển thị 1–{filtered.length} trên tổng số {myDossiers.length} hồ
              sơ của bạn
            </span>
            <div className="flex items-center gap-1.5">
              <button
                type="button"
                disabled
                className="size-8 rounded-[6px] border border-[#E2E8F0] flex items-center justify-center opacity-50"
              >
                <ChevronLeft className="size-3.5" />
              </button>
              <button
                type="button"
                className="size-8 rounded-[6px] bg-[#F0F9FF] border border-[#BAE6FD] text-[#0284C7] font-semibold"
              >
                1
              </button>
              <button
                type="button"
                disabled
                className="size-8 rounded-[6px] border border-[#E2E8F0] flex items-center justify-center opacity-50"
              >
                <ChevronRight className="size-3.5" />
              </button>
            </div>
          </div>
        </div>
      </div>

      {/* Customer Detail Read-Only Drawer / Modal */}
      {selectedDetail && (
        <div
          className="fixed inset-0 z-50 bg-[#0F172A]/45 flex items-center justify-center p-4"
          role="dialog"
          aria-modal="true"
        >
          <div className="w-full max-w-[600px] bg-white border border-[#E2E8F0] rounded-[14px] shadow-xl overflow-hidden">
            <div className="px-6 py-4 border-b border-[#E2E8F0] flex items-center justify-between">
              <div>
                <h3 className="text-[16px] font-semibold text-[#0F172A]">
                  Chi tiết hồ sơ {selectedDetail.applicationId} (v
                  {selectedDetail.version})
                </h3>
                <p className="text-[12px] text-[#64748B] mt-0.5">
                  Kết quả kiểm tra 4 nhóm chứng từ và mã toàn vẹn SHA-256
                </p>
              </div>
              <button
                type="button"
                onClick={() => setSelectedDetail(null)}
                className="p-1 text-[#64748B] hover:text-[#0F172A]"
              >
                <X className="size-4" />
              </button>
            </div>

            <div className="p-6 flex flex-col gap-3 max-h-[65vh] overflow-auto">
              {(selectedDetail.documents || []).map((doc) => {
                const tMeta =
                  DOC_TYPE_META[normalizeDocType(doc.docType)] ||
                  DOC_TYPE_META.UNKNOWN;
                return (
                  <div
                    key={doc.fileId}
                    className="border border-[#E2E8F0] rounded-[8px] p-3.5 flex flex-col gap-1.5"
                  >
                    <div className="flex items-center justify-between gap-2">
                      <span className="text-[13px] font-semibold text-[#0F172A]">
                        {tMeta.shortTitle} · {doc.fileName}
                      </span>
                      <DocStatusBadge status={doc.status} />
                    </div>
                    <p className="text-[12px] text-[#475569]">
                      {doc.reason || tMeta.validReason}
                    </p>
                    <p className="font-mono text-[10px] text-[#64748B] truncate">
                      SHA-256: {doc.fileHashSha256}
                    </p>
                  </div>
                );
              })}
            </div>

            <div className="px-6 py-4 bg-[#F8FAFC] border-t border-[#E2E8F0] flex items-center justify-end gap-3">
              <button
                type="button"
                onClick={() => setSelectedDetail(null)}
                className="px-4 py-2 rounded-[8px] border border-[#E2E8F0] bg-white text-[12px] font-medium text-[#475569]"
              >
                Đóng
              </button>
              {selectedDetail.completenessStatus !==
                "READY_FOR_CREDIT_REVIEW" && (
                <button
                  type="button"
                  onClick={() => {
                    const id = selectedDetail.applicationId;
                    setSelectedDetail(null);
                    navigate(
                      `/customer/upload?applicationId=${encodeURIComponent(
                        id
                      )}&mode=supplement`
                    );
                  }}
                  className="px-4 py-2 rounded-[8px] bg-[#0284C7] text-[12px] font-semibold text-white"
                >
                  Nộp bổ sung hồ sơ
                </button>
              )}
            </div>
          </div>
        </div>
      )}
    </AppLayout>
  );
}
