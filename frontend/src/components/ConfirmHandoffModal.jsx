import { useEffect, useState } from "react";
import { ArrowRight, X } from "lucide-react";
import { DISCLAIMER_TEXT } from "../constants/statusCodes";

function formatNow() {
  return new Intl.DateTimeFormat("vi-VN", {
    day: "2-digit",
    month: "2-digit",
    year: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  }).format(new Date());
}

export default function ConfirmHandoffModal({
  isOpen,
  onClose,
  dossier,
  reviewerName,
  onConfirm,
}) {
  const [note, setNote] = useState(
    "Đã kiểm tra đối chiếu CCCD 2 mặt, Hợp đồng lao động có mộc đỏ và hồ sơ vay vốn. Đủ điều kiện bàn giao bộ phận Thẩm định tín dụng."
  );
  const [error, setError] = useState("");
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (isOpen) {
      setError("");
    }
  }, [isOpen]);

  if (!isOpen || !dossier) return null;

  const handleSubmit = async (e) => {
    e.preventDefault();
    if (!note.trim()) {
      setError("Vui lòng nhập ghi chú xác nhận trước khi bàn giao tín dụng.");
      return;
    }
    setSubmitting(true);
    setError("");
    try {
      await onConfirm(note.trim());
      onClose();
    } catch (err) {
      setError(
        err?.response?.data?.message ||
          err?.message ||
          "Không thể xác nhận bàn giao. Vui lòng thử lại."
      );
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div
      className="fixed inset-0 z-50 bg-[#0F172A]/50 flex items-center justify-center p-4"
      role="dialog"
      aria-modal="true"
      aria-labelledby="confirm-handoff-title"
    >
      <form
        onSubmit={handleSubmit}
        className="w-full max-w-[540px] bg-white border border-[#E2E8F0] rounded-[14px] shadow-[0_20px_40px_-8px_rgba(15,23,42,0.2)] overflow-hidden"
      >
        {/* Modal Header */}
        <div className="px-6 pt-[22px] pb-[18px] border-b border-[#E2E8F0] flex items-start justify-between gap-4 bg-white">
          <div className="flex flex-col gap-1">
            <h2
              id="confirm-handoff-title"
              className="text-[18px] font-semibold text-[#0F172A] leading-snug"
            >
              Xác nhận &amp; Chuyển xử lý
            </h2>
            <p className="text-[12px] text-[#64748B]">
              Chuyển trạng thái hồ sơ sang{" "}
              <span className="font-medium text-[#0F172A]">
                READY_FOR_CREDIT_REVIEW
              </span>{" "}
              (Bàn giao tín dụng).
            </p>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="p-1 rounded-md text-[#64748B] hover:text-[#0F172A] hover:bg-[#F8FAFC] transition-colors"
            aria-label="Đóng hộp thoại"
          >
            <X className="size-4" />
          </button>
        </div>

        {/* Modal Body */}
        <div className="px-6 py-5 flex flex-col gap-4">
          {/* Summary Info Box */}
          <div className="bg-[#F8FAFC] border border-[#E2E8F0] rounded-[8px] px-3.5 py-3 flex flex-col gap-2">
            <div className="flex items-center justify-between gap-3">
              <span className="text-[11px] text-[#64748B]">
                Mã hồ sơ / Khách hàng
              </span>
              <span className="text-[12px] font-semibold text-[#0F172A] text-right">
                {dossier.applicationId} · {dossier.applicantName} (v
                {dossier.version})
              </span>
            </div>
            <div className="flex items-center justify-between gap-3">
              <span className="text-[11px] text-[#64748B]">
                Cán bộ xác nhận
              </span>
              <span className="text-[12px] font-medium text-[#0F172A] text-right">
                {reviewerName} (Loan Officer)
              </span>
            </div>
            <div className="flex items-center justify-between gap-3">
              <span className="text-[11px] text-[#64748B]">
                Thời gian ghi nhận
              </span>
              <span className="text-[12px] font-medium text-[#0F172A] text-right">
                {formatNow()}
              </span>
            </div>
          </div>

          {/* Note Field */}
          <div className="flex flex-col gap-2">
            <label
              htmlFor="handoff-note"
              className="text-[13px] font-medium text-[#0F172A]"
            >
              Ghi chú xác nhận rà soát{" "}
              <span className="text-[#0284C7]">*</span>
            </label>
            <textarea
              id="handoff-note"
              rows={4}
              value={note}
              onChange={(e) => setNote(e.target.value)}
              placeholder="Nhập nhận xét sau khi đối chiếu chứng từ gốc..."
              className="w-full rounded-[8px] border border-[#CBD5E1] bg-white px-3.5 py-3 text-[13px] text-[#0F172A] placeholder:text-[#94A3B8] focus:outline-none focus:border-[#0284C7] focus:ring-2 focus:ring-[#0284C7]/15 resize-y"
            />
            <p className="text-[11px] text-[#64748B]">
              Ghi chú cùng tên cán bộ và thời gian sẽ được lưu lịch sử không thể
              sửa đổi vào bảng <code className="font-mono">AuditLogs</code>.
            </p>
            {error && (
              <p className="text-[12px] text-[#DC2626] font-medium">{error}</p>
            )}
          </div>

          {/* Disclaimer Box */}
          <div className="bg-[#FFFBEB] border border-[#FDE68A] rounded-[8px] px-3 py-2.5">
            <p className="text-[11px] font-medium text-[#92400E] leading-relaxed">
              {DISCLAIMER_TEXT}
            </p>
          </div>
        </div>

        {/* Modal Footer */}
        <div className="px-6 pt-4 pb-5 bg-[#F8FAFC] border-t border-[#E2E8F0] flex items-center justify-end gap-3">
          <button
            type="button"
            onClick={onClose}
            disabled={submitting}
            className="px-4 py-2.5 rounded-[8px] border border-[#E2E8F0] bg-white text-[13px] font-medium text-[#475569] hover:bg-[#F8FAFC] transition-colors disabled:opacity-60"
          >
            Hủy bỏ
          </button>
          <button
            type="submit"
            disabled={submitting}
            className="px-[18px] py-2.5 rounded-[8px] bg-[#0284C7] hover:bg-[#0369A1] text-[13px] font-semibold text-white inline-flex items-center gap-2 transition-colors disabled:opacity-60"
          >
            <span>
              {submitting ? "Đang lưu xác nhận..." : "Xác nhận bàn giao tín dụng"}
            </span>
            <ArrowRight className="size-4" />
          </button>
        </div>
      </form>
    </div>
  );
}
