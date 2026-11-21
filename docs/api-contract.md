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

## Get all dossiers (latest version per application)

`GET /api/v1/dossiers` returns `200 OK` with a JSON array of all applications and their latest uploaded version, sorted by `versionCreatedAt` descending:

```json
[
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
]
```

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

## Get original document file content

`GET /api/v1/dossiers/{applicationId}/files/{fileId}/content` streams the stored original binary file (`application/pdf`, `image/png`, or `image/jpeg`) with `Content-Disposition: inline; filename*=UTF-8''<fileName>` for in-browser PDF/image viewing.

Returns `404 Not Found` if the `applicationId` does not exist or `fileId` does not belong to that application.

## Runtime configuration

- `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`: SQL Server connection. `DB_PASSWORD` is required at runtime; no password fallback is committed.
- `APP_STORAGE_LOCATION`: upload directory; defaults to `uploads`.
- `JPA_DDL_AUTO`: defaults to `validate`. Apply reviewed schema migration first; do not use Hibernate `update` in production.
- `CAMUNDA_ENABLED`: set to `true` only with a compatible Camunda 8.10 cluster, a deployed process, and the reviewed schema.
- `CAMUNDA_GRPC_ADDRESS`: Camunda gRPC address; default `http://127.0.0.1:26500`.
- `CAMUNDA_REST_ADDRESS`: Camunda Orchestration Cluster REST address; default `http://127.0.0.1:8088`.
- `CAMUNDA_PROCESS_ID`: defaults to `retail-loan-document-completeness`.
- `CAMUNDA_CONFIRM_ELEMENT_ID`: defaults to `Activity_03e0s8k`.

The Java starter/client is pinned to Camunda 8.10.9. The root `docker-compose.yml` is a **legacy 8.2** environment and cannot validate or run this Confirm path. `DurableConfirmCamundaIT` passed 1/1 against an isolated real Camunda 8.10.9 engine in R6 using its dedicated test BPMN, H2, and test-only identity/review facts; see `docs/testing/confirm-camunda-it.md`. It was not rerun after R10's persistence mapping changes. This does not verify the team's full BPMN/DMN or production integration.

## Confirm dossier handoff (TASK 02Q implementation contract; still provisional)

`POST /api/v1/dossiers/{applicationId}/confirm` accepts:

```json
{"reviewerName":"Nguyen Van A","note":"Reviewed dossier","expectedVersion":3}
```

`applicationId` is 1–50 ASCII letters, digits, `_` or `-`. `reviewerName` is required, at most 200 characters, and **display data only**. `note` is optional and at most 1000 characters. `expectedVersion` must be positive and match the current persisted version. The actor, role, and case scope must come from trusted server-side authentication/authorization; the request body cannot establish them. CSRF protection remains enabled for this POST.

With `CAMUNDA_ENABLED=true`, the backend requires authoritative version-bound review facts and a persisted engine-returned process correlation. A successful admission writes a unique `ConfirmOperations` row before the remote command. It searches the Camunda 8.10 native User Task by process key and element ID, re-reads task and root variables, and completes the OPEN task with `confirmOperationId` and `handoffDecision=CONFIRMED`. READY and one `DOSSIER_CONFIRMED` audit are written in one later transaction **only after** the completed task and operation marker can be verified and the dossier/review facts are rechecked. The HTTP coordinator writes READY/audit in the verified isolated User Task flow. The team's workflow may have a downstream finalize worker and is **not** interchangeable without an ownership/integration decision.

If finalization is verified within the request, the response is `200 OK`:

```json
{"operationId":"uuid","applicationId":"APP-001","version":3,"state":"COMPLETED","statusUrl":"/api/v1/dossiers/APP-001/confirm-operations/uuid","auditLogId":"uuid","completenessStatus":"READY_FOR_CREDIT_REVIEW"}
```

If admitted but unfinished, the response is `202 Accepted` with `Location` set to `statusUrl`; `state` is `PENDING`, `COMPLETING`, `RECONCILING`, or `REQUIRES_INTERVENTION`. `auditLogId` and `completenessStatus` are null until finalization. An authenticated, case-scoped `GET /api/v1/dossiers/{applicationId}/confirm-operations/{operationId}` returns the current operation status. A historical completed operation does not claim the current dossier is READY after a newer upload.

The operation is unique per application and dossier version. A duplicate Confirm returns 409. Upload is blocked while that application's Confirm is PENDING, COMPLETING, RECONCILING or REQUIRES_INTERVENTION. A lost Camunda completion response is reconciled using the same persisted task key and operation marker; the command is not blindly retried. A completed task without this marker cannot promote READY. A failed database finalization leaves the operation RECONCILING for recovery; stale evidence or ambiguous/wrong task correlation requires intervention. There is no distributed DB–Camunda transaction.

Errors use ProblemDetail with a `code`: invalid request/audit overflow 400; unauthenticated 401; role or case denial 403; missing dossier/operation 404; stale, duplicate or ineligible review 409; missing authoritative facts/correlation 503. The operation can already be durably accepted when a later workflow outage occurs; then the response is 202 with a status resource instead of 503.

When Camunda is disabled, the pre-existing verified-absent test path remains fail-closed in production because no trusted case-scope provider, review-facts source, or verified-absent gateway is installed. It is not the Camunda OPEN-task completion path.

## Team integration dependencies (not yet verified)

- **Camunda:** The configured default Confirm User Task ID `Activity_03e0s8k` exists in the unmerged `UNIT02-22` BPMN. The unmerged `UNIT02-58` BPMN instead uses `Activity_0462f5z`; its decision ID and `overallStatus` output align with the DMN added to `origin/develop` at `4225ef1`, but its READY branch reaches an end event without an open Confirm User Task. The `UNIT02-22` BPMN also contains a downstream `finalize-completeness-handoff` worker, which overlaps this coordinator's READY/audit writes. Select an authoritative BPMN and resolve single-writer ownership before team workflow integration; no compatibility with either BPMN is claimed here.
- **Frontend:** The unmerged `SUB-206.2` client currently sends `reviewerName` and `note` only. It must send the current dossier `expectedVersion`, use a trusted authenticated session and the required CSRF token, handle `200`/`202` and poll the operation status when needed. A client-supplied reviewer name is display data, not actor identity. The current mock `localStorage` user does not satisfy the server's authentication or case-scope checks.
- **Database:** The Confirm SQL Server migration passed on an isolated synthetic baseline only. Validate it against the approved team schema and dependent objects before any shared deployment; no production migration has been run.

**Verification and deployment boundary:** H2/JPA tests with synthetic scoped identities, synthetic review facts, and controlled Camunda client responses passed; the latest full Maven regression on November 21, 2026 passed 131/131 tests. The previously reported 137/137 included nine stale Camunda 8.5 test results from an earlier run. The isolated real Camunda 8.10.9 IT passed 1/1 in R6; it was not rerun after R10's persistence mapping changes. A separate manual demonstration on Camunda 8.10.10 verified HTTP 202, a completed Confirm operation, READY status, one confirmed audit, and completion of the native User Task and process. SQL Server persistence and Vietnamese Unicode IT passed 6/6 in R10; the candidate migration passed with Hibernate `validate` only on a synthetic SQL Server baseline. Production IdP/case assignment, authoritative review facts, and team BPMN/DMN integration remain **unverified or unavailable**. This implementation contract is not a Product Owner-approved API contract or production-ready claim.
