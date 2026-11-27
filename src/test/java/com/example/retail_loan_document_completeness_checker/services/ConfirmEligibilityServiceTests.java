package com.example.retail_loan_document_completeness_checker.services;

import com.example.retail_loan_document_completeness_checker.services.ConfirmEligibilityService.Applicability;
import com.example.retail_loan_document_completeness_checker.services.ConfirmEligibilityService.ChecklistFacts;
import com.example.retail_loan_document_completeness_checker.services.ConfirmEligibilityService.Decision;
import com.example.retail_loan_document_completeness_checker.services.ConfirmEligibilityService.EvidenceState;
import com.example.retail_loan_document_completeness_checker.services.ConfirmEligibilityService.ExceptionState;
import com.example.retail_loan_document_completeness_checker.services.ConfirmEligibilityService.RequirementFact;
import com.example.retail_loan_document_completeness_checker.services.ConfirmEligibilityService.SnapshotFacts;
import com.example.retail_loan_document_completeness_checker.services.ConfirmEligibilityService.VersionFacts;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConfirmEligibilityServiceTests {

    private static final Instant IMPORT_TIME = Instant.parse("2026-11-19T08:00:00Z");
    private static final Instant AS_OF = Instant.parse("2026-11-20T08:00:00Z");
    private static final Instant VALID_UNTIL = Instant.parse("2026-12-01T00:00:00Z");

    private final ConfirmEligibilityService evaluator = new ConfirmEligibilityService();

    @Test
    void completeVerifiedSnapshotIsEligibleOnlyWithExplicitFacts() {
        assertThat(evaluator.evaluate(versions(), snapshot(), checklist())).isEqualTo(Decision.ELIGIBLE);
    }

    @Test
    void missingRequiredEvidenceIsNotEligible() {
        assertThat(withRequirement(requirement(EvidenceState.MISSING))).isEqualTo(Decision.NOT_ELIGIBLE);
    }

    @Test
    void unknownOrUnverifiedEvidenceNeverPasses() {
        assertThat(withRequirement(requirement(EvidenceState.UNKNOWN))).isEqualTo(Decision.INDETERMINATE);
        assertThat(withRequirement(requirement(EvidenceState.UNVERIFIED))).isEqualTo(Decision.NOT_ELIGIBLE);
    }

    @Test
    void pendingOrFailedRequiredEvidenceNeverPasses() {
        assertThat(withRequirement(requirement(EvidenceState.PENDING))).isEqualTo(Decision.NOT_ELIGIBLE);
        assertThat(withRequirement(requirement(EvidenceState.FAILED))).isEqualTo(Decision.NOT_ELIGIBLE);
    }

    @Test
    void checklistMustBeKnownAndFullyEnumerated() {
        assertThat(evaluator.evaluate(versions(), snapshot(), null)).isEqualTo(Decision.INDETERMINATE);
        assertThat(evaluator.evaluate(versions(), snapshot(), new ChecklistFacts("APP-001", 2L,
                "HASH-1", "CTX-1", null, true, List.of(accepted()))))
                .isEqualTo(Decision.INDETERMINATE);
        assertThat(evaluator.evaluate(versions(), snapshot(), new ChecklistFacts("APP-001", 2L,
                "HASH-1", "CTX-1", "POLICY-1", false, List.of(accepted()))))
                .isEqualTo(Decision.INDETERMINATE);
        assertThat(evaluator.evaluate(versions(), snapshot(), new ChecklistFacts("APP-001", 2L,
                "HASH-1", "CTX-1", "POLICY-1", true, List.of())))
                .isEqualTo(Decision.INDETERMINATE);
    }

    @Test
    void missingCurrentVersionIsIndeterminate() {
        assertThat(evaluator.evaluate(new VersionFacts("APP-001", 2L, null, 2L), snapshot(), checklist()))
                .isEqualTo(Decision.INDETERMINATE);
        assertThat(evaluator.evaluate(null, snapshot(), checklist())).isEqualTo(Decision.INDETERMINATE);
    }

    @Test
    void staleDossierOrEvaluationVersionIsNotEligible() {
        assertThat(evaluator.evaluate(new VersionFacts("APP-001", 2L, 3L, 2L), snapshot(), checklist()))
                .isEqualTo(Decision.NOT_ELIGIBLE);
        assertThat(evaluator.evaluate(new VersionFacts("APP-001", 3L, 3L, 2L), snapshot(), checklist()))
                .isEqualTo(Decision.NOT_ELIGIBLE);
    }

    @Test
    void factsBoundToAnotherApplicationVersionManifestOrContextAreNotEligible() {
        assertThat(evaluator.evaluate(versions(), new SnapshotFacts("OTHER", 2L, "HASH-1", "CTX-1",
                IMPORT_TIME, AS_OF, VALID_UNTIL, true, true, true, true), checklist()))
                .isEqualTo(Decision.NOT_ELIGIBLE);
        assertThat(evaluator.evaluate(versions(), new SnapshotFacts("APP-001", 1L, "HASH-1", "CTX-1",
                IMPORT_TIME, AS_OF, VALID_UNTIL, true, true, true, true), checklist()))
                .isEqualTo(Decision.NOT_ELIGIBLE);
        assertThat(evaluator.evaluate(versions(), snapshot(), new ChecklistFacts("APP-001", 2L,
                "OLD-HASH", "CTX-1", "POLICY-1", true, List.of(accepted()))))
                .isEqualTo(Decision.NOT_ELIGIBLE);
        assertThat(evaluator.evaluate(versions(), snapshot(), new ChecklistFacts("APP-001", 2L,
                "HASH-1", "OLD-CTX", "POLICY-1", true, List.of(accepted()))))
                .isEqualTo(Decision.NOT_ELIGIBLE);
    }

    @Test
    void unresolvedExpiredOrUnauthorizedExceptionBlocksReadiness() {
        for (ExceptionState state : List.of(ExceptionState.UNRESOLVED,
                ExceptionState.EXPIRED, ExceptionState.UNAUTHORIZED)) {
            assertThat(withRequirement(new RequirementFact("REQ-1", Applicability.APPLICABLE,
                    null, EvidenceState.ACCEPTED, List.of("FILE-1"), state)))
                    .isEqualTo(Decision.NOT_ELIGIBLE);
        }
    }

    @Test
    void exceptionClaimAloneCannotEstablishEligibilityWithoutPolicyContract() {
        assertThat(withRequirement(new RequirementFact("REQ-1", Applicability.APPLICABLE,
                null, EvidenceState.MISSING, List.of(), ExceptionState.VALID_AUTHORIZED)))
                .isEqualTo(Decision.INDETERMINATE);
    }

    @Test
    void missingOrNegativeSnapshotFactsNeverDefaultToSatisfied() {
        assertThat(evaluator.evaluate(versions(), new SnapshotFacts("APP-001", 2L, null, "CTX-1", IMPORT_TIME,
                AS_OF, VALID_UNTIL, true, true, true, true), checklist()))
                .isEqualTo(Decision.INDETERMINATE);
        assertThat(evaluator.evaluate(versions(), new SnapshotFacts("APP-001", 2L, "HASH-1", "CTX-1", IMPORT_TIME,
                AS_OF, VALID_UNTIL, null, true, true, true), checklist()))
                .isEqualTo(Decision.INDETERMINATE);
        assertThat(evaluator.evaluate(versions(), new SnapshotFacts("APP-001", 2L, "HASH-1", "CTX-1", IMPORT_TIME,
                AS_OF, VALID_UNTIL, false, true, true, true), checklist()))
                .isEqualTo(Decision.NOT_ELIGIBLE);
        assertThat(evaluator.evaluate(versions(), new SnapshotFacts("APP-001", 2L, "HASH-1", "CTX-1", IMPORT_TIME,
                AS_OF, VALID_UNTIL, true, false, true, true), checklist()))
                .isEqualTo(Decision.NOT_ELIGIBLE);
        assertThat(evaluator.evaluate(versions(), new SnapshotFacts("APP-001", 2L, "HASH-1", "CTX-1", IMPORT_TIME,
                AS_OF, VALID_UNTIL, true, true, false, true), checklist()))
                .isEqualTo(Decision.NOT_ELIGIBLE);
        assertThat(evaluator.evaluate(versions(), new SnapshotFacts("APP-001", 2L, "HASH-1", "CTX-1", IMPORT_TIME,
                AS_OF, VALID_UNTIL, true, true, true, false), checklist()))
                .isEqualTo(Decision.NOT_ELIGIBLE);
    }

    @Test
    void expiredEvidenceCannotPassEvenWithoutAnUpload() {
        assertThat(evaluator.evaluate(versions(), new SnapshotFacts("APP-001", 2L, "HASH-1", "CTX-1", IMPORT_TIME,
                AS_OF, IMPORT_TIME, true, true, true, true), checklist()))
                .isEqualTo(Decision.NOT_ELIGIBLE);
    }

    @Test
    void acceptedStateWithoutEvidenceReferencesCannotPass() {
        assertThat(withRequirement(new RequirementFact("REQ-1", Applicability.APPLICABLE,
                null, EvidenceState.ACCEPTED, List.of(), ExceptionState.NONE)))
                .isEqualTo(Decision.INDETERMINATE);
        assertThat(withRequirement(new RequirementFact("REQ-1", Applicability.APPLICABLE,
                null, EvidenceState.ACCEPTED, null, ExceptionState.NONE)))
                .isEqualTo(Decision.INDETERMINATE);
    }

    @Test
    void notApplicableRequirementNeedsAnApprovedConditionAndReason() {
        assertThat(withRequirement(new RequirementFact("REQ-1", Applicability.APPROVED_NOT_APPLICABLE,
                "Approved condition for this case", null, null, ExceptionState.NONE)))
                .isEqualTo(Decision.ELIGIBLE);
        assertThat(withRequirement(new RequirementFact("REQ-1", Applicability.APPROVED_NOT_APPLICABLE,
                null, null, null, ExceptionState.NONE)))
                .isEqualTo(Decision.INDETERMINATE);
    }

    @Test
    void frontendOnlyStateCannotEstablishEligibilityOrSupplyActorAuthority() {
        assertThat(evaluator.evaluate(null, null, null)).isEqualTo(Decision.INDETERMINATE);
        assertThat(ConfirmEligibilityService.class.getDeclaredMethods())
                .filteredOn(method -> method.getName().equals("evaluate"))
                .singleElement()
                .satisfies(method -> assertThat(method.getParameterTypes())
                        .containsExactly(VersionFacts.class, SnapshotFacts.class, ChecklistFacts.class));
    }

    @Test
    void evaluationLeavesFactsUnchangedAndHasNoPersistenceCollaborators() {
        List<RequirementFact> requirements = new ArrayList<>(List.of(accepted()));
        ChecklistFacts facts = new ChecklistFacts("APP-001", 2L,
                "HASH-1", "CTX-1", "POLICY-1", true, requirements);
        assertThat(evaluator.evaluate(versions(), snapshot(), facts)).isEqualTo(Decision.ELIGIBLE);
        assertThat(requirements).containsExactly(accepted());
        assertThat(ConfirmEligibilityService.class.getDeclaredFields())
                .filteredOn(field -> !Modifier.isStatic(field.getModifiers()))
                .isEmpty();
    }

    private Decision withRequirement(RequirementFact requirement) {
        return evaluator.evaluate(versions(), snapshot(), new ChecklistFacts("APP-001", 2L,
                "HASH-1", "CTX-1", "POLICY-1", true, List.of(requirement)));
    }

    private VersionFacts versions() {
        return new VersionFacts("APP-001", 2L, 2L, 2L);
    }

    private SnapshotFacts snapshot() {
        return new SnapshotFacts("APP-001", 2L, "HASH-1", "CTX-1", IMPORT_TIME, AS_OF, VALID_UNTIL,
                true, true, true, true);
    }

    private ChecklistFacts checklist() {
        return new ChecklistFacts("APP-001", 2L,
                "HASH-1", "CTX-1", "POLICY-1", true, List.of(accepted()));
    }

    private RequirementFact accepted() {
        return new RequirementFact("REQ-1", Applicability.APPLICABLE,
                null, EvidenceState.ACCEPTED, List.of("FILE-1"), ExceptionState.NONE);
    }

    private RequirementFact requirement(EvidenceState state) {
        return new RequirementFact("REQ-1", Applicability.APPLICABLE,
                null, state, List.of(), ExceptionState.NONE);
    }
}
