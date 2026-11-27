
import axios from "axios";

export const API_BASE_URL =
  import.meta.env.VITE_API_BASE_URL || "http://localhost:8080";

const apiClient = axios.create({
  baseURL: API_BASE_URL,
  timeout: 30000,
});

// Upload hồ sơ
export async function uploadDossier({
  applicationId,
  productType,
  applicantName,
  files,
}) {
  const formData = new FormData();

  const metadataPayload = {
    applicationId,
    productType,
    applicantName,
  };

  const metadataBlob = new Blob(
    [JSON.stringify(metadataPayload)],
    { type: "application/json" }
  );

  formData.append("metadata", metadataBlob);

  files.forEach((file) => {
    formData.append("files", file);
  });

  const response = await apiClient.post(
    "/api/v1/dossiers/upload",
    formData
  );

  return response.data;
}

// Lấy danh sách hồ sơ từ Backend
export async function getDossiers() {
  const response = await apiClient.get("/api/v1/dossiers");
  return response.data;
}

// Lấy chi tiết hồ sơ từ Backend
export async function getDossierById(applicationId) {
  const response = await apiClient.get(
    `/api/v1/dossiers/${encodeURIComponent(applicationId)}`
  );

  return response.data;
}

// Xác nhận bàn giao hồ sơ
// Backend yêu cầu expectedVersion
export async function confirmDossier(
  applicationId,
  { reviewerName, note, expectedVersion }
) {
  const response = await apiClient.post(
    `/api/v1/dossiers/${encodeURIComponent(applicationId)}/confirm`,
    {
      reviewerName,
      note,
      expectedVersion,
    }
  );

  return {
    ...response.data,
    httpStatus: response.status,
  };
}

// Lấy trạng thái xử lý Confirm khi Backend trả 202
export async function getConfirmOperationStatus(
  applicationId,
  operationId
) {
  const response = await apiClient.get(
    `/api/v1/dossiers/${encodeURIComponent(
      applicationId
    )}/confirm-operations/${encodeURIComponent(operationId)}`
  );

  return response.data;
}

// Yêu cầu bổ sung hồ sơ
// Giữ nguyên chức năng có sẵn của nhóm
export async function requestSupplementDossier(
  applicationId,
  { reviewerName, reason }
) {
  const response = await apiClient.post(
    `/api/v1/dossiers/${encodeURIComponent(
      applicationId
    )}/request-supplement`,
    {
      reviewerName,
      reason,
    }
  );

  return response.data;
}

// URL xem PDF thật từ Backend
export function getDocumentFileUrl(applicationId, fileId) {
  return `${API_BASE_URL}/api/v1/dossiers/${encodeURIComponent(
    applicationId
  )}/files/${encodeURIComponent(fileId)}/content`;
}

// Tính SHA-256 của file
// Giữ nguyên chức năng có sẵn
export async function computeSha256(file) {
  if (window?.crypto?.subtle && file?.arrayBuffer) {
    const buffer = await file.arrayBuffer();

    const hashBuffer = await window.crypto.subtle.digest(
      "SHA-256",
      buffer
    );

    const hashArray = Array.from(new Uint8Array(hashBuffer));

    return hashArray
      .map((b) => b.toString(16).padStart(2, "0"))
      .join("");
  }

  return Array.from({ length: 64 }, () =>
    Math.floor(Math.random() * 16).toString(16)
  ).join("");
}
