export const PRODUCT_TYPES = {
  UNSECURED_SALARIED_LOAN: "UNSECURED_SALARIED_LOAN",
  HOME_MORTGAGE_LOAN: "HOME_MORTGAGE_LOAN",
  AUTO_LOAN: "AUTO_LOAN",
};

export const LOAN_PACKAGES = [
  {
    code: PRODUCT_TYPES.UNSECURED_SALARIED_LOAN,
    title: "Vay tín chấp tiêu dùng theo lương",
    badge: "Mặc định · Hỗ trợ AI & DMN",
    terms:
      "UNSECURED_SALARIED_LOAN · Lãi suất từ 10.5%/năm · Hạn mức tối đa 500 triệu VNĐ · Kỳ hạn 12 - 60 tháng",
    requiredDocs:
      "Yêu cầu: CCCD 2 mặt, Hợp đồng lao động, Sao kê lương 03 tháng gần nhất, Đơn đề nghị vay vốn.",
    enabled: true,
    defaultAmount: "100.000.000 VNĐ",
    defaultTermBranch: "24 tháng · CN TP. Hồ Chí Minh",
  },
  {
    code: PRODUCT_TYPES.HOME_MORTGAGE_LOAN,
    title: "Vay mua nhà / Bất động sản thế chấp",
    badge: "Tượng trưng · Sắp ra mắt",
    terms:
      "HOME_MORTGAGE_LOAN · Lãi suất từ 7.5%/năm · Hạn mức tối đa 10 tỷ VNĐ · Kỳ hạn tới 240 tháng",
    requiredDocs:
      "Yêu cầu: CCCD 2 mặt, HĐLĐ, Sao kê lương 03 tháng, Đơn vay & Giấy chứng nhận quyền sử dụng đất.",
    enabled: false,
  },
  {
    code: PRODUCT_TYPES.AUTO_LOAN,
    title: "Vay mua ô tô tiêu dùng / Kinh doanh",
    badge: "Tượng trưng · Sắp ra mắt",
    terms:
      "AUTO_LOAN · Lãi suất từ 8.2%/năm · Tài trợ tối đa 80% giá trị xe · Kỳ hạn tới 96 tháng",
    requiredDocs:
      "Yêu cầu: CCCD 2 mặt, HĐLĐ, Sao kê lương 03 tháng, Đơn đề nghị vay vốn & Hợp đồng mua bán xe.",
    enabled: false,
  },
];

export const REQUIRED_DOC_ORDER = [
  "NATIONAL_ID",
  "LABOR_CONTRACT",
  "BANK_STATEMENT",
  "LOAN_APPLICATION",
];

export const DOC_TYPE_META = {
  NATIONAL_ID: {
    code: "NATIONAL_ID",
    order: 1,
    title: "1. CCCD / CMND (2 mặt)",
    shortTitle: "CCCD / CMND",
    requirement: "Đủ 2 mặt (trước/sau), rõ số và không rách góc.",
    validReason: "Đủ 2 mặt trước/sau, rõ nét, khớp thông tin.",
  },
  LABOR_CONTRACT: {
    code: "LABOR_CONTRACT",
    order: 2,
    title: "2. Hợp đồng lao động",
    shortTitle: "Hợp đồng lao động",
    requirement: "Còn hiệu lực, có dấu mộc và chữ ký công ty.",
    validReason: "Còn hiệu lực, có đầy đủ chữ ký và dấu mộc.",
  },
  BANK_STATEMENT: {
    code: "BANK_STATEMENT",
    order: 3,
    title: "3. Sao kê lương (3 tháng gần nhất)",
    shortTitle: "Sao kê lương",
    requirement: "Đủ 3 tháng gần nhất tính đến ngày nộp hồ sơ.",
    validReason: "Đầy đủ giao dịch nhận lương 03 tháng liên tiếp.",
  },
  LOAN_APPLICATION: {
    code: "LOAN_APPLICATION",
    order: 4,
    title: "4. Đơn đăng ký vay",
    shortTitle: "Đơn đăng ký vay",
    requirement: "Đầy đủ thông tin và có chữ ký người vay ở trang cuối.",
    validReason: "Đầy đủ thông tin và có chữ ký người vay.",
  },
  UNKNOWN: {
    code: "UNKNOWN",
    order: 99,
    title: "Tài liệu chưa xác định",
    shortTitle: "Chưa phân loại",
    requirement: "Chờ AI/OCR phân loại hoặc cán bộ kiểm tra thủ công.",
    validReason: "Chưa xác định loại chứng từ.",
  },
};

export const DOC_STATUS_META = {
  VALID: {
    code: "VALID",
    label: "Hợp lệ",
    severity: "success",
    bgClass: "bg-[#F0FDF4]",
    borderClass: "border-[#BBF7D0]",
    textClass: "text-[#15803D]",
    dotClass: "bg-[#15803D]",
  },
  MISSING_BACK_SIDE: {
    code: "MISSING_BACK_SIDE",
    label: "Thiếu mặt sau",
    severity: "error",
    bgClass: "bg-[#FEF2F2]",
    borderClass: "border-[#FECACA]",
    textClass: "text-[#DC2626]",
    dotClass: "bg-[#DC2626]",
  },
  PERIOD_GAP: {
    code: "PERIOD_GAP",
    label: "Thiếu dữ liệu",
    severity: "error",
    bgClass: "bg-[#FEF2F2]",
    borderClass: "border-[#FECACA]",
    textClass: "text-[#DC2626]",
    dotClass: "bg-[#DC2626]",
  },
  BLURRY: {
    code: "BLURRY",
    label: "Ảnh bị mờ",
    severity: "warning",
    bgClass: "bg-[#FFFBEB]",
    borderClass: "border-[#FDE68A]",
    textClass: "text-[#B45309]",
    dotClass: "bg-[#B45309]",
  },
  MISSING_SIGNATURE: {
    code: "MISSING_SIGNATURE",
    label: "Thiếu chữ ký",
    severity: "warning",
    bgClass: "bg-[#FFFBEB]",
    borderClass: "border-[#FDE68A]",
    textClass: "text-[#B45309]",
    dotClass: "bg-[#B45309]",
  },
  MISSING_DOCUMENT: {
    code: "MISSING_DOCUMENT",
    label: "Chưa nộp tệp",
    severity: "error",
    bgClass: "bg-[#FEF2F2]",
    borderClass: "border-[#FECACA]",
    textClass: "text-[#DC2626]",
    dotClass: "bg-[#DC2626]",
  },
  PENDING: {
    code: "PENDING",
    label: "Đang xử lý",
    severity: "neutral",
    bgClass: "bg-[#F8FAFC]",
    borderClass: "border-[#E2E8F0]",
    textClass: "text-[#475569]",
    dotClass: "bg-[#64748B]",
  },
};

export const COMPLETENESS_STATUS_META = {
  INCOMPLETE: {
    code: "INCOMPLETE",
    label: "Thiếu hồ sơ",
    fullLabel: "Hồ sơ chưa đầy đủ",
    bgClass: "bg-[#FEF2F2]",
    borderClass: "border-[#FECACA]",
    textClass: "text-[#DC2626]",
    dotClass: "bg-[#DC2626]",
  },
  REVIEW_NEEDED: {
    code: "REVIEW_NEEDED",
    label: "Cần rà soát",
    fullLabel: "Cần cán bộ rà soát",
    bgClass: "bg-[#FFFBEB]",
    borderClass: "border-[#FDE68A]",
    textClass: "text-[#B45309]",
    dotClass: "bg-[#B45309]",
  },
  READY_FOR_CREDIT_REVIEW: {
    code: "READY_FOR_CREDIT_REVIEW",
    label: "Sẵn sàng phê duyệt",
    customerLabel: "Sẵn sàng thẩm định",
    fullLabel: "Sẵn sàng thẩm định tín dụng",
    bgClass: "bg-[#F0FDF4]",
    borderClass: "border-[#BBF7D0]",
    textClass: "text-[#15803D]",
    dotClass: "bg-[#15803D]",
  },
};

export function normalizeDocType(rawType) {
  if (!rawType) return "UNKNOWN";
  if (rawType === "EMPLOYMENT_CONTRACT") return "LABOR_CONTRACT";
  return DOC_TYPE_META[rawType] ? rawType : "UNKNOWN";
}

export function normalizeDocStatus(rawStatus) {
  if (!rawStatus) return "PENDING";
  if (rawStatus === "MISSING") return "MISSING_BACK_SIDE";
  if (rawStatus === "NO_SIGNATURE") return "MISSING_SIGNATURE";
  return DOC_STATUS_META[rawStatus] ? rawStatus : "PENDING";
}

export function getLoanProductTitle(productType) {
  const found = LOAN_PACKAGES.find((p) => p.code === productType);
  return found ? "Vay tín chấp theo lương" : productType || "Vay tín chấp theo lương";
}

export const DISCLAIMER_TEXT =
  "Lưu ý: Kết quả chỉ hỗ trợ rà soát, không thay thế quyết định phê duyệt tín dụng.";
