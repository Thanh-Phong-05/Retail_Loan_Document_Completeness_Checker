import { Check, ChevronRight } from "lucide-react";

const STEPS = [
  { number: 1, label: "Chọn gói vay & Chi tiết" },
  { number: 2, label: "Tải hồ sơ" },
  { number: 3, label: "Xác minh" },
];

export default function ProgressStepper({ currentStep = 1 }) {
  return (
    <div className="bg-white border border-[#E2E8F0] rounded-[12px] px-6 py-5 flex flex-wrap items-center justify-between gap-4">
      {STEPS.map((step, idx) => {
        const isCompleted = step.number < currentStep;
        const isActive = step.number === currentStep;

        return (
          <div
            key={step.number}
            className="flex items-center gap-3 flex-1 min-w-[180px]"
          >
            {isCompleted ? (
              <div className="size-[30px] rounded-full bg-[#F0FDF4] border border-[#BBF7D0] flex items-center justify-center shrink-0">
                <Check className="size-4 text-[#15803D]" strokeWidth={2.5} />
              </div>
            ) : isActive ? (
              <div className="size-[30px] rounded-full bg-[#0284C7] border border-[#0284C7] flex items-center justify-center shrink-0 text-[12px] font-semibold text-white">
                {step.number}
              </div>
            ) : (
              <div className="size-[30px] rounded-full bg-[#F8FAFC] border border-[#E2E8F0] flex items-center justify-center shrink-0 text-[12px] font-semibold text-[#64748B]">
                {step.number}
              </div>
            )}

            <span
              className={`text-[13px] whitespace-nowrap ${
                isActive
                  ? "font-semibold text-[#0284C7]"
                  : "font-medium text-[#64748B]"
              }`}
            >
              {step.label}
            </span>

            {idx < STEPS.length - 1 && (
              <ChevronRight className="size-4 text-[#94A3B8] ml-1 shrink-0" />
            )}
          </div>
        );
      })}
    </div>
  );
}
