package com.example.retail_loan_document_completeness_checker.security;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public class ConfirmAuthorizationService {

    /** The HTTP API is public; every Confirm mutation/status read must enforce this service boundary. */
    public void requireAccess(Authentication actor, String applicationId) {
        Outcome outcome = evaluate(actor, applicationId);
        if (outcome == Outcome.AUTHORIZED) return;
        boolean anonymous = outcome == Outcome.UNAUTHENTICATED;
        throw new com.example.retail_loan_document_completeness_checker.exceptions.ConfirmException(
                anonymous ? org.springframework.http.HttpStatus.UNAUTHORIZED : org.springframework.http.HttpStatus.FORBIDDEN,
                anonymous ? "CONFIRM_AUTHENTICATION_REQUIRED" : "CONFIRM_ACCESS_DENIED",
                "Confirmation access denied", "A trusted reviewer with access to this dossier is required");
    }

    private final ObjectProvider<ConfirmCaseScope> caseScope;

    public ConfirmAuthorizationService(ObjectProvider<ConfirmCaseScope> caseScope) {
        this.caseScope = caseScope;
    }

    public enum Outcome {
        UNAUTHENTICATED,
        ROLE_DENIED,
        CASE_SCOPE_UNVERIFIED,
        AUTHORIZED
    }

    public Outcome evaluate(Authentication authentication, String applicationId) {
        if (authentication == null || !authentication.isAuthenticated()
                || authentication.getName() == null || authentication.getName().isBlank()
                || authentication instanceof AnonymousAuthenticationToken) {
            return Outcome.UNAUTHENTICATED;
        }

        boolean reviewerOrOfficer = authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_REVIEWER".equals(authority.getAuthority())
                        || "ROLE_OFFICER".equals(authority.getAuthority()));
        if (!reviewerOrOfficer) {
            return Outcome.ROLE_DENIED;
        }

        // No provider is installed in production until trusted case assignment is integrated.
        ConfirmCaseScope scope = caseScope.getIfAvailable();
        return scope != null && scope.mayConfirm(authentication, applicationId)
                ? Outcome.AUTHORIZED : Outcome.CASE_SCOPE_UNVERIFIED;
    }
}
