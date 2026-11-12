package com.example.retail_loan_document_completeness_checker.services;

import com.example.retail_loan_document_completeness_checker.dto.UploadDossierRequest;
import com.example.retail_loan_document_completeness_checker.repositories.ApplicationRepository;
import com.example.retail_loan_document_completeness_checker.repositories.AuditLogRepository;
import com.example.retail_loan_document_completeness_checker.repositories.DossierVersionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = "debug=false")
@Import(DossierPersistenceService.class)
class DossierPersistenceServiceTests {

    @Autowired
    DossierPersistenceService service;

    @Autowired
    ApplicationRepository applicationRepository;

    @Autowired
    DossierVersionRepository dossierVersionRepository;

    @Autowired
    AuditLogRepository auditLogRepository;

    @Test
    void createsNextVersionForExistingApplication() {
        var request = new UploadDossierRequest(
                "APP-001",
                "UNSECURED_SALARIED_LOAN",
                "Dao Quoc Thanh"
        );

        var first = service.saveUpload(request, List.of(storedFile("FILE-001")));
        var second = service.saveUpload(request, List.of(storedFile("FILE-002")));

        assertThat(first.version()).isEqualTo(1);
        assertThat(second.version()).isEqualTo(2);
        assertThat(service.getLatest("APP-001").documents())
                .extracting(document -> document.fileId())
                .containsExactly("FILE-002");
        assertThat(applicationRepository.count()).isEqualTo(1);
        assertThat(dossierVersionRepository.count()).isEqualTo(2);
        assertThat(auditLogRepository.count()).isEqualTo(2);
    }

    private StoredFile storedFile(String id) {
        return new StoredFile(
                id,
                "statement.pdf",
                id + ".pdf",
                Path.of("uploads", id + ".pdf"),
                "application/pdf",
                100,
                "a".repeat(64)
        );
    }
}
