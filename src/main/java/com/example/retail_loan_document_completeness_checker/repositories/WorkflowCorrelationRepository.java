package com.example.retail_loan_document_completeness_checker.repositories;

import com.example.retail_loan_document_completeness_checker.models.WorkflowCorrelation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.List;

public interface WorkflowCorrelationRepository extends JpaRepository<WorkflowCorrelation, String> {
    Optional<WorkflowCorrelation> findByApplicationIdAndDossierVersion(String applicationId, long dossierVersion);
    List<WorkflowCorrelation> findByState(WorkflowCorrelation.State state);
}
