package com.example.retail_loan_document_completeness_checker.services;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Assesses explicitly supplied, trusted dossier facts. Current repositories do not supply all
 * required review facts; confirmation uses this evaluator only when an authoritative source is present.
 */
public final class ConfirmEligibilityService {

    public enum Decision { ELIGIBLE, NOT_ELIGIBLE, INDETERMINATE }

    public enum Applicability { APPLICABLE, APPROVED_NOT_APPLICABLE, UNKNOWN }

    public enum EvidenceState { ACCEPTED, MISSING, UNVERIFIED, PENDING, FAILED, UNKNOWN }

    public enum ExceptionState { NONE, VALID_AUTHORIZED, UNRESOLVED, EXPIRED, UNAUTHORIZED, UNKNOWN }

    public record VersionFacts(String applicationId, Long dossierVersion,
                               Long latestVersion, Long evaluationVersion) {}

    public record SnapshotFacts(String applicationId, Long dossierVersion,
                                String manifestHash, String contextVersion,
                                Instant importTime, Instant asOf, Instant evidenceValidUntil,
                                Boolean manifestProcessingComplete, Boolean criticalFieldsVerified,
                                Boolean snapshotAttested, Boolean noKnownSourceChange) {}

    public record ChecklistFacts(String applicationId, Long dossierVersion,
                                 String evaluatedManifestHash, String evaluatedContextVersion,
                                 String version, Boolean allRequirementsEnumerated,
                                 List<RequirementFact> requirements) {
        public ChecklistFacts {
            if (requirements != null) requirements = Collections.unmodifiableList(new ArrayList<>(requirements));
        }
    }

    public record RequirementFact(String requirementId, Applicability applicability,
                                  String notApplicableReason, EvidenceState evidenceState,
                                  List<String> acceptedEvidenceRefs, ExceptionState exceptionState) {
        public RequirementFact {
            if (acceptedEvidenceRefs != null) {
                acceptedEvidenceRefs = Collections.unmodifiableList(new ArrayList<>(acceptedEvidenceRefs));
            }
        }
    }

    public Decision evaluate(VersionFacts versions, SnapshotFacts snapshot, ChecklistFacts checklist) {
        Decision result = combine(evaluateVersions(versions), evaluateBindings(versions, snapshot, checklist));
        return combine(combine(result, evaluateSnapshot(snapshot)), evaluateChecklist(checklist));
    }

    private Decision evaluateBindings(VersionFacts versions, SnapshotFacts snapshot, ChecklistFacts checklist) {
        if (versions == null || snapshot == null || checklist == null
                || blank(versions.applicationId()) || invalid(versions.dossierVersion())
                || blank(snapshot.applicationId()) || invalid(snapshot.dossierVersion())
                || blank(snapshot.manifestHash()) || blank(snapshot.contextVersion())
                || blank(checklist.applicationId()) || invalid(checklist.dossierVersion())
                || blank(checklist.evaluatedManifestHash()) || blank(checklist.evaluatedContextVersion())) {
            return Decision.INDETERMINATE;
        }
        return versions.applicationId().equals(snapshot.applicationId())
                && versions.applicationId().equals(checklist.applicationId())
                && versions.dossierVersion().equals(snapshot.dossierVersion())
                && versions.dossierVersion().equals(checklist.dossierVersion())
                && snapshot.manifestHash().equals(checklist.evaluatedManifestHash())
                && snapshot.contextVersion().equals(checklist.evaluatedContextVersion())
                ? Decision.ELIGIBLE : Decision.NOT_ELIGIBLE;
    }

    private Decision evaluateVersions(VersionFacts facts) {
        if (facts == null || blank(facts.applicationId()) || invalid(facts.dossierVersion())
                || invalid(facts.latestVersion()) || invalid(facts.evaluationVersion())) {
            return Decision.INDETERMINATE;
        }
        return facts.dossierVersion().equals(facts.latestVersion())
                && facts.dossierVersion().equals(facts.evaluationVersion())
                ? Decision.ELIGIBLE : Decision.NOT_ELIGIBLE;
    }

    private Decision evaluateSnapshot(SnapshotFacts facts) {
        if (facts == null || blank(facts.manifestHash()) || blank(facts.contextVersion())
                || facts.importTime() == null || facts.asOf() == null
                || facts.evidenceValidUntil() == null) {
            return Decision.INDETERMINATE;
        }
        Decision result = facts.asOf().isBefore(facts.importTime())
                || facts.asOf().isAfter(facts.evidenceValidUntil())
                ? Decision.NOT_ELIGIBLE : Decision.ELIGIBLE;
        result = combine(result, requiredTrue(facts.manifestProcessingComplete()));
        result = combine(result, requiredTrue(facts.criticalFieldsVerified()));
        result = combine(result, requiredTrue(facts.snapshotAttested()));
        return combine(result, requiredTrue(facts.noKnownSourceChange()));
    }

    private Decision evaluateChecklist(ChecklistFacts facts) {
        if (facts == null || blank(facts.version()) || !Boolean.TRUE.equals(facts.allRequirementsEnumerated())
                || facts.requirements() == null || facts.requirements().isEmpty()) {
            return Decision.INDETERMINATE;
        }
        Decision result = Decision.ELIGIBLE;
        for (RequirementFact requirement : facts.requirements()) {
            result = combine(result, evaluateRequirement(requirement));
        }
        return result;
    }

    private Decision evaluateRequirement(RequirementFact fact) {
        if (fact == null || blank(fact.requirementId()) || fact.applicability() == null
                || fact.applicability() == Applicability.UNKNOWN || fact.exceptionState() == null
                || fact.exceptionState() == ExceptionState.UNKNOWN) {
            return Decision.INDETERMINATE;
        }
        if (fact.exceptionState() == ExceptionState.UNRESOLVED
                || fact.exceptionState() == ExceptionState.EXPIRED
                || fact.exceptionState() == ExceptionState.UNAUTHORIZED) {
            return Decision.NOT_ELIGIBLE;
        }
        // Exception-only satisfaction needs an approved policy/scope contract not present here.
        if (fact.exceptionState() == ExceptionState.VALID_AUTHORIZED) {
            return Decision.INDETERMINATE;
        }
        if (fact.applicability() == Applicability.APPROVED_NOT_APPLICABLE) {
            return blank(fact.notApplicableReason()) ? Decision.INDETERMINATE : Decision.ELIGIBLE;
        }
        if (fact.evidenceState() == null || fact.evidenceState() == EvidenceState.UNKNOWN) {
            return Decision.INDETERMINATE;
        }
        if (fact.evidenceState() != EvidenceState.ACCEPTED) {
            return Decision.NOT_ELIGIBLE;
        }
        return fact.acceptedEvidenceRefs() != null && !fact.acceptedEvidenceRefs().isEmpty()
                && fact.acceptedEvidenceRefs().stream().noneMatch(ConfirmEligibilityService::blank)
                ? Decision.ELIGIBLE : Decision.INDETERMINATE;
    }

    private static Decision requiredTrue(Boolean value) {
        return value == null ? Decision.INDETERMINATE
                : value ? Decision.ELIGIBLE : Decision.NOT_ELIGIBLE;
    }

    private static Decision combine(Decision left, Decision right) {
        if (left == Decision.NOT_ELIGIBLE || right == Decision.NOT_ELIGIBLE) return Decision.NOT_ELIGIBLE;
        if (left == Decision.INDETERMINATE || right == Decision.INDETERMINATE) return Decision.INDETERMINATE;
        return Decision.ELIGIBLE;
    }

    private static boolean invalid(Long version) {
        return version == null || version <= 0;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
