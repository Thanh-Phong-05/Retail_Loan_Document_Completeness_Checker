package com.example.retail_loan_document_completeness_checker.exceptions;

public class DossierNotFoundException extends RuntimeException {

    public DossierNotFoundException(String applicationId) {
        super("Dossier not found: " + applicationId);
    }
}
