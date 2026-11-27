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
- `CAMUNDA_ENABLED`: set to `true` only with a compatible Camunda 8.5 cluster, a deployed process, and the reviewed schema.
- `CAMUNDA_GRPC_ADDRESS`: Camunda gRPC address; default `http://127.0.0.1:26500`.
- `CAMUNDA_REST_ADDRESS`: Zeebe REST address; default `http://127.0.0.1:8088`.
- `CAMUNDA_PROCESS_ID`: defaults to `retail-loan-document-completeness`.
- `CAMUNDA_CONFIRM_ELEMENT_ID`: defaults to `Activity_0462f5z` (Linh's `workflow/retail_loan_process.bpmn`).
- `CAMUNDA_CLIENT_MODE`: defaults to `simple` for local 8.5; configure approved authentication for deployment.
- `CAMUNDA_OPERATE_ADDRESS`: defaults to `http://127.0.0.1:8081`.
- `CAMUNDA_TASKLIST_ADDRESS`: defaults to `http://127.0.0.1:8082`.

The Java starter is pinned to `io.camunda.spring:spring-boot-starter-camunda:8.5.0`. Use the isolated 8.5 stack and instructions in `docs/testing/confirm-camunda-it.md`; the root legacy 8.2 Compose file cannot validate this path. Historical 8.10 results are not evidence for the current adapter.

## Confirm dossier handoff (TASK 02Q implementation contract; still provisional)

`POST /api/v1/dossiers/{applicationId}/confirm` accepts:

```json
{"reviewerName":"Nguyen Van A","note":"Reviewed dossier","expectedVersion":3}
```

`applicationId` is 1–50 ASCII letters, digits, `_` or `-`. `reviewerName` is required, at most 200 characters, and **display data only**. `note` is optional and at most 1000 characters. `expectedVersion` must be positive and match the current persisted version. The actor, role, and case scope must come from trusted server-side authentication/authorization; the request body cannot establish them. CSRF protection remains enabled for this POST. Per the PO-authorized HTTP policy, `/api/v1/**` is `permitAll()` in every profile; Confirm authorization is enforced in the service, including operation status reads. Missing or untrusted identity still fails closed. Existing upload/read endpoints remain public, with no new authentication requirement.

With `CAMUNDA_ENABLED=true`, the backend requires authoritative version-bound review facts and a persisted engine-returned process correlation. A successful admission writes a unique `ConfirmOperations` row before the remote command. It searches the Camunda 8.5 native User Task through Tasklist by process key and element ID, re-reads task and Operate root variables, and uses a Zeebe command to complete the OPEN task with `confirmOperationId` and `handoffDecision=CONFIRMED`. READY and one `DOSSIER_CONFIRMED` audit are written in one later transaction **only after** the completed task and operation marker can be verified and the dossier/review facts are rechecked. The HTTP coordinator writes READY/audit in the verified isolated User Task flow. The team's workflow may have a downstream finalize worker and is **not** interchangeable without an ownership/integration decision.

If finalization is verified within the request, the response is `200 OK`:

```json
{"operationId":"uuid","applicationId":"APP-001","version":3,"state":"COMPLETED","statusUrl":"/api/v1/dossiers/APP-001/confirm-operations/uuid","auditLogId":"uuid","completenessStatus":"READY_FOR_CREDIT_REVIEW"}
```

If admitted but unfinished, the response is `202 Accepted` with `Location` set to `statusUrl`; `state` is `PENDING`, `COMPLETING`, `RECONCILING`, or `REQUIRES_INTERVENTION`. `auditLogId` and `completenessStatus` are null until finalization. An authenticated, case-scoped `GET /api/v1/dossiers/{applicationId}/confirm-operations/{operationId}` returns the current operation status. A historical completed operation does not claim the current dossier is READY after a newer upload.

The operation is unique per application and dossier version. A duplicate Confirm returns 409. Upload is blocked while that application's Confirm is PENDING, COMPLETING, RECONCILING or REQUIRES_INTERVENTION. A lost Camunda completion response is reconciled using the same persisted task key and operation marker; the command is not blindly retried. A completed task without this marker cannot promote READY. A failed database finalization leaves the operation RECONCILING for recovery; stale evidence or ambiguous/wrong task correlation requires intervention. There is no distributed DB–Camunda transaction.

Errors use ProblemDetail with a `code`: invalid request/audit overflow 400; unauthenticated 401; role or case denial 403; missing dossier/operation 404; stale, duplicate or ineligible review 409; missing authoritative facts/correlation 503. The operation can already be durably accepted when a later workflow outage occurs; then the response is 202 with a status resource instead of 503.

When Camunda is disabled, the pre-existing verified-absent test path remains fail-closed in production because no trusted case-scope provider, review-facts source, or verified-absent gateway is installed. It is not the Camunda OPEN-task completion path.

## Team integration dependencies (not yet verified)

For PR delivery, the exact selected source files passed 138/138 tests in a fresh export on November 26, 2026. The historical 139/139 workspace result below includes one untracked manual-demo HTTP test excluded from this commit. Neither count is evidence of a successful merged-`develop` regression or production acceptance.

Current isolated-runtime evidence: the November 26, 2026 Camunda 8.5.0 test passed 1/1 after exporter indexes became ready. It verified HTTP 202 to COMPLETED, READY/one audit, task/process completion and duplicate handling with the test BPMN and H2. It does not supply the dependencies below. See `docs/testing/confirm-camunda-it.md` for precise evidence.

The separate deployment-only check of Linh's unchanged BPMN from develop `cf008abc` failed with `INVALID_ARGUMENT` at `Flow_0gcvgk9`: its condition `== overallStatus = "READY_FOR_CREDIT_REVIEW"` has an extra leading `=`. This is a verified team-workflow deployment blocker, independent of the passing isolated Confirm IT. No team BPMN/DMN was changed.

- **Camunda:** Production defaults select Linh's `workflow/retail_loan_process.bpmn`, process `retail-loan-document-completeness`, task `Activity_0462f5z`. The inspected develop `cf008abc` also contains the detailed `UNIT02-22` BPMN with the same process ID, a different task and downstream `finalize-completeness-handoff`. Do not deploy both as interchangeable versions. Linh's READY branch bypasses the User Task; OCR/DMN and branch behavior still require team integration verification. No Java finalize worker was found in the inspected branches. Do not add a competing READY/audit writer.
- **Frontend:** The inspected frontend client currently sends `reviewerName` and `note` only. It must send the current dossier `expectedVersion`, use a trusted authenticated session and the required CSRF token, handle `200`/`202` and poll the operation status when needed. A client-supplied reviewer name is display data, not actor identity. The current mock `localStorage` user does not satisfy the server's authentication or case-scope checks.
- **Database:** The Confirm SQL Server migration passed on an isolated synthetic baseline only. Validate it against the approved team schema and dependent objects before any shared deployment; no production migration has been run.

**Verification and deployment boundary:** H2/JPA tests with synthetic scoped identities, synthetic review facts, and controlled Camunda client responses passed; the full Maven regression on November 26, 2026 passed 139/139 after the 8.5 port (the prior November 21 result was 131/131). The previously reported 137/137 included nine stale Camunda 8.5 test results from an earlier run. The isolated real Camunda 8.10.9 IT passed 1/1 in R6; it was not rerun after R10's persistence mapping changes. A separate manual demonstration on Camunda 8.10.10 verified HTTP 202, a completed Confirm operation, READY status, one confirmed audit, and completion of the native User Task and process. SQL Server persistence and Vietnamese Unicode IT passed 6/6 in R10; the candidate migration passed with Hibernate `validate` only on a synthetic SQL Server baseline. Production IdP/case assignment, authoritative review facts, and team BPMN/DMN integration remain **unverified or unavailable**. This implementation contract is not a Product Owner-approved API contract or production-ready claim.
