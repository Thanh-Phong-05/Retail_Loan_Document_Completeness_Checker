import { useEffect, useMemo, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import {
  ArrowRight,
  CheckCircle2,
  ChevronRight,
  CircleAlert,
  CircleCheck,
  Clock,
  Eye,
  History,
} from "lucide-react";
import AppLayout from "../components/AppLayout";
import ConfirmHandoffModal from "../components/ConfirmHandoffModal";
import DocumentPreviewCanvas from "../components/DocumentPreviewCanvas";
import RequestSupplementModal from "../components/RequestSupplementModal";
import {
  CompletenessStatusBadge,
  DocStatusBadge,
} from "../components/StatusBadge";
import {
  DISCLAIMER_TEXT,
  DOC_TYPE_META,
  REQUIRED_DOC_ORDER,
  normalizeDocStatus,
  normalizeDocType,
} from "../constants/statusCodes";
import { useAuth } from "../context/AuthContext";
import { useDossiers } from "../context/DossierContext";

function formatDateTimeVi(isoStr) {
  if (!isoStr) return "19/11/2026 14:32";
  const dt = new Date(isoStr);
  if (Number.isNaN(dt.getTime())) return isoStr;
  return new Intl.DateTimeFormat("vi-VN", {
    day: "2-digit",
    month: "2-digit",
    year: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  }).format(dt);
}

export default function ReviewWorkspacePage() {
  const { applicationId } = useParams();
  const navigate = useNavigate();
  const { user } = useAuth();
  const {
    dossiers,
    getDossier,
    fetchDossierDetail,
    fileUrlsByFileId,
    useMockApi,
    confirmHandoff,
    requestSupplement,
  } = useDossiers();

  const dossier =
    getDossier(applicationId) ||
    dossiers[0] ||
    null;

  const [selectedFileId, setSelectedFileId] = useState(null);
  const [currentPage, setCurrentPage] = useState(1);
  const [highlightPulse, setHighlightPulse] = useState(false);
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [supplementOpen, setSupplementOpen] = useState(false);
  const [actionSuccessMsg, setActionSuccessMsg] = useState("");

  useEffect(() => {
    if (applicationId && !useMockApi) {
      fetchDossierDetail(applicationId).catch(() => {});
    }
  }, [applicationId, useMockApi]);

  const checklistItems = useMemo(() => {
    if (!dossier) return [];
    const docs = dossier.documents || [];
    const byType = new Map();
    const extras = [];

    docs.forEach((doc) => {
      const normalizedType = normalizeDocType(doc.docType);
      if (
        REQUIRED_DOC_ORDER.includes(normalizedType) &&
        !byType.has(normalizedType)
      ) {
        byType.set(normalizedType, doc);
      } else {
        extras.push(doc);
      }
    });

    const ordered = REQUIRED_DOC_ORDER.map((typeCode) => {
      const doc = byType.get(typeCode);
      const meta = DOC_TYPE_META[typeCode];
      if (!doc) {
        return {
          key: typeCode,
          title: meta.title,
          docType: typeCode,
          status: "MISSING_DOCUMENT",
          reason: `Khách hàng chưa tải lên tệp ${meta.shortTitle}.`,
          pageAnchor: 1,
          document: null,
        };
      }
      const status = normalizeDocStatus(doc.status);
      return {
        key: doc.fileId,
        title: meta.title,
        docType: typeCode,
        status,
        reason:
          doc.reason ||
          (status === "VALID"
            ? meta.validReason
            : `Phát hiện cảnh báo ${status}.`),
        pageAnchor: doc.pageAnchor || 1,
        document: doc,
      };
    });

    extras.forEach((doc, idx) => {
      const status = normalizeDocStatus(doc.status);
      ordered.push({
        key: doc.fileId || `extra-${idx}`,
        title: `Tài liệu bổ sung: ${doc.fileName}`,
        docType: normalizeDocType(doc.docType),
        status,
        reason: doc.reason || "Tài liệu đính kèm thêm.",
        pageAnchor: doc.pageAnchor || 1,
        document: doc,
      });
    });

    return ordered;
  }, [dossier]);

  // Select default document (prefer first error doc if any, else first doc)
  useEffect(() => {
    if (!checklistItems.length) return;
    const errorItem = checklistItems.find(
      (i) => i.document && i.status !== "VALID"
    );
    const initial =
      errorItem || checklistItems.find((i) => i.document) || null;
    if (initial?.document) {
      setSelectedFileId(initial.document.fileId);
      setCurrentPage(initial.pageAnchor || 1);
    }
  }, [dossier?.applicationId, dossier?.version]);

  if (!dossier) {
    return (
      <AppLayout>
        <div className="bg-white border border-[#E2E8F0] rounded-[12px] p-10 text-center flex flex-col items-center gap-3">
          <p className="text-[15px] font-semibold text-[#0F172A]">
            Không tìm thấy hồ sơ {applicationId}
          </p>
          <button
            type="button"
            onClick={() => navigate("/admin/dashboard")}
            className="px-4 py-2 rounded-[8px] bg-[#0284C7] text-white text-[13px] font-semibold"
          >
            Quay lại Danh sách hồ sơ
          </button>
        </div>
      </AppLayout>
    );
  }

  const activeDoc =
    (dossier.documents || []).find((d) => d.fileId === selectedFileId) ||
    dossier.documents?.[0] ||
    null;

  const validCount = checklistItems.filter((i) => i.status === "VALID").length;
  const issueCount = checklistItems.length - validCount;

  const handleJumpToPage = (item) => {
    if (!item.document) return;
    setSelectedFileId(item.document.fileId);
    setCurrentPage(item.pageAnchor || 1);
    setHighlightPulse(true);
    window.setTimeout(() => setHighlightPulse(false), 1400);
  };


const handleConfirmHandoff = async (note) => {
  setActionSuccessMsg("");

  const result = await confirmHandoff(dossier.applicationId, {
    reviewerName: user?.fullName || "Alex Morgan",
    note,
  });

  if (useMockApi || result?.state === "COMPLETED") {
    setActionSuccessMsg(
      `Đã xác nhận bàn giao hồ sơ ${dossier.applicationId} thành công.`
    );
    return;
  }

  if (result?.httpStatus === 202) {
    setActionSuccessMsg(
      `Backend đã tiếp nhận yêu cầu xác nhận hồ sơ ${dossier.applicationId}. ` +
      `Trạng thái: ${result.state || "PENDING"}. Chưa hoàn tất bàn giao.`
    );
    return;
  }

  throw new Error("Phản hồi xác nhận từ Backend không hợp lệ.");
};


  const handleRequestSupplement = async (reason) => {
    await requestSupplement(dossier.applicationId, {
      reviewerName: user?.fullName || "Alex Morgan",
      reason,
    });
    setActionSuccessMsg(
      `Đã gửi yêu cầu bổ sung cho hồ sơ ${dossier.applicationId} (INCOMPLETE).`
    );
  };

  const isAlreadyReady =
    dossier.completenessStatus === "READY_FOR_CREDIT_REVIEW";

  return (
    <AppLayout>
      <div className="flex flex-col gap-6 w-full max-w-[1600px] mx-auto">
        {/* Breadcrumbs */}
        <nav className="flex items-center gap-2 text-[12px] text-[#64748B]">
          <button
            type="button"
            onClick={() => navigate("/admin/queue")}
            className="hover:text-[#0F172A]"
          >
            Hàng đợi xác minh
          </button>
          <ChevronRight className="size-3" />
          <span className="font-medium text-[#0F172A]">
            {dossier.applicationId}
          </span>
        </nav>

        {/* Heading & Actions */}
        <div className="flex flex-wrap items-center justify-between gap-4">
          <div className="flex flex-col gap-2.5">
            <h1 className="text-[26px] sm:text-[28px] font-semibold tracking-[-0.5px] text-[#0F172A]">
              {dossier.applicationId} - {dossier.applicantName}
            </h1>
            <div className="flex flex-wrap items-center gap-3">
              <CompletenessStatusBadge
                status={dossier.completenessStatus}
                role="OFFICER"
              />
              <span className="text-[12px] text-[#64748B]">
                Rà soát tính đầy đủ của hồ sơ vay · Phiên bản v{dossier.version}
              </span>
            </div>
          </div>

          <div className="flex flex-wrap items-center gap-2.5">
            <button
              type="button"
              onClick={() => setSupplementOpen(true)}
              className="h-[44px] px-4 rounded-[6px] border border-[#E2E8F0] bg-white hover:bg-[#F8FAFC] text-[12px] font-semibold text-[#475569] transition-colors"
            >
              Yêu cầu bổ sung
            </button>
            <button
              type="button"
              onClick={() => setConfirmOpen(true)}
              className="h-[44px] px-[18px] rounded-[6px] bg-[#0284C7] hover:bg-[#0369A1] text-[12px] font-semibold text-white inline-flex items-center gap-2 transition-colors"
            >
              <span>Xác nhận &amp; Chuyển xử lý</span>
              <ArrowRight className="size-4" />
            </button>
          </div>
        </div>

        {(actionSuccessMsg || isAlreadyReady) && (
          <div className="rounded-[10px] border border-[#BBF7D0] bg-[#F0FDF4] px-4 py-3 flex items-center gap-2.5 text-[12px] text-[#15803D]">
            <CheckCircle2 className="size-4 shrink-0" />
            <span>
              {actionSuccessMsg ||
                "Hồ sơ này đang ở trạng thái READY_FOR_CREDIT_REVIEW (Sẵn sàng thẩm định tín dụng)."}
            </span>
          </div>
        )}

        {/* Main 2-Column Review Workspace */}
        <div className="grid grid-cols-1 lg:grid-cols-[435px_1fr] gap-6 items-stretch">
          {/* Left Checklist Card */}
          <section className="bg-white border border-[#E2E8F0] rounded-[12px] flex flex-col justify-between overflow-hidden">
            <div>
              {/* Checklist Heading */}
              <div className="px-[22px] pt-[18px] pb-4 border-b border-[#E2E8F0] flex flex-col gap-2">
                <h2 className="text-[16px] font-semibold text-[#0F172A]">
                  Checklist Rà soát Hồ sơ ({checklistItems.length}/4)
                </h2>

                <div className="flex flex-wrap items-center gap-1.5 text-[12px]">
                  <span className="font-semibold text-[#15803D]">
                    {validCount} hợp lệ
                  </span>
                  <span className="text-[#94A3B8]">·</span>
                  <span
                    className={`font-semibold ${
                      issueCount > 0 ? "text-[#DC2626]" : "text-[#15803D]"
                    }`}
                  >
                    {issueCount} {issueCount > 0 ? "cần xử lý" : "lỗi"}
                  </span>
                  <span className="text-[#64748B] text-[11px]">
                    — Đã rà soát {checklistItems.length} mục
                  </span>
                </div>

                {/* Evaluation Timestamp */}
                <div className="flex items-center gap-1.5 text-[11px] text-[#64748B]">
                  <span className="size-1.5 rounded-full bg-[#0284C7] shrink-0" />
                  <Clock className="size-3 shrink-0" />
                  <span>
                    Thời gian đánh giá:{" "}
                    {formatDateTimeVi(
                      dossier.evaluatedAt || dossier.versionCreatedAt
                    )}{" "}
                    · AI/OCR &amp; DMN (Phiên bản v{dossier.version})
                  </span>
                </div>
              </div>

              {/* 4 Checklist Cards */}
              <div className="p-[18px] pb-3 flex flex-col gap-3">
                {checklistItems.map((item) => {
                  const isValid = item.status === "VALID";
                  const isError =
                    item.status === "PERIOD_GAP" ||
                    item.status === "MISSING_BACK_SIDE" ||
                    item.status === "MISSING_DOCUMENT";
                  const isSelected =
                    item.document && item.document.fileId === activeDoc?.fileId;

                  const cardBorder = isValid
                    ? "border-[#BBF7D0] bg-white"
                    : isError
                    ? "border-[#FECACA] bg-[#FEF2F2]/40"
                    : "border-[#FDE68A] bg-[#FFFBEB]/40";

                  return (
                    <div
                      key={item.key}
                      onClick={() => handleJumpToPage(item)}
                      className={`rounded-[10px] border p-4 flex flex-col gap-2.5 transition-all ${cardBorder} ${
                        item.document ? "cursor-pointer hover:shadow-xs" : ""
                      } ${isSelected ? "ring-2 ring-[#0284C7]/35" : ""}`}
                    >
                      <div className="flex items-center gap-2.5">
                        {isValid ? (
                          <CircleCheck className="size-[18px] text-[#15803D] shrink-0" />
                        ) : (
                          <CircleAlert
                            className={`size-[18px] shrink-0 ${
                              isError ? "text-[#DC2626]" : "text-[#B45309]"
                            }`}
                          />
                        )}
                        <span className="text-[14px] font-semibold text-[#0F172A]">
                          {item.title}
                        </span>
                      </div>

                      <div>
                        <DocStatusBadge status={item.status} />
                      </div>

                      <p
                        className={`text-[12px] leading-[1.45] ${
                          isValid
                            ? "text-[#475569]"
                            : isError
                            ? "text-[#DC2626]"
                            : "text-[#B45309]"
                        }`}
                      >
                        {item.reason}
                      </p>

                      {!isValid && item.document && (
                        <button
                          type="button"
                          onClick={(e) => {
                            e.stopPropagation();
                            handleJumpToPage(item);
                          }}
                          className="inline-flex items-center gap-1.5 text-[12px] font-semibold text-[#0284C7] hover:underline w-fit"
                        >
                          <Eye className="size-[15px]" />
                          <span>Xem lỗi tại Trang {item.pageAnchor} →</span>
                        </button>
                      )}
                    </div>
                  );
                })}
              </div>
            </div>

            {/* Bottom Disclaimer Box matching Figma */}
            <div className="px-[18px] pt-1 pb-4">
              <div className="bg-[#FFFBEB] border border-[#FDE68A] rounded-[8px] px-3 py-2.5">
                <p className="text-[11px] font-medium text-[#92400E] leading-[1.45]">
                  {DISCLAIMER_TEXT}
                </p>
              </div>
            </div>
          </section>

          {/* Right PDF Viewer */}
          <section className="min-h-[736px]">
            <DocumentPreviewCanvas
              applicationId={dossier.applicationId}
              applicantName={dossier.applicantName}
              document={activeDoc}
              currentPage={currentPage}
              onPageChange={setCurrentPage}
              localFileUrl={
                activeDoc ? fileUrlsByFileId[activeDoc.fileId] : null
              }
              useMockApi={useMockApi}
              highlightPulse={highlightPulse}
            />
          </section>
        </div>

        {/* Compact Audit Log History */}
        {dossier.auditLogs?.length > 0 && (
          <section className="bg-white border border-[#E2E8F0] rounded-[12px] p-5 flex flex-col gap-3">
            <div className="flex items-center gap-2 text-[13px] font-semibold text-[#0F172A]">
              <History className="size-4 text-[#0284C7]" />
              <span>Nhật ký kiểm toán (Audit Logs)</span>
            </div>
            <div className="flex flex-col gap-2">
              {dossier.auditLogs.map((log) => (
                <div
                  key={log.auditLogId}
                  className="text-[12px] flex flex-wrap items-center justify-between gap-2 border-b border-[#F1F5F9] last:border-b-0 pb-2 last:pb-0"
                >
                  <div className="flex items-center gap-2">
                    <span className="font-mono text-[11px] px-1.5 py-0.5 rounded bg-[#F8FAFC] border border-[#E2E8F0] text-[#475569]">
                      {log.action}
                    </span>
                    <span className="font-medium text-[#0F172A]">
                      {log.actor}:
                    </span>
                    <span className="text-[#475569]">{log.details}</span>
                  </div>
                  <span className="text-[11px] text-[#64748B]">
                    {formatDateTimeVi(log.createdAt)}
                  </span>
                </div>
              ))}
            </div>
          </section>
        )}
      </div>

      {/* Screen 7: Confirm & Handoff Note Modal */}
      <ConfirmHandoffModal
        isOpen={confirmOpen}
        onClose={() => setConfirmOpen(false)}
        dossier={dossier}
        reviewerName={user?.fullName || "Alex Morgan"}
        onConfirm={handleConfirmHandoff}
      />

      {/* Supplement Request Modal */}
      <RequestSupplementModal
        isOpen={supplementOpen}
        onClose={() => setSupplementOpen(false)}
        dossier={dossier}
        onSubmit={handleRequestSupplement}
      />
    </AppLayout>
  );
}
