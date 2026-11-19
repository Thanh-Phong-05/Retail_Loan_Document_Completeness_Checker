import { useState } from "react";
import { AlertCircle, X } from "lucide-react";

export default function RequestSupplementModal({
  isOpen,
  onClose,
  dossier,
  onSubmit,
}) {
  const [reason, setReason] = useState(
    dossier?.summaryNote ||
      "Vui lòng bổ sung đầy đủ chứng từ còn thiếu / chụp rõ nét hơn và nộp lại phiên bản mới."
  );
  const [submitting, setSubmitting] = useState(false);

  if (!isOpen || !dossier) return null;

  const handleConfirm = async (e) => {
    e.preventDefault();
    if (!reason.trim()) return;
    setSubmitting(true);
    try {
      await onSubmit(reason.trim());
      onClose();
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div
      className="fixed inset-0 z-50 bg-[#0F172A]/50 flex items-center justify-center p-4"
      role="dialog"
      aria-modal="true"
    >
      <form
        onSubmit={handleConfirm}
        className="w-full max-w-[480px] bg-white border border-[#E2E8F0] rounded-[14px] shadow-[0_20px_40px_-8px_rgba(15,23,42,0.2)] overflow-hidden"
      >
        <div className="px-6 py-4 border-b border-[#E2E8F0] flex items-center justify-between">
          <div className="flex items-center gap-2.5">
            <AlertCircle className="size-5 text-[#DC2626]" />
            <h3 className="text-[16px] font-semibold text-[#0F172A]">
              Yêu cầu khách hàng bổ sung
            </h3>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="p-1 text-[#64748B] hover:text-[#0F172A]"
          >
            <X className="size-4" />
          </button>
        </div>

        <div className="px-6 py-5 flex flex-col gap-3">
          <p className="text-[12px] text-[#64748B]">
            Hồ sơ <strong className="text-[#0F172A]">{dossier.applicationId}</strong> sẽ chuyển sang trạng thái{" "}
            <span className="font-medium text-[#DC2626]">INCOMPLETE</span> để khách hàng thấy nút{" "}
            <strong>Nộp bổ sung</strong> tại mục <em>Hồ sơ vay của tôi</em>.
          </p>
          <textarea
            rows={3}
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            className="w-full rounded-[8px] border border-[#CBD5E1] px-3.5 py-2.5 text-[13px] text-[#0F172A] focus:outline-none focus:border-[#0284C7]"
            placeholder="Nhập lý do yêu cầu bổ sung..."
          />
        </div>

        <div className="px-6 py-4 bg-[#F8FAFC] border-t border-[#E2E8F0] flex justify-end gap-3">
          <button
            type="button"
            onClick={onClose}
            className="px-4 py-2 rounded-[8px] border border-[#E2E8F0] bg-white text-[13px] font-medium text-[#475569]"
          >
            Hủy
          </button>
          <button
            type="submit"
            disabled={submitting || !reason.trim()}
            className="px-4 py-2 rounded-[8px] bg-[#DC2626] hover:bg-[#B91C1C] text-[13px] font-semibold text-white disabled:opacity-60"
          >
            {submitting ? "Đang gửi..." : "Gửi yêu cầu bổ sung"}
          </button>
        </div>
      </form>
    </div>
  );
}
