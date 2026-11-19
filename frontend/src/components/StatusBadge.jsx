import {
  COMPLETENESS_STATUS_META,
  DOC_STATUS_META,
  normalizeDocStatus,
} from "../constants/statusCodes";

export function CompletenessStatusBadge({ status, role = "OFFICER" }) {
  const meta =
    COMPLETENESS_STATUS_META[status] || COMPLETENESS_STATUS_META.REVIEW_NEEDED;
  const label =
    role === "CUSTOMER" && meta.customerLabel ? meta.customerLabel : meta.label;

  return (
    <span
      className={`inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-[11px] font-medium whitespace-nowrap border ${meta.bgClass} ${meta.borderClass} ${meta.textClass}`}
    >
      <span className={`size-1.5 rounded-full shrink-0 ${meta.dotClass}`} />
      {label}
    </span>
  );
}

export function DocStatusBadge({ status }) {
  const normalized = normalizeDocStatus(status);
  const meta = DOC_STATUS_META[normalized] || DOC_STATUS_META.PENDING;

  return (
    <span
      className={`inline-flex items-center gap-1.5 rounded-md px-2 py-0.5 text-[11px] font-medium whitespace-nowrap border ${meta.bgClass} ${meta.borderClass} ${meta.textClass}`}
    >
      {meta.label}
    </span>
  );
}
