package com.example.retail_loan_document_completeness_checker.services;

import com.example.retail_loan_document_completeness_checker.dto.ConfirmDossierRequest;
import com.example.retail_loan_document_completeness_checker.dto.ConfirmOperationStatus;
import com.example.retail_loan_document_completeness_checker.exceptions.ConfirmException;
import com.example.retail_loan_document_completeness_checker.exceptions.DossierNotFoundException;
import com.example.retail_loan_document_completeness_checker.models.Application;
import com.example.retail_loan_document_completeness_checker.models.AuditLog;
import com.example.retail_loan_document_completeness_checker.models.CompletenessStatus;
import com.example.retail_loan_document_completeness_checker.models.ConfirmOperation;
import com.example.retail_loan_document_completeness_checker.models.WorkflowCorrelation;
import com.example.retail_loan_document_completeness_checker.repositories.ApplicationRepository;
import com.example.retail_loan_document_completeness_checker.repositories.AuditLogRepository;
import com.example.retail_loan_document_completeness_checker.repositories.ConfirmOperationRepository;
import com.example.retail_loan_document_completeness_checker.repositories.DossierVersionRepository;
import com.example.retail_loan_document_completeness_checker.repositories.WorkflowCorrelationRepository;
import com.example.retail_loan_document_completeness_checker.security.ConfirmAuthorizationService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Database admission and finalization are separate transactions. Camunda is called between them.
 * A lost response is reconciled from the same task key and operation marker; it is never blindly resent.
 */
@Service
@ConditionalOnProperty(name = "app.camunda.enabled", havingValue = "true")
public class DurableConfirmCoordinator {
    private final ConfirmAuthorizationService authorization;
    private final ApplicationRepository applications;
    private final DossierVersionRepository versions;
    private final AuditLogRepository auditLogs;
    private final ConfirmOperationRepository operations;
    private final WorkflowCorrelationRepository correlations;
    private final ConfirmEligibilityFactsProvider persistedFacts;
    private final ObjectProvider<ConfirmReviewFactsSource> reviewFactsSources;
    private final Camunda810UserTaskClient tasks;
    private final TransactionTemplate transactions;
    private final ConfirmEligibilityService eligibility = new ConfirmEligibilityService();

    public DurableConfirmCoordinator(ConfirmAuthorizationService authorization,
                                     ApplicationRepository applications,
                                     DossierVersionRepository versions,
                                     AuditLogRepository auditLogs,
                                     ConfirmOperationRepository operations,
                                     WorkflowCorrelationRepository correlations,
                                     ConfirmEligibilityFactsProvider persistedFacts,
                                     ObjectProvider<ConfirmReviewFactsSource> reviewFactsSources,
                                     Camunda810UserTaskClient tasks,
                                     PlatformTransactionManager transactionManager) {
        this.authorization = authorization;
        this.applications = applications;
        this.versions = versions;
        this.auditLogs = auditLogs;
        this.operations = operations;
        this.correlations = correlations;
        this.persistedFacts = persistedFacts;
        this.reviewFactsSources = reviewFactsSources;
        this.tasks = tasks;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public ConfirmOperationStatus confirm(String applicationId, ConfirmDossierRequest request, Authentication actor) {
        String operationId = transactions.execute(status -> admit(applicationId, request, actor));
        try {
            recover(operationId);
        } catch (RuntimeException ignored) {
            // Admission is already committed. The operation remains queryable and recoverable.
        }
        return status(applicationId, operationId, actor);
    }

    public ConfirmOperationStatus status(String applicationId, String operationId, Authentication actor) {
        requireAccess(applicationId, actor);
        ConfirmOperation operation = operations.findById(operationId)
                .filter(found -> applicationId.equals(found.getApplicationId()))
                .orElseThrow(() -> new DossierNotFoundException(applicationId));
        boolean currentReady = operation.getState() == ConfirmOperation.State.COMPLETED
                && applications.findById(applicationId).map(application ->
                application.getCompletenessStatus() == CompletenessStatus.READY_FOR_CREDIT_REVIEW).orElse(false)
                && versions.findFirstByApplicationApplicationIdOrderByVersionNumberDesc(applicationId)
                .map(version -> version.getVersionNumber() == operation.getDossierVersion()).orElse(false);
        return new ConfirmOperationStatus(operation.getId(), operation.getApplicationId(),
                operation.getDossierVersion(), operation.getState(),
                "/api/v1/dossiers/" + applicationId + "/confirm-operations/" + operationId,
                operation.getAuditLogId(), currentReady
                ? CompletenessStatus.READY_FOR_CREDIT_REVIEW : null);
    }

    private String admit(String applicationId, ConfirmDossierRequest request, Authentication actor) {
        requireAccess(applicationId, actor);
        Application application = applications.findByIdForUpdate(applicationId)
                .orElseThrow(() -> new DossierNotFoundException(applicationId));
        var current = versions.findFirstByApplicationApplicationIdOrderByVersionNumberDesc(applicationId)
                .orElseThrow(() -> new DossierNotFoundException(applicationId));
        long version = current.getVersionNumber();
        if (version != request.expectedVersion()) {
            throw failure(HttpStatus.CONFLICT, "STALE_DOSSIER_VERSION", "Expected dossier version is stale");
        }
        if (application.getCompletenessStatus() == CompletenessStatus.READY_FOR_CREDIT_REVIEW) {
            throw failure(HttpStatus.CONFLICT, "DOSSIER_ALREADY_CONFIRMED", "Dossier is already confirmed");
        }
        if (operations.findByApplicationIdAndDossierVersion(applicationId, version).isPresent()) {
            throw failure(HttpStatus.CONFLICT, "CONFIRM_IN_PROGRESS", "Confirmation already exists for this version");
        }
        WorkflowCorrelation correlation = correlations.findByApplicationIdAndDossierVersion(applicationId, version)
                .orElseThrow(() -> failure(HttpStatus.SERVICE_UNAVAILABLE,
                        "CONFIRM_WORKFLOW_UNAVAILABLE", "Persisted process correlation is unavailable"));
        if (correlation.getState() != WorkflowCorrelation.State.STARTED
                || correlation.getProcessInstanceKey() == null || correlation.getProcessInstanceKey() <= 0
                || correlation.getProcessDefinitionKey() == null || correlation.getProcessDefinitionKey() <= 0
                || correlation.getProcessDefinitionVersion() == null || correlation.getProcessDefinitionVersion() <= 0) {
            throw failure(HttpStatus.SERVICE_UNAVAILABLE,
                    "CONFIRM_WORKFLOW_UNAVAILABLE", "Persisted process correlation is invalid");
        }
        var reviewed = requireEligibility(applicationId, version);
        String actorId = actor.getName();
        String role = actor.getAuthorities().stream().map(a -> a.getAuthority())
                .filter(a -> "ROLE_REVIEWER".equals(a) || "ROLE_OFFICER".equals(a))
                .findFirst().orElseThrow(() -> failure(HttpStatus.FORBIDDEN,
                        "CONFIRM_CASE_ACCESS_DENIED", "Reviewer role is missing"));
        if (actorId.length() > 200 || reviewed.snapshot().manifestHash().length() > 128
                || reviewed.snapshot().contextVersion().length() > 128
                || auditDetails(applicationId, version, application.getCompletenessStatus(), actorId,
                request.reviewerName(), request.note()).length() > 1000) {
            throw failure(HttpStatus.BAD_REQUEST, "CONFIRM_AUDIT_TOO_LONG", "Audit context exceeds storage limits");
        }
        ConfirmOperation operation = new ConfirmOperation();
        operation.setId(UUID.randomUUID().toString());
        operation.setApplicationId(applicationId);
        operation.setDossierVersion(version);
        operation.setActorId(actorId);
        operation.setActorRole(role);
        operation.setReviewerName(request.reviewerName());
        operation.setNote(request.note());
        operation.setSnapshotManifest(reviewed.snapshot().manifestHash());
        operation.setSnapshotContext(reviewed.snapshot().contextVersion());
        operation.setReviewFingerprint(fingerprint(reviewed));
        operation.setEvidenceValidUntil(persistedEvidenceExpiry(reviewed.snapshot().evidenceValidUntil()));
        operation.setProcessInstanceKey(correlation.getProcessInstanceKey());
        operation.setState(ConfirmOperation.State.PENDING);
        operation.setCreatedAt(Instant.now());
        operation.setUpdatedAt(operation.getCreatedAt());
        operations.saveAndFlush(operation);
        return operation.getId();
    }

    private ConfirmReviewFactsSource.ReviewFacts requireEligibility(String applicationId, long version) {
        var stored = persistedFacts.read(applicationId, version);
        if (stored.status() != ConfirmEligibilityFactsProvider.ReadStatus.CURRENT_VERSION_READ
                || stored.versions() == null || stored.versions().dossierVersion() != version) {
            throw failure(HttpStatus.SERVICE_UNAVAILABLE, "CONFIRM_FACTS_UNAVAILABLE",
                    "Persisted dossier facts could not be verified");
        }
        ConfirmReviewFactsSource source = reviewFactsSources.getIfAvailable();
        if (source == null) {
            throw failure(HttpStatus.SERVICE_UNAVAILABLE, "CONFIRM_FACTS_UNAVAILABLE",
                    "Authoritative review facts are unavailable");
        }
        ConfirmReviewFactsSource.ReviewFacts reviewed;
        try {
            reviewed = source.read(applicationId, version);
        } catch (RuntimeException exception) {
            throw failure(HttpStatus.SERVICE_UNAVAILABLE, "CONFIRM_FACTS_UNAVAILABLE",
                    "Authoritative review facts are unavailable");
        }
        if (reviewed == null || reviewed.snapshot() == null || reviewed.checklist() == null) {
            throw failure(HttpStatus.SERVICE_UNAVAILABLE, "CONFIRM_FACTS_UNAVAILABLE",
                    "Authoritative review facts are unavailable");
        }
        var decision = eligibility.evaluate(new ConfirmEligibilityService.VersionFacts(
                applicationId, version, version, reviewed.evaluationVersion()),
                reviewed.snapshot(), reviewed.checklist());
        if (decision != ConfirmEligibilityService.Decision.ELIGIBLE) {
            throw failure(decision == ConfirmEligibilityService.Decision.NOT_ELIGIBLE
                            ? HttpStatus.CONFLICT : HttpStatus.SERVICE_UNAVAILABLE,
                    decision == ConfirmEligibilityService.Decision.NOT_ELIGIBLE
                            ? "DOSSIER_NOT_ELIGIBLE" : "CONFIRM_FACTS_UNAVAILABLE",
                    "Review evidence does not establish confirmation eligibility");
        }
        if (Instant.now().isAfter(reviewed.snapshot().evidenceValidUntil())) {
            throw failure(HttpStatus.CONFLICT, "DOSSIER_REVIEW_EXPIRED", "Review evidence expired");
        }
        return reviewed;
    }

    public void recover(String operationId) {
        ConfirmOperation operation = operations.findById(operationId).orElse(null);
        if (operation == null || operation.getState() == ConfirmOperation.State.COMPLETED
                || operation.getState() == ConfirmOperation.State.REQUIRES_INTERVENTION) return;
        WorkflowCorrelation correlation = correlations.findByApplicationIdAndDossierVersion(
                operation.getApplicationId(), operation.getDossierVersion()).orElse(null);
        if (correlation == null || correlation.getState() != WorkflowCorrelation.State.STARTED
                || !java.util.Objects.equals(correlation.getProcessInstanceKey(), operation.getProcessInstanceKey())) {
            intervention(operationId, "PROCESS_CORRELATION_MISSING");
            return;
        }
        var expected = new Camunda810UserTaskClient.Correlation(operation.getApplicationId(),
                operation.getDossierVersion(), correlation.getProcessInstanceKey(),
                correlation.getProcessDefinitionKey(), correlation.getProcessId(),
                correlation.getConfirmElementId(), correlation.getTenantId());
        if (operation.getUserTaskKey() == null) {
            var lookup = tasks.lookup(expected);
            if (lookup.state() == Camunda810UserTaskClient.LookupState.WRONG_CASE_OR_VERSION
                    || lookup.state() == Camunda810UserTaskClient.LookupState.MULTIPLE_MATCHES
                    || lookup.state() == Camunda810UserTaskClient.LookupState.ALREADY_COMPLETED) {
                intervention(operationId, lookup.state().name());
                return;
            }
            if (lookup.state() != Camunda810UserTaskClient.LookupState.OPEN) {
                recordRecoverableError(operationId, lookup.state().name());
                return;
            }
            boolean claimed = Boolean.TRUE.equals(transactions.execute(status -> {
                ConfirmOperation locked = operations.findByIdForUpdate(operationId).orElseThrow();
                if (locked.getState() != ConfirmOperation.State.PENDING || locked.getUserTaskKey() != null) return false;
                locked.setUserTaskKey(lookup.taskKey());
                locked.setState(ConfirmOperation.State.COMPLETING);
                locked.setAttempts(locked.getAttempts() + 1);
                locked.setUpdatedAt(Instant.now());
                operations.saveAndFlush(locked);
                return true;
            }));
            if (!claimed) return;
            // Persist the completion intent before the remote command. A crash here is safely reconcilable.
            var result = tasks.complete(expected, lookup.taskKey(),
                    java.util.Map.of("confirmOperationId", operationId, "handoffDecision", "CONFIRMED"));
            transactions.executeWithoutResult(status -> {
                ConfirmOperation locked = operations.findByIdForUpdate(operationId).orElseThrow();
                if (locked.getState() == ConfirmOperation.State.COMPLETING) {
                    locked.setState(ConfirmOperation.State.RECONCILING);
                    locked.setLastError(result == Camunda810UserTaskClient.CompletionState.COMPLETED
                            ? null : result.name());
                    locked.setUpdatedAt(Instant.now());
                }
            });
            if (result == Camunda810UserTaskClient.CompletionState.WRONG_TASK) {
                intervention(operationId, "WRONG_TASK");
                return;
            }
            if (result == Camunda810UserTaskClient.CompletionState.ALREADY_COMPLETED
                    && !tasks.completedBy(expected, lookup.taskKey(), operationId)) {
                intervention(operationId, "TASK_COMPLETED_WITHOUT_OPERATION_MARKER");
                return;
            }
            operation = operations.findById(operationId).orElseThrow();
        }
        if (operation.getUserTaskKey() != null
                && tasks.completedBy(expected, operation.getUserTaskKey(), operationId)) {
            try {
                transactions.executeWithoutResult(status -> finalizeConfirmed(operationId));
            } catch (ConfirmException exception) {
                intervention(operationId, exception.code());
            } catch (RuntimeException exception) {
                try { recordRecoverableError(operationId, "DB_FINALIZATION_FAILED"); }
                catch (RuntimeException databaseUnavailable) { /* The durable row remains for retry. */ }
            }
            // A database exception leaves RECONCILING for a later retry; no second Camunda command is sent.
        }
    }

    private void finalizeConfirmed(String operationId) {
        ConfirmOperation initial = operations.findById(operationId).orElseThrow();
        Application application = applications.findByIdForUpdate(initial.getApplicationId())
                .orElseThrow(() -> new DossierNotFoundException(initial.getApplicationId()));
        ConfirmOperation operation = operations.findByIdForUpdate(operationId).orElseThrow();
        if (operation.getState() == ConfirmOperation.State.COMPLETED) return;
        if (operation.getState() == ConfirmOperation.State.REQUIRES_INTERVENTION
                || operation.getUserTaskKey() == null) return;
        var current = versions.findFirstByApplicationApplicationIdOrderByVersionNumberDesc(operation.getApplicationId())
                .orElseThrow(() -> new DossierNotFoundException(operation.getApplicationId()));
        if (current.getVersionNumber() != operation.getDossierVersion()
                || application.getCompletenessStatus() == CompletenessStatus.READY_FOR_CREDIT_REVIEW) {
            throw failure(HttpStatus.CONFLICT, "STALE_DOSSIER_VERSION", "Dossier changed during confirmation");
        }
        var reviewed = requireEligibility(operation.getApplicationId(), operation.getDossierVersion());
        if (!operation.getSnapshotManifest().equals(reviewed.snapshot().manifestHash())
                || !operation.getSnapshotContext().equals(reviewed.snapshot().contextVersion())
                || !operation.getEvidenceValidUntil().equals(
                        persistedEvidenceExpiry(reviewed.snapshot().evidenceValidUntil()))
                || !operation.getReviewFingerprint().equals(fingerprint(reviewed))) {
            throw failure(HttpStatus.CONFLICT, "STALE_REVIEW_EVIDENCE", "Review evidence changed during confirmation");
        }
        String details = auditDetails(operation.getApplicationId(), operation.getDossierVersion(),
                application.getCompletenessStatus(), operation.getActorId(),
                operation.getReviewerName(), operation.getNote());
        if (details.length() > 1000) {
            throw failure(HttpStatus.BAD_REQUEST, "CONFIRM_AUDIT_TOO_LONG", "Audit context exceeds storage limits");
        }
        application.setCompletenessStatus(CompletenessStatus.READY_FOR_CREDIT_REVIEW);
        applications.saveAndFlush(application);
        AuditLog audit = new AuditLog();
        audit.setAuditLogId(UUID.randomUUID().toString());
        audit.setAction("DOSSIER_CONFIRMED");
        audit.setDetails(details);
        audit.setCreatedAt(Instant.now());
        audit.setApplication(application);
        auditLogs.saveAndFlush(audit);
        operation.setAuditLogId(audit.getAuditLogId());
        operation.setState(ConfirmOperation.State.COMPLETED);
        operation.setLastError(null);
        operation.setUpdatedAt(Instant.now());
        operations.saveAndFlush(operation);
    }

    private static Instant persistedEvidenceExpiry(Instant expiry) {
        // ConfirmOperations.evidence_valid_until is SQL Server datetime2(6).
        // Normalize before persistence so reloading cannot make unchanged evidence appear stale.
        return expiry.truncatedTo(ChronoUnit.MICROS);
    }

    private void intervention(String operationId, String code) {
        transactions.executeWithoutResult(status -> {
            ConfirmOperation operation = operations.findByIdForUpdate(operationId).orElseThrow();
            if (operation.getState() != ConfirmOperation.State.COMPLETED) {
                operation.setState(ConfirmOperation.State.REQUIRES_INTERVENTION);
                operation.setLastError(code);
                operation.setUpdatedAt(Instant.now());
            }
        });
    }

    private void recordRecoverableError(String operationId, String code) {
        transactions.executeWithoutResult(status -> {
            ConfirmOperation operation = operations.findByIdForUpdate(operationId).orElseThrow();
            if (operation.getState() == ConfirmOperation.State.PENDING
                    || operation.getState() == ConfirmOperation.State.COMPLETING
                    || operation.getState() == ConfirmOperation.State.RECONCILING) {
                operation.setLastError(code);
                operation.setUpdatedAt(Instant.now());
            }
        });
    }

    @Scheduled(fixedDelayString = "${app.camunda.confirm-recovery-ms:15000}")
    public void recoverOutstanding() {
        List<ConfirmOperation> outstanding = operations.findByStateIn(List.of(
                ConfirmOperation.State.PENDING, ConfirmOperation.State.COMPLETING,
                ConfirmOperation.State.RECONCILING));
        for (ConfirmOperation operation : outstanding) {
            try { recover(operation.getId()); }
            catch (RuntimeException ignored) {
                try { recordRecoverableError(operation.getId(), "RECOVERY_EXCEPTION"); }
                catch (RuntimeException stillUnavailable) { /* Preserve the row for the next pass. */ }
            }
        }
    }

    private void requireAccess(String applicationId, Authentication actor) {
        if (authorization.evaluate(actor, applicationId) != ConfirmAuthorizationService.Outcome.AUTHORIZED) {
            throw failure(HttpStatus.FORBIDDEN, "CONFIRM_CASE_ACCESS_DENIED", "Case access denied");
        }
    }

    private static String auditDetails(String applicationId, long version, CompletenessStatus previous,
                                       String actorId, String reviewerName, String note) {
        return "applicationId=" + applicationId.length() + ":" + applicationId
                + ";version=" + version + ";before=" + previous
                + ";after=READY_FOR_CREDIT_REVIEW;actor=" + actorId.length() + ":" + actorId
                + ";reviewerName=" + reviewerName.length() + ":" + reviewerName
                + ";note=" + (note == null ? "null" : note.length() + ":" + note);
    }

    private static String fingerprint(ConfirmReviewFactsSource.ReviewFacts reviewed) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(reviewed.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static ConfirmException failure(HttpStatus status, String code, String detail) {
        return new ConfirmException(status, code, "Confirmation unavailable", detail);
    }
}
