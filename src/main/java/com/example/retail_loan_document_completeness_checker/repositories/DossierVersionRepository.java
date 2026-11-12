package com.example.retail_loan_document_completeness_checker.repositories;

import com.example.retail_loan_document_completeness_checker.models.DossierVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DossierVersionRepository extends JpaRepository<DossierVersion, String> {

    Optional<DossierVersion> findFirstByApplicationApplicationIdOrderByVersionNumberDesc(String applicationId);
}
