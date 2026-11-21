package com.example.retail_loan_document_completeness_checker.services;

import com.example.retail_loan_document_completeness_checker.models.Application;
import com.example.retail_loan_document_completeness_checker.models.DocStatus;
import com.example.retail_loan_document_completeness_checker.models.DocType;
import com.example.retail_loan_document_completeness_checker.models.DocumentFile;
import com.example.retail_loan_document_completeness_checker.models.DossierVersion;
import com.example.retail_loan_document_completeness_checker.repositories.ApplicationRepository;
import com.example.retail_loan_document_completeness_checker.repositories.DossierVersionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Reads only facts persisted by the current backend. A read-only transaction does not protect a
 * later confirmation from concurrent imports; confirmation must recheck under its own protocol.
 */
@Service
public class ConfirmEligibilityFactsProvider {

    public enum ReadStatus {
        INVALID_INPUT, APPLICATION_NOT_FOUND, NO_CURRENT_VERSION,
        INCONSISTENT_ASSOCIATION, STALE_EXPECTED_VERSION, CURRENT_VERSION_READ
    }

    public record StoredFileFact(String fileId, String sha256, DocType docType,
                                 DocStatus processingStatus, int pageCount) {}

    public record ReadResult(ReadStatus status, ConfirmEligibilityService.VersionFacts versions,
                             ConfirmEligibilityService.SnapshotFacts snapshot,
                             ConfirmEligibilityService.ChecklistFacts checklist,
                             List<StoredFileFact> files) {
        public ReadResult {
            files = List.copyOf(files);
        }
    }

    private final ApplicationRepository applicationRepository;
    private final DossierVersionRepository dossierVersionRepository;

    public ConfirmEligibilityFactsProvider(ApplicationRepository applicationRepository,
                                           DossierVersionRepository dossierVersionRepository) {
        this.applicationRepository = applicationRepository;
        this.dossierVersionRepository = dossierVersionRepository;
    }

    @Transactional(readOnly = true)
    public ReadResult read(String applicationId, Long expectedVersion) {
        if (applicationId == null || applicationId.isBlank()
                || expectedVersion == null || expectedVersion <= 0) {
            return unavailable(ReadStatus.INVALID_INPUT);
        }

        Application application = applicationRepository.findById(applicationId).orElse(null);
        if (application == null) {
            return unavailable(ReadStatus.APPLICATION_NOT_FOUND);
        }
        if (!applicationId.equals(application.getApplicationId())) {
            return unavailable(ReadStatus.INCONSISTENT_ASSOCIATION);
        }

        DossierVersion current = dossierVersionRepository
                .findFirstByApplicationApplicationIdOrderByVersionNumberDesc(applicationId)
                .orElse(null);
        if (current == null) {
            return unavailable(ReadStatus.NO_CURRENT_VERSION);
        }
        if (current.getVersionNumber() <= 0 || current.getDossierVersionId() == null
                || current.getDossierVersionId().isBlank() || current.getApplication() == null
                || !applicationId.equals(current.getApplication().getApplicationId())) {
            return unavailable(ReadStatus.INCONSISTENT_ASSOCIATION);
        }

        long persistedVersion = current.getVersionNumber();
        // No persisted checklist evaluation version exists; never substitute the request version.
        var versions = new ConfirmEligibilityService.VersionFacts(
                application.getApplicationId(), persistedVersion, persistedVersion, null);
        if (persistedVersion != expectedVersion) {
            return new ReadResult(ReadStatus.STALE_EXPECTED_VERSION, versions, null, null, List.of());
        }

        List<StoredFileFact> files = new java.util.ArrayList<>();
        for (DocumentFile document : current.getDocuments()) {
            if (document == null || document.getDossierVersion() == null
                    || !current.getDossierVersionId().equals(document.getDossierVersion().getDossierVersionId())) {
                return unavailable(ReadStatus.INCONSISTENT_ASSOCIATION);
            }
            // Raw VALID is a processing status, not accepted evidence for a checklist requirement.
            files.add(new StoredFileFact(document.getFileId(), document.getFileHashSha256(),
                    document.getDocType(), document.getStatus(), document.getPageCount()));
        }
        // Manifest, attestation, accepted evidence and checklist have no authoritative source yet.
        return new ReadResult(ReadStatus.CURRENT_VERSION_READ, versions, null, null, files);
    }

    private static ReadResult unavailable(ReadStatus status) {
        return new ReadResult(status, null, null, null, List.of());
    }
}
