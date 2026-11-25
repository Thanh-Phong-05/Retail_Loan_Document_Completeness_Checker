package com.example.retail_loan_document_completeness_checker.services;

/** Authoritative, version-bound review facts. Uploaded files and processing statuses are insufficient. */
public interface ConfirmReviewFactsSource {

    record ReviewFacts(Long evaluationVersion,
                       ConfirmEligibilityService.SnapshotFacts snapshot,
                       ConfirmEligibilityService.ChecklistFacts checklist) {}

    ReviewFacts read(String applicationId, long dossierVersion);
}
