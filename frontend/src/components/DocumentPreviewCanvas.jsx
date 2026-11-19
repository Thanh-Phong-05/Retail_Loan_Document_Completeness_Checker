import { useEffect, useState } from "react";
import {
  AlertTriangle,
  ChevronLeft,
  ChevronRight,
  Download,
  FileText,
  Minus,
  Plus,
} from "lucide-react";
import { normalizeDocStatus, normalizeDocType } from "../constants/statusCodes";
import { getDocumentFileUrl } from "../services/dossierApi";

export default function DocumentPreviewCanvas({
  applicationId,
  applicantName,
  document,
  currentPage,
  onPageChange,
  localFileUrl,
  useMockApi,
  highlightPulse,
}) {
  const [zoom, setZoom] = useState(100);

  useEffect(() => {
    setZoom(100);
  }, [document?.fileId]);

  if (!document) {
    return (
      <div className="bg-white border border-[#E2E8F0] rounded-[12px] h-full min-h-[640px] flex items-center justify-center text-[#64748B] text-[13px]">
        Chọn một chứng từ bên trái để xem nội dung.
      </div>
    );
  }

  const totalPages = Math.max(document.pageCount || 1, document.pageAnchor || 1, 1);
  const docType = normalizeDocType(document.docType);
  const docStatus = normalizeDocStatus(document.status);
  const isErrorAtThisPage =
    docStatus !== "VALID" && currentPage === (document.pageAnchor || 1);

  const ext = document.fileName?.split(".").pop()?.toUpperCase() || "PDF";
  const isImage =
    document.contentType?.startsWith("image/") ||
    ["PNG", "JPG", "JPEG"].includes(ext);

  const resolvedFileUrl =
    localFileUrl ||
    (!useMockApi ? getDocumentFileUrl(applicationId, document.fileId) : null);

  const handleDownload = () => {
    if (resolvedFileUrl) {
      const a = window.document.createElement("a");
      a.href = resolvedFileUrl;
      a.download = document.fileName || "document.pdf";
      a.click();
      return;
    }
    const content = [
      `MERIDIAN BANK - BAN SAO MINH HOA CHUNG TU`,
      `Ma ho so: ${applicationId}`,
      `Khach hang: ${applicantName}`,
      `Ten tep: ${document.fileName}`,
      `Loai giay to: ${docType}`,
      `Trang thai: ${docStatus}`,
      `Ly do: ${document.reason || "Hop le"}`,
      `SHA-256: ${document.fileHashSha256}`,
    ].join("\n");
    const blob = new Blob([content], { type: "text/plain;charset=utf-8" });
    const url = URL.createObjectURL(blob);
    const a = window.document.createElement("a");
    a.href = url;
    a.download = `${document.fileName || "document"}.txt`;
    a.click();
    URL.revokeObjectURL(url);
  };

  return (
    <div className="bg-white border border-[#E2E8F0] rounded-[12px] overflow-hidden flex flex-col h-full">
      {/* Dark PDF Toolbar matching Figma */}
      <div className="bg-[#1E293B] text-white px-[18px] py-3 flex flex-col gap-2.5 shrink-0">
        <div className="flex items-center justify-between gap-3">
          <div className="flex items-center gap-2.5 min-w-0">
            <FileText className="size-4 text-[#7DD3FC] shrink-0" />
            <span className="text-[12px] font-medium truncate">
              {document.fileName}
            </span>
          </div>
          <span className="text-[10px] font-semibold tracking-wider text-[#94A3B8] uppercase shrink-0">
            {ext}
          </span>
        </div>

        <div className="flex items-center justify-between text-[12px] text-[#CBD5E1]">
          <div className="flex items-center gap-2">
            <button
              type="button"
              disabled={currentPage <= 1}
              onClick={() => onPageChange(Math.max(1, currentPage - 1))}
              className="p-1 rounded hover:bg-white/10 disabled:opacity-40"
              title="Trang trước"
            >
              <ChevronLeft className="size-3.5" />
            </button>
            <span className="min-w-[78px] text-center font-medium text-white">
              Trang {currentPage} / {totalPages}
            </span>
            <button
              type="button"
              disabled={currentPage >= totalPages}
              onClick={() => onPageChange(Math.min(totalPages, currentPage + 1))}
              className="p-1 rounded hover:bg-white/10 disabled:opacity-40"
              title="Trang tiếp"
            >
              <ChevronRight className="size-3.5" />
            </button>
          </div>

          <div className="flex items-center gap-2.5">
            <button
              type="button"
              onClick={() => setZoom((z) => Math.max(70, z - 10))}
              className="p-1 rounded hover:bg-white/10"
              title="Thu nhỏ"
            >
              <Minus className="size-3.5" />
            </button>
            <span className="w-10 text-center text-white font-medium">
              {zoom}%
            </span>
            <button
              type="button"
              onClick={() => setZoom((z) => Math.min(140, z + 10))}
              className="p-1 rounded hover:bg-white/10"
              title="Phóng to"
            >
              <Plus className="size-3.5" />
            </button>
          </div>

          <button
            type="button"
            onClick={handleDownload}
            className="inline-flex items-center gap-1.5 text-[12px] font-medium text-white hover:text-[#7DD3FC] transition-colors"
          >
            <Download className="size-3.5" />
            <span>Tải xuống</span>
          </button>
        </div>
      </div>

      {/* Document Canvas */}
      <div className="bg-[#CBD5E1]/45 flex-1 p-5 overflow-auto flex flex-col items-center gap-4 min-h-[600px]">
        {isErrorAtThisPage && (
          <div
            className={`w-full max-w-[760px] rounded-[8px] border border-[#FECACA] bg-[#FEF2F2] px-3.5 py-2.5 flex items-center gap-2.5 text-[12px] text-[#DC2626] transition-all ${
              highlightPulse ? "ring-4 ring-[#DC2626]/25 scale-[1.01]" : ""
            }`}
          >
            <AlertTriangle className="size-4 shrink-0" />
            <span>
              <strong>Page Anchor (Trang {currentPage}):</strong>{" "}
              {document.reason}
            </span>
          </div>
        )}

        {resolvedFileUrl ? (
          isImage ? (
            <div className="bg-white p-3 rounded-lg shadow-sm border border-[#E2E8F0] overflow-auto max-w-full">
              <img
                src={resolvedFileUrl}
                alt={document.fileName}
                style={{ width: `${(540 * zoom) / 100}px` }}
                className="h-auto block mx-auto"
              />
            </div>
          ) : (
            <iframe
              key={`${resolvedFileUrl}#page=${currentPage}`}
              src={`${resolvedFileUrl}#page=${currentPage}&zoom=${zoom}`}
              title={document.fileName}
              className="w-full max-w-[760px] h-[660px] bg-white rounded-lg border border-[#E2E8F0] shadow-sm"
            />
          )
        ) : (
          <IllustrativeMockDocument
            applicationId={applicationId}
            applicantName={applicantName}
            document={document}
            docType={docType}
            docStatus={docStatus}
            currentPage={currentPage}
            totalPages={totalPages}
            zoom={zoom}
            highlightPulse={highlightPulse}
          />
        )}
      </div>
    </div>
  );
}

function IllustrativeMockDocument({
  applicationId,
  applicantName,
  document,
  docType,
  docStatus,
  currentPage,
  totalPages,
  zoom,
  highlightPulse,
}) {
  const isErrorAnchor = docStatus !== "VALID" && currentPage === document.pageAnchor;

  return (
    <div
      style={{ transform: `scale(${zoom / 100})`, transformOrigin: "top center" }}
      className={`w-full max-w-[760px] bg-white border border-[#E2E8F0] rounded-[6px] shadow-[0_4px_16px_rgba(15,23,42,0.08)] p-[30px] flex flex-col gap-5 text-[#0F172A] transition-all ${
        highlightPulse && isErrorAnchor ? "ring-4 ring-[#DC2626]/30" : ""
      }`}
    >
      {/* Letterhead */}
      <div className="flex items-start justify-between border-b border-[#E2E8F0] pb-4">
        <div>
          <div className="text-[16px] font-bold tracking-tight text-[#0F172A]">
            meridian
          </div>
          <div className="text-[10px] text-[#64748B] mt-0.5">
            NGÂN HÀNG MINH HỌA · CN. TP. HỒ CHÍ MINH
          </div>
        </div>
        <span className="text-[9px] uppercase tracking-wider text-[#64748B] bg-[#F8FAFC] border border-[#E2E8F0] px-2 py-1 rounded">
          TÀI LIỆU MẪU / PHỤC VỤ RÀ SOÁT
        </span>
      </div>

      {docType === "BANK_STATEMENT" ? (
        <>
          <div className="text-center">
            <h4 className="text-[16px] font-bold tracking-tight uppercase">
              SAO KÊ TÀI KHOẢN NHẬN LƯƠNG
            </h4>
            <p className="text-[11px] text-[#64748B] mt-1">
              Kỳ đối chiếu: 01/08/2026 – 31/10/2026 · Đơn vị tiền tệ: VNĐ
            </p>
          </div>

          <div className="grid grid-cols-[120px_1fr] gap-y-2 text-[11px]">
            <span className="text-[#64748B]">Tên chủ tài khoản:</span>
            <span className="font-semibold uppercase">{applicantName}</span>
            <span className="text-[#64748B]">Số tài khoản:</span>
            <span className="font-medium">•••• •••• 0123 (tài khoản mẫu)</span>
            <span className="text-[#64748B]">Đơn vị trả lương:</span>
            <span className="font-medium">Công ty TNHH Minh Họa</span>
          </div>

          <div className="border border-[#E2E8F0] rounded-md overflow-hidden text-[11px]">
            <div className="grid grid-cols-[72px_1fr_90px_90px] bg-[#F8FAFC] border-b border-[#E2E8F0] px-2.5 py-2 font-semibold text-[#475569]">
              <span>Ngày GD</span>
              <span>Nội dung giao dịch</span>
              <span className="text-right">Ghi có (VNĐ)</span>
              <span className="text-right">Số dư (VNĐ)</span>
            </div>

            {docStatus === "PERIOD_GAP" && currentPage === document.pageAnchor && (
              <div className="bg-[#FEF2F2] border-y-2 border-dashed border-[#DC2626] px-3 py-3 flex flex-col gap-1.5">
                <div className="flex items-center justify-between text-[#DC2626] font-semibold">
                  <span>THÁNG {document.missingPeriod || "08/2026"}</span>
                  <span>Chưa có dữ liệu sao kê</span>
                </div>
                <span className="inline-block w-fit rounded bg-[#FECACA]/70 px-2 py-0.5 text-[10px] font-semibold text-[#B91C1C]">
                  {document.reason || "Phát hiện thiếu sao kê tháng 08/2026"}
                </span>
              </div>
            )}

            <div className="grid grid-cols-[72px_1fr_90px_90px] px-2.5 py-2.5 border-b border-[#F1F5F9]">
              <span className="text-[#64748B]">05/09/2026</span>
              <span>Nhận lương tháng 09/2026</span>
              <span className="text-right font-medium text-[#15803D]">25.000.000</span>
              <span className="text-right">31.450.000</span>
            </div>
            <div className="grid grid-cols-[72px_1fr_90px_90px] px-2.5 py-2.5 border-b border-[#F1F5F9]">
              <span className="text-[#64748B]">20/09/2026</span>
              <span>Chuyển khoản thanh toán</span>
              <span className="text-right text-[#64748B]">—</span>
              <span className="text-right">26.450.000</span>
            </div>
            <div className="grid grid-cols-[72px_1fr_90px_90px] px-2.5 py-2.5 border-b border-[#F1F5F9]">
              <span className="text-[#64748B]">05/10/2026</span>
              <span>Nhận lương tháng 10/2026</span>
              <span className="text-right font-medium text-[#15803D]">25.000.000</span>
              <span className="text-right">51.450.000</span>
            </div>
            <div className="grid grid-cols-[72px_1fr_90px_90px] px-2.5 py-2.5 border-b border-[#F1F5F9]">
              <span className="text-[#64748B]">22/10/2026</span>
              <span>Chuyển khoản thanh toán</span>
              <span className="text-right text-[#64748B]">—</span>
              <span className="text-right">43.950.000</span>
            </div>
            <div className="flex items-center justify-between bg-[#F8FAFC] px-2.5 py-2 font-semibold">
              <span>Tổng tiền lương ghi nhận</span>
              <span>
                {docStatus === "PERIOD_GAP" ? "50.000.000 (02 tháng)" : "75.000.000 (03 tháng)"}
              </span>
            </div>
          </div>
        </>
      ) : docType === "NATIONAL_ID" ? (
        <div className="flex flex-col gap-4">
          <div className="text-center">
            <h4 className="text-[15px] font-bold uppercase">
              CĂN CƯỚC CÔNG DÂN ({currentPage === 1 ? "MẶT TRƯỚC" : "MẶT SAU"})
            </h4>
            <p className="text-[11px] text-[#64748B] mt-1">
              Hồ sơ: {applicationId} · Chủ sở hữu: {applicantName}
            </p>
          </div>
          <div
            className={`border-2 rounded-xl p-5 flex flex-col gap-3 ${
              docStatus === "BLURRY"
                ? "border-[#F59E0B] bg-[#FFFBEB]/60 blur-[0.8px]"
                : docStatus === "MISSING_BACK_SIDE"
                ? "border-dashed border-[#DC2626] bg-[#FEF2F2]/60"
                : "border-[#CBD5E1] bg-[#F8FAFC]"
            }`}
          >
            <div className="flex justify-between text-[11px] font-semibold text-[#0284C7]">
              <span>CỘNG HÒA XÃ HỘI CHỦ NGHĨA VIỆT NAM</span>
              <span>Số: 079099001234</span>
            </div>
            <div className="text-[12px] space-y-1.5">
              <p>
                <span className="text-[#64748B]">Họ và tên:</span>{" "}
                <strong className="uppercase">{applicantName}</strong>
              </p>
              <p>
                <span className="text-[#64748B]">Ngày sinh:</span> 12/05/1996 ·{" "}
                <span className="text-[#64748B]">Giới tính:</span> Nam
              </p>
              <p>
                <span className="text-[#64748B]">Nơi thường trú:</span> Quận 1, TP. Hồ Chí Minh
              </p>
            </div>
          </div>
          {isErrorAnchor && (
            <div className="rounded-lg border-2 border-dashed border-[#DC2626] bg-[#FEF2F2] p-3 text-[11px] text-[#B91C1C]">
              <strong>Cảnh báo OCR tại Trang {currentPage}:</strong> {document.reason}
            </div>
          )}
        </div>
      ) : docType === "LABOR_CONTRACT" ? (
        <div className="flex flex-col gap-4 text-[12px]">
          <div className="text-center">
            <h4 className="text-[15px] font-bold uppercase">HỢP ĐỒNG LAO ĐỘNG</h4>
            <p className="text-[11px] text-[#64748B]">
              Số: 108/2026/HĐLĐ · Thời hạn: Không xác định thời hạn
            </p>
          </div>
          <div className="space-y-2 leading-relaxed text-[#334155]">
            <p>
              <strong>Bên A (Người sử dụng lao động):</strong> Công ty TNHH Minh Họa
            </p>
            <p>
              <strong>Bên B (Người lao động):</strong> {applicantName}
            </p>
            <p>
              <strong>Mức lương chính thức:</strong> 25.000.000 VNĐ/tháng (Chuyển khoản qua ngân hàng)
            </p>
          </div>
          <div className="grid grid-cols-2 gap-4 pt-6 border-t border-[#E2E8F0] text-center">
            <div>
              <p className="font-semibold">NGƯỜI LAO ĐỘNG</p>
              <p className="text-[11px] text-[#15803D] mt-4 font-medium">
                (Đã ký) {applicantName}
              </p>
            </div>
            <div>
              <p className="font-semibold">ĐẠI DIỆN CÔNG TY</p>
              <p className="text-[11px] text-[#DC2626] mt-4 font-medium border border-[#FECACA] bg-[#FEF2F2] rounded px-2 py-1 inline-block">
                {docStatus === "MISSING_SIGNATURE"
                  ? "Chưa có chữ ký / dấu mộc đỏ"
                  : "Đã đóng dấu mộc đỏ & ký tên"}
              </p>
            </div>
          </div>
        </div>
      ) : (
        <div className="flex flex-col gap-4 text-[12px]">
          <div className="text-center">
            <h4 className="text-[15px] font-bold uppercase">
              ĐƠN ĐỀ NGHỊ VAY VỐN KIÊM PHƯƠNG ÁN TRẢ NỢ
            </h4>
            <p className="text-[11px] text-[#64748B]">
              Sản phẩm: Vay tín chấp tiêu dùng theo lương ({applicationId})
            </p>
          </div>
          <div className="space-y-2 text-[#334155]">
            <p>
              <strong>Họ tên người vay:</strong> {applicantName}
            </p>
            <p>
              <strong>Mục đích vay:</strong> Tiêu dùng cá nhân
            </p>
            <p>
              <strong>Cam kết của người vay:</strong> Tôi cam đoan toàn bộ giấy tờ
              cung cấp là trung thực và hợp pháp.
            </p>
          </div>
          <div className="pt-6 border-t border-[#E2E8F0] flex justify-end">
            <div className="text-center w-[220px]">
              <p className="font-semibold">NGƯỜI ĐỀ NGHỊ VAY VỐN</p>
              {docStatus === "MISSING_SIGNATURE" && isErrorAnchor ? (
                <div className="mt-3 border-2 border-dashed border-[#DC2626] bg-[#FEF2F2] rounded-md p-2.5 text-[11px] text-[#DC2626] font-semibold">
                  Thiếu chữ ký người vay ở trang cuối
                </div>
              ) : (
                <p className="text-[11px] text-[#15803D] mt-4 font-medium">
                  (Đã ký xác nhận) {applicantName}
                </p>
              )}
            </div>
          </div>
        </div>
      )}

      <div className="flex items-center justify-between text-[10px] text-[#64748B] pt-3 border-t border-[#F1F5F9]">
        <span>Dữ liệu minh họa phục vụ rà soát hồ sơ vay</span>
        <span>
          Trang {currentPage} / {totalPages}
        </span>
      </div>
    </div>
  );
}
