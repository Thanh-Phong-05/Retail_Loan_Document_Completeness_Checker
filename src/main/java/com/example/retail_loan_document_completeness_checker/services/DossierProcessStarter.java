package com.example.retail_loan_document_completeness_checker.services;

public interface DossierProcessStarter {

    void start(String applicationId, int dossierVersion);
}
