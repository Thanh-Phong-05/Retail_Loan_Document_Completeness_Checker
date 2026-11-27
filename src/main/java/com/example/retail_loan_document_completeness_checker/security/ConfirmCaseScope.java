package com.example.retail_loan_document_completeness_checker.security;

import org.springframework.security.core.Authentication;

/** Supplied only by an integration with trusted case assignments. No production implementation exists yet. */
public interface ConfirmCaseScope {
    boolean mayConfirm(Authentication authentication, String applicationId);
}
