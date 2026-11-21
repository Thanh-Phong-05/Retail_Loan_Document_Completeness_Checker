package com.example.retail_loan_document_completeness_checker.services;

import com.example.retail_loan_document_completeness_checker.dto.ConfirmDossierRequest;
import com.example.retail_loan_document_completeness_checker.dto.ConfirmDossierResponse;
import com.example.retail_loan_document_completeness_checker.exceptions.ConfirmException;
import com.example.retail_loan_document_completeness_checker.exceptions.DossierNotFoundException;
import com.example.retail_loan_document_completeness_checker.models.Application;
import com.example.retail_loan_document_completeness_checker.models.AuditLog;
import com.example.retail_loan_document_completeness_checker.models.CompletenessStatus;
import com.example.retail_loan_document_completeness_checker.repositories.ApplicationRepository;
import com.example.retail_loan_document_completeness_checker.repositories.AuditLogRepository;
import com.example.retail_loan_document_completeness_checker.repositories.DossierVersionRepository;
import com.example.retail_loan_document_completeness_checker.security.ConfirmAuthorizationService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class ConfirmService {

    private final ConfirmAuthorizationService authorization;
    private final ApplicationRepository applications;
    private final DossierVersionRepository versions;
    private final AuditLogRepository auditLogs;
    private final ConfirmEligibilityFactsProvider persistedFacts;
    private final ObjectProvider<ConfirmReviewFactsSource> reviewFactsSources;
    private final ObjectProvider<ConfirmWorkflowGateway> workflowGateways;
    private final ConfirmEligibilityService eligibility = new ConfirmEligibilityService();

    public ConfirmService(ConfirmAuthorizationService authorization,
                          ApplicationRepository applications,
                          DossierVersionRepository versions,
                          AuditLogRepository auditLogs,
                          ConfirmEligibilityFactsProvider persistedFacts,
                          ObjectProvider<ConfirmReviewFactsSource> reviewFactsSources,
                          ObjectProvider<ConfirmWorkflowGateway> workflowGateways) {
        this.authorization = authorization;
        this.applications = applications;
        this.versions = versions;
        this.auditLogs = auditLogs;
        this.persistedFacts = persistedFacts;
        this.reviewFactsSources = reviewFactsSources;
        this.workflowGateways = workflowGateways;
    }

    @Transactional
    public ConfirmDossierResponse confirm(String applicationId, ConfirmDossierRequest request,
                                          Authentication actor) {
        if (authorization.evaluate(actor, applicationId) != ConfirmAuthorizationService.Outcome.AUTHORIZED) {
            throw failure(HttpStatus.FORBIDDEN, "CONFIRM_CASE_ACCESS_DENIED", "Confirmation forbidden",
                    "The authenticated reviewer is not authorized for this dossier");
        }

        // The upload path takes this same row lock for existing applications.
        Application application = applications.findByIdForUpdate(applicationId)
                .orElseThrow(() -> new DossierNotFoundException(applicationId));
        var current = versions.findFirstByApplicationApplicationIdOrderByVersionNumberDesc(applicationId)
                .orElseThrow(() -> new DossierNotFoundException(applicationId));
        long currentVersion = current.getVersionNumber();
        if (currentVersion != request.expectedVersion()) {
            throw failure(HttpStatus.CONFLICT, "STALE_DOSSIER_VERSION", "Stale dossier version",
                    "The expected dossier version is no longer current");
        }
        if (application.getCompletenessStatus() == CompletenessStatus.READY_FOR_CREDIT_REVIEW) {
            throw failure(HttpStatus.CONFLICT, "DOSSIER_ALREADY_CONFIRMED", "Dossier already confirmed",
                    "This dossier version has already been confirmed");
        }

        var stored = persistedFacts.read(applicationId, request.expectedVersion());
        if (stored.status() != ConfirmEligibilityFactsProvider.ReadStatus.CURRENT_VERSION_READ
                || stored.versions() == null || stored.versions().dossierVersion() != currentVersion) {
            throw failure(HttpStatus.SERVICE_UNAVAILABLE, "CONFIRM_FACTS_UNAVAILABLE", "Review facts unavailable",
                    "Persisted dossier facts could not be verified");
        }

        ConfirmReviewFactsSource source = reviewFactsSources.getIfAvailable();
        if (source == null) {
            throw factsUnavailable();
        }
        ConfirmReviewFactsSource.ReviewFacts reviewed;
        try {
            reviewed = source.read(applicationId, currentVersion);
        } catch (RuntimeException exception) {
            throw factsUnavailable();
        }
        if (reviewed == null) {
            throw factsUnavailable();
        }
        var persistedVersion = stored.versions();
        var versionFacts = new ConfirmEligibilityService.VersionFacts(
                persistedVersion.applicationId(), persistedVersion.dossierVersion(),
                persistedVersion.latestVersion(), reviewed.evaluationVersion());
        var decision = eligibility.evaluate(versionFacts, reviewed.snapshot(), reviewed.checklist());
        if (decision == ConfirmEligibilityService.Decision.INDETERMINATE) {
            throw factsUnavailable();
        }
        if (decision == ConfirmEligibilityService.Decision.NOT_ELIGIBLE) {
            throw failure(HttpStatus.CONFLICT, "DOSSIER_NOT_ELIGIBLE", "Dossier not eligible",
                    "Verified review facts do not permit confirmation");
        }
        if (Instant.now().isAfter(reviewed.snapshot().evidenceValidUntil())) {
            throw failure(HttpStatus.CONFLICT, "DOSSIER_REVIEW_EXPIRED", "Review snapshot expired",
                    "The verified evidence validity period has expired");
        }

        ConfirmWorkflowGateway gateway = workflowGateways.getIfAvailable();
        if (gateway == null) {
            throw workflowUnavailable();
        }
        ConfirmWorkflowGateway.TaskState taskState;
        try {
            taskState = gateway.inspect(applicationId, currentVersion);
        } catch (RuntimeException exception) {
            throw workflowUnavailable();
        }
        // A present task needs durable completion/recovery and a verified Camunda correlation contract.
        // Until those exist, do not commit READY or claim that a NoOp adapter completed it.
        if (taskState != ConfirmWorkflowGateway.TaskState.VERIFIED_ABSENT) {
            throw workflowUnavailable();
        }

        CompletenessStatus previous = application.getCompletenessStatus();
        String actorId = actor.getName();
        String details = auditDetails(applicationId, currentVersion, previous,
                actorId, request.reviewerName(), request.note());
        if (details.length() > 1000) {
            throw failure(HttpStatus.BAD_REQUEST, "CONFIRM_AUDIT_TOO_LONG", "Confirmation audit too long",
                    "Actor, reviewer name and note cannot fit in the existing audit details column");
        }

        Instant now = Instant.now();
        application.setCompletenessStatus(CompletenessStatus.READY_FOR_CREDIT_REVIEW);
        applications.saveAndFlush(application);
        AuditLog audit = new AuditLog();
        audit.setAuditLogId(UUID.randomUUID().toString());
        audit.setAction("DOSSIER_CONFIRMED");
        audit.setDetails(details);
        audit.setCreatedAt(now);
        audit.setApplication(application);
        auditLogs.saveAndFlush(audit);
        return new ConfirmDossierResponse(applicationId, currentVersion,
                CompletenessStatus.READY_FOR_CREDIT_REVIEW, audit.getAuditLogId(), now,
                "VERIFIED_NO_OPEN_TASK");
    }

    private static String auditDetails(String applicationId, long version, CompletenessStatus previous,
                                       String actorId, String reviewerName, String note) {
        // Length-prefixed values keep arbitrary punctuation and Unicode unambiguous without truncation.
        return "applicationId=" + applicationId.length() + ":" + applicationId
                + ";version=" + version + ";before=" + previous
                + ";after=READY_FOR_CREDIT_REVIEW;actor=" + actorId.length() + ":" + actorId
                + ";reviewerName=" + reviewerName.length() + ":" + reviewerName
                + ";note=" + (note == null ? "null" : note.length() + ":" + note);
    }

    private static ConfirmException factsUnavailable() {
        return failure(HttpStatus.SERVICE_UNAVAILABLE, "CONFIRM_FACTS_UNAVAILABLE", "Review facts unavailable",
                "An authoritative review snapshot and checklist are not available");
    }

    private static ConfirmException workflowUnavailable() {
        return failure(HttpStatus.SERVICE_UNAVAILABLE, "CONFIRM_WORKFLOW_UNAVAILABLE", "Workflow unavailable",
                "Open Camunda task state or safe completion could not be verified");
    }

    private static ConfirmException failure(HttpStatus status, String code, String title, String detail) {
        return new ConfirmException(status, code, title, detail);
    }
}
