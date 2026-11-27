
import { createContext, useContext, useEffect, useState } from "react";
import { INITIAL_MOCK_DOSSIERS } from "../data/mockDossiers";
import {
  PRODUCT_TYPES,
  REQUIRED_DOC_ORDER,
  normalizeDocStatus,
  normalizeDocType,
} from "../constants/statusCodes";
import * as dossierApi from "../services/dossierApi";

const DOSSIERS_STORAGE_KEY = "meridian_dossiers_v1";
const DRAFT_STORAGE_KEY = "meridian_loan_draft_v1";

const DEFAULT_DRAFT_LOAN = {
  applicationId: "APP-2026-007",
  applicantName: "Nguyễn Văn A",
  productType: PRODUCT_TYPES.UNSECURED_SALARIED_LOAN,
  loanAmount: "100.000.000 VNĐ",
  termAndBranch: "24 tháng · CN TP. Hồ Chí Minh",
};

const DossierContext = createContext(null);

function loadStoredDossiers() {
  try {
    const raw = localStorage.getItem(DOSSIERS_STORAGE_KEY);
    if (raw) {
      const parsed = JSON.parse(raw);
      if (Array.isArray(parsed) && parsed.length > 0) return parsed;
    }
  } catch {
    // Fallback to initial mock data
  }
  return INITIAL_MOCK_DOSSIERS;
}

function loadStoredDraft() {
  try {
    const raw = localStorage.getItem(DRAFT_STORAGE_KEY);
    if (raw) return { ...DEFAULT_DRAFT_LOAN, ...JSON.parse(raw) };
  } catch {
    // Fallback
  }
  return DEFAULT_DRAFT_LOAN;
}

function inferDocTypeFromFileName(fileName = "") {
  const normalized = fileName
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .replace(/đ/g, "d")
    .replace(/Đ/g, "D")
    .toLowerCase();

  if (
    normalized.includes("cccd") ||
    normalized.includes("cmnd") ||
    normalized.includes("can cuoc") ||
    normalized.includes("can_cuoc") ||
    normalized.includes("id")
  ) {
    return "NATIONAL_ID";
  }

  if (
    normalized.includes("hdld") ||
    normalized.includes("hop_dong") ||
    normalized.includes("hop dong") ||
    normalized.includes("lao_dong") ||
    normalized.includes("lao dong") ||
    normalized.includes("contract")
  ) {
    return "LABOR_CONTRACT";
  }

  if (
    normalized.includes("sao_ke") ||
    normalized.includes("sao ke") ||
    normalized.includes("saoke") ||
    normalized.includes("luong") ||
    normalized.includes("bank") ||
    normalized.includes("statement")
  ) {
    return "BANK_STATEMENT";
  }

  if (
    normalized.includes("don_vay") ||
    normalized.includes("don vay") ||
    normalized.includes("don_dang_ky") ||
    normalized.includes("don dang ky") ||
    normalized.includes("don_de_nghi") ||
    normalized.includes("don de nghi") ||
    normalized.includes("loan")
  ) {
    return "LOAN_APPLICATION";
  }

  return "UNKNOWN";
}

function normalizeBackendDossier(d) {
  return {
    ...d,
    loanAmount: d.loanAmount || "100.000.000 VNĐ",
    branch: d.branch || "TP. Hồ Chí Minh",
    evaluatedAt: d.evaluatedAt || d.versionCreatedAt,
    documents: (d.documents || []).map((doc) => ({
      ...doc,
      docType: normalizeDocType(doc.docType),
      status: normalizeDocStatus(doc.status),
    })),
    auditLogs: d.auditLogs || [],
  };
}

export function DossierProvider({ children }) {
  const [useMockApi, setUseMockApi] = useState(
    () => (import.meta.env.VITE_USE_MOCK_API ?? "true") !== "false"
  );

  const [dossiers, setDossiers] = useState(() => loadStoredDossiers());
  const [draftLoan, setDraftLoan] = useState(() => loadStoredDraft());
  const [fileUrlsByFileId, setFileUrlsByFileId] = useState({});
  const [syncError, setSyncError] = useState(null);

  useEffect(() => {
    try {
      localStorage.setItem(
        DOSSIERS_STORAGE_KEY,
        JSON.stringify(dossiers)
      );
    } catch {
      // Ignore
    }
  }, [dossiers]);

  useEffect(() => {
    try {
      localStorage.setItem(
        DRAFT_STORAGE_KEY,
        JSON.stringify(draftLoan)
      );
    } catch {
      // Ignore
    }
  }, [draftLoan]);

  const refreshFromBackend = async () => {
    if (useMockApi) return dossiers;

    try {
      const list = await dossierApi.getDossiers();

      const normalized = Array.isArray(list)
        ? list.map(normalizeBackendDossier)
        : [];

      setDossiers(normalized);
      setSyncError(null);

      return normalized;
    } catch (err) {
      setSyncError(
        err?.response?.data?.message ||
          "Không kết nối được API Backend (kiểm tra Spring Boot & CORS)."
      );

      throw err;
    }
  };

  useEffect(() => {
    if (!useMockApi) {
      refreshFromBackend().catch(() => {});
    } else {
      setSyncError(null);
    }
  }, [useMockApi]);

  const getDossier = (applicationId) =>
    dossiers.find((d) => d.applicationId === applicationId) || null;

  const fetchDossierDetail = async (applicationId) => {
    if (useMockApi) {
      return getDossier(applicationId);
    }

    const raw = await dossierApi.getDossierById(applicationId);
    const normalized = normalizeBackendDossier(raw);

    setDossiers((prev) => {
      const exists = prev.some(
        (d) => d.applicationId === applicationId
      );

      return exists
        ? prev.map((d) =>
            d.applicationId === applicationId
              ? normalized
              : d
          )
        : [normalized, ...prev];
    });

    return normalized;
  };

  const submitUpload = async ({
    applicationId,
    productType,
    applicantName,
    loanAmount,
    branch,
    files,
  }) => {
    const cleanId = applicationId.trim();
    const cleanName = applicantName.trim();

    // Create local object URLs for preview
    const localObjectUrls = files.map((file) => ({
      name: file.name,
      url: URL.createObjectURL(file),
    }));

    if (!useMockApi) {
      const created = await dossierApi.uploadDossier({
        applicationId: cleanId,
        productType,
        applicantName: cleanName,
        files,
      });

      const normalized = normalizeBackendDossier(created);
      const urlMap = {};

      (normalized.documents || []).forEach((doc, idx) => {
        if (localObjectUrls[idx]) {
          urlMap[doc.fileId] = localObjectUrls[idx].url;
        }
      });

      setFileUrlsByFileId((prev) => ({
        ...prev,
        ...urlMap,
      }));

      setDossiers((prev) => {
        const exists = prev.some(
          (d) => d.applicationId === cleanId
        );

        return exists
          ? prev.map((d) =>
              d.applicationId === cleanId
                ? normalized
                : d
            )
          : [normalized, ...prev];
      });

      return normalized;
    }

    // Mock mode
    const nowIso = new Date().toISOString();
    const existing = getDossier(cleanId);
    const nextVersion = existing ? existing.version + 1 : 1;

    const docs = await Promise.all(
      files.map(async (file, index) => {
        const fileId =
          `mock-${cleanId}-v${nextVersion}-${index + 1}-${Date.now()}`;

        const sha256 = await dossierApi.computeSha256(file);
        const docType = inferDocTypeFromFileName(file.name);
        const isValidType = docType !== "UNKNOWN";

        return {
          fileId,
          docType,
          fileName: file.name,
          contentType: file.type || "application/pdf",
          fileSize: file.size || 102400,
          fileHashSha256: sha256,
          status: isValidType ? "VALID" : "BLURRY",
          reason: isValidType
            ? "Đã nhận diện hợp lệ từ tệp tải lên."
            : "AI chưa nhận diện chắc chắn loại giấy tờ, cần cán bộ rà soát.",
          missingPeriod: null,
          pageCount: file.type?.startsWith("image/")
            ? 1
            : 2,
          pageAnchor: 1,
        };
      })
    );

    const urlMap = {};

    docs.forEach((doc, idx) => {
      if (localObjectUrls[idx]) {
        urlMap[doc.fileId] = localObjectUrls[idx].url;
      }
    });

    setFileUrlsByFileId((prev) => ({
      ...prev,
      ...urlMap,
    }));

    const presentTypes = new Set(
      docs.map((d) => d.docType)
    );

    const missingTypes = REQUIRED_DOC_ORDER.filter(
      (req) => !presentTypes.has(req)
    );

    const hasBlurryOrUnknown = docs.some(
      (d) => d.status !== "VALID"
    );

    let completenessStatus = "READY_FOR_CREDIT_REVIEW";
    let summaryNote = "Đủ 4/4 chứng từ";

    if (missingTypes.length > 0) {
      completenessStatus = "INCOMPLETE";
      summaryNote =
        `Thiếu ${missingTypes.length} nhóm giấy tờ bắt buộc`;
    } else if (hasBlurryOrUnknown) {
      completenessStatus = "REVIEW_NEEDED";
      summaryNote = "Cần cán bộ đối chiếu tài liệu";
    }

    const newDossier = {
      applicationId: cleanId,
      productType,
      applicantName: cleanName,
      loanAmount:
        loanAmount ||
        draftLoan.loanAmount ||
        "100.000.000 VNĐ",
      branch:
        branch ||
        (draftLoan.termAndBranch?.includes("·")
          ? draftLoan.termAndBranch.split("·")[1].trim()
          : "TP. Hồ Chí Minh"),
      submissionDate: nowIso.slice(0, 10),
      version: nextVersion,
      versionCreatedAt: nowIso,
      evaluatedAt: nowIso,
      completenessStatus,
      summaryNote,
      documents: docs,
      auditLogs: [
        ...(existing?.auditLogs || []),
        {
          auditLogId: `log-${Date.now()}`,
          action: "DOSSIER_UPLOADED",
          actor: cleanName,
          details:
            `Nộp phiên bản v${nextVersion} gồm ${docs.length} tệp ` +
            "(SHA-256 đã xác thực).",
          createdAt: nowIso,
        },
      ],
    };

    setDossiers((prev) => {
      const exists = prev.some(
        (d) => d.applicationId === cleanId
      );

      return exists
        ? prev.map((d) =>
            d.applicationId === cleanId
              ? newDossier
              : d
          )
        : [newDossier, ...prev];
    });

    return newDossier;
  };

  // Connect real Confirm API
  const confirmHandoff = async (
    applicationId,
    { reviewerName, note }
  ) => {
    if (!useMockApi) {
      // Read current version from Backend
      const current = await dossierApi.getDossierById(
        applicationId
      );

      const expectedVersion = Number(current?.version);

      if (
        !Number.isSafeInteger(expectedVersion) ||
        expectedVersion < 1
      ) {
        throw new Error(
          "Không xác định được phiên bản hồ sơ từ Backend."
        );
      }

      const result = await dossierApi.confirmDossier(
        applicationId,
        {
          reviewerName,
          note,
          expectedVersion,
        }
      );

      // Backend returns operation status, not dossier data
      if (result.state === "COMPLETED") {
        const latest = await dossierApi.getDossierById(
          applicationId
        );

        const normalized = normalizeBackendDossier(latest);

        setDossiers((prev) =>
          prev.some(
            (d) => d.applicationId === applicationId
          )
            ? prev.map((d) =>
                d.applicationId === applicationId
                  ? normalized
                  : d
              )
            : [normalized, ...prev]
        );

        return result;
      }

      // 202 Accepted: still processing
      if (result.httpStatus === 202) {
        return result;
      }

      throw new Error(
        `Phản hồi Confirm không hợp lệ: ${
          result.state || "UNKNOWN"
        }`
      );
    }

    // Keep existing Mock behavior
    const nowIso = new Date().toISOString();
    let result = null;

    setDossiers((prev) =>
      prev.map((d) => {
        if (d.applicationId !== applicationId) return d;

        result = {
          ...d,
          completenessStatus: "READY_FOR_CREDIT_REVIEW",
          summaryNote: "Đã xác nhận bàn giao tín dụng",
          evaluatedAt: nowIso,
          auditLogs: [
            ...(d.auditLogs || []),
            {
              auditLogId: `log-confirm-${Date.now()}`,
              action: "CONFIRMED_FOR_CREDIT_REVIEW",
              actor: reviewerName,
              details: note,
              createdAt: nowIso,
            },
          ],
        };

        return result;
      })
    );

    return result;
  };

  const requestSupplement = async (
    applicationId,
    { reviewerName, reason }
  ) => {
    if (!useMockApi) {
      const updated =
        await dossierApi.requestSupplementDossier(
          applicationId,
          {
            reviewerName,
            reason,
          }
        );

      const normalized = normalizeBackendDossier(updated);

      setDossiers((prev) =>
        prev.map((d) =>
          d.applicationId === applicationId
            ? normalized
            : d
        )
      );

      return normalized;
    }

    const nowIso = new Date().toISOString();
    let result = null;

    setDossiers((prev) =>
      prev.map((d) => {
        if (d.applicationId !== applicationId) return d;

        result = {
          ...d,
          completenessStatus: "INCOMPLETE",
          summaryNote:
            reason || "Cán bộ yêu cầu bổ sung hồ sơ",
          evaluatedAt: nowIso,
          auditLogs: [
            ...(d.auditLogs || []),
            {
              auditLogId: `log-supp-${Date.now()}`,
              action: "SUPPLEMENT_REQUESTED",
              actor: reviewerName,
              details: reason,
              createdAt: nowIso,
            },
          ],
        };

        return result;
      })
    );

    return result;
  };

  const resetDemoData = () => {
    setDossiers(INITIAL_MOCK_DOSSIERS);
    setDraftLoan(DEFAULT_DRAFT_LOAN);

    try {
      localStorage.removeItem(DOSSIERS_STORAGE_KEY);
      localStorage.removeItem(DRAFT_STORAGE_KEY);
    } catch {
      // Ignore
    }
  };

  return (
    <DossierContext.Provider
      value={{
        dossiers,
        useMockApi,
        setUseMockApi,
        syncError,
        draftLoan,
        setDraftLoan,
        fileUrlsByFileId,
        getDossier,
        fetchDossierDetail,
        refreshFromBackend,
        submitUpload,
        confirmHandoff,
        requestSupplement,
        resetDemoData,
      }}
    >
      {children}
    </DossierContext.Provider>
  );
}

export function useDossiers() {
  const ctx = useContext(DossierContext);

  if (!ctx) {
    throw new Error(
      "useDossiers must be used inside DossierProvider"
    );
  }

  return ctx;
}
