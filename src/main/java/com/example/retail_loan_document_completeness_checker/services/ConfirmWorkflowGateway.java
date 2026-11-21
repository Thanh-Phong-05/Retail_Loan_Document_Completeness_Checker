package com.example.retail_loan_document_completeness_checker.services;

/** A verified task lookup is required before confirmation; UNKNOWN must never mean absent. */
public interface ConfirmWorkflowGateway {

    enum TaskState { VERIFIED_ABSENT, PRESENT, UNKNOWN }

    TaskState inspect(String applicationId, long dossierVersion);
}
