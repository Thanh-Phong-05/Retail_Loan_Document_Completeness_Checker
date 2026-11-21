package com.example.retail_loan_document_completeness_checker.security;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public class ConfirmAuthorizationService {

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
