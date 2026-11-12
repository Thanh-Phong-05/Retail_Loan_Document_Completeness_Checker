# Dossier REST API contract

## Upload a dossier

`POST /api/v1/dossiers/upload` uses `multipart/form-data` with:

- `metadata`: an `application/json` part.
- `files`: one or more PDF, PNG, or JPEG parts; maximum 20 files and 10 MB per file.

Example metadata:

```json
{
  "applicationId": "APP-001",
  "productType": "UNSECURED_SALARIED_LOAN",
  "applicantName": "Nguyen Van A"
}
```

Example request:

```bash
curl -X POST http://localhost:8080/api/v1/dossiers/upload \
  -F 'metadata={"applicationId":"APP-001","productType":"UNSECURED_SALARIED_LOAN","applicantName":"Nguyen Van A"};type=application/json' \
  -F 'files=@cccd.pdf;type=application/pdf' \
  -F 'files=@bank-statement.png;type=image/png'
```

The API returns `201 Created`, a `Location` header, the created dossier version, and the SHA-256 hash of each file.

## Get the latest dossier version

`GET /api/v1/dossiers/{applicationId}` returns the latest uploaded version:

```json
{
  "applicationId": "APP-001",
  "productType": "UNSECURED_SALARIED_LOAN",
  "applicantName": "Nguyen Van A",
  "submissionDate": "2026-11-11",
  "version": 1,
  "versionCreatedAt": "2026-11-11T10:00:00Z",
  "completenessStatus": "REVIEW_NEEDED",
  "documents": [
    {
      "fileId": "generated-uuid",
      "docType": "UNKNOWN",
      "fileName": "cccd.pdf",
      "contentType": "application/pdf",
      "fileSize": 1024,
      "fileHashSha256": "64-character-lowercase-hex",
      "status": "PENDING",
      "missingPeriod": null,
      "pageCount": 0,
      "pageAnchor": 0
    }
  ]
}
```

`docType`, document `status`, page data, and the overall completeness status are updated later by the OCR/Camunda workflow.

## Runtime configuration

- `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`: SQL Server connection.
- `APP_STORAGE_LOCATION`: upload directory; defaults to `uploads`.
- `CAMUNDA_ENABLED`: set to `true` to start a process after persistence.
- `CAMUNDA_GATEWAY_URL`: defaults to `http://127.0.0.1:26500`.
- `CAMUNDA_PROCESS_ID`: defaults to `retail-loan-document-completeness`.
