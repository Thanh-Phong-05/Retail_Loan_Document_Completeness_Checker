package com.example.retail_loan_document_completeness_checker.services;

import com.example.retail_loan_document_completeness_checker.exceptions.ProcessStartException;
import com.example.retail_loan_document_completeness_checker.models.WorkflowCorrelation;
import com.example.retail_loan_document_completeness_checker.repositories.WorkflowCorrelationRepository;
import io.camunda.client.CamundaClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.Map;
import java.util.Objects;
import java.time.Duration;

@Component
@ConditionalOnProperty(name = "app.camunda.enabled", havingValue = "true")
public class ZeebeDossierProcessStarter implements DossierProcessStarter {

    private final CamundaClient camundaClient;
    private final String processId;
    private final String confirmElementId;
    private final WorkflowCorrelationRepository correlations;

    public ZeebeDossierProcessStarter(
            CamundaClient camundaClient,
            WorkflowCorrelationRepository correlations,
            @Value("${app.camunda.process-id:retail-loan-document-completeness}") String processId,
            @Value("${app.camunda.confirm-element-id:Activity_03e0s8k}") String confirmElementId
    ) {
        this.camundaClient = camundaClient;
        this.correlations = correlations;
        this.processId = processId;
        this.confirmElementId = confirmElementId;
    }

    @Override
    public void start(String applicationId, int dossierVersion) {
        try {
            WorkflowCorrelation correlation = correlations.findByApplicationIdAndDossierVersion(
                    applicationId, dossierVersion).orElseThrow(() ->
                    new IllegalStateException("Durable workflow start intent is missing"));
            if (correlation.getState() != WorkflowCorrelation.State.START_PENDING) {
                throw new IllegalStateException("Workflow start intent is not pending");
            }
            var created = camundaClient.newCreateInstanceCommand()
                    .bpmnProcessId(processId)
                    .latestVersion()
                    .businessId(applicationId + ":" + dossierVersion)
                    .variables(Map.of(
                            "applicationId", applicationId,
                            "dossierVersion", dossierVersion
                    ))
                    .send()
                    .join();
            if (created.getProcessInstanceKey() <= 0 || created.getProcessDefinitionKey() <= 0
                    || !processId.equals(created.getBpmnProcessId())) {
                throw new IllegalStateException("Camunda returned incomplete process correlation");
            }
            correlation.setProcessInstanceKey(created.getProcessInstanceKey());
            correlation.setProcessDefinitionKey(created.getProcessDefinitionKey());
            correlation.setProcessDefinitionVersion(created.getVersion());
            correlation.setProcessId(created.getBpmnProcessId());
            correlation.setTenantId(created.getTenantId() == null ? "<default>" : created.getTenantId());
            correlation.setConfirmElementId(confirmElementId);
            correlation.setState(WorkflowCorrelation.State.STARTED);
            correlations.saveAndFlush(correlation);
        } catch (RuntimeException exception) {
            throw new ProcessStartException("The dossier was stored but its Camunda process could not be started", exception);
        }
    }

    /** Reconcile a lost create response by a unique engine business ID; never blindly create again. */
    @Scheduled(fixedDelayString = "${app.camunda.confirm-recovery-ms:15000}")
    public void reconcileStarts() {
        for (WorkflowCorrelation intent : correlations.findByState(WorkflowCorrelation.State.START_PENDING)) {
            try {
                var matches = camundaClient.newProcessInstanceSearchRequest()
                        .filter(filter -> filter.businessId(intent.getApplicationId() + ":" + intent.getDossierVersion()))
                        .page(page -> page.limit(2)).requestTimeout(Duration.ofSeconds(5)).send().join().items();
                if (matches == null || matches.isEmpty()) continue; // Search may lag the command stream.
                if (matches.size() != 1) {
                    intent.setState(WorkflowCorrelation.State.REQUIRES_INTERVENTION);
                    correlations.saveAndFlush(intent);
                    continue;
                }
                var found = matches.get(0);
                if (!Objects.equals(found.getBusinessId(), intent.getApplicationId() + ":" + intent.getDossierVersion())
                        || !Objects.equals(found.getProcessDefinitionId(), intent.getProcessId())
                        || found.getProcessInstanceKey() == null || found.getProcessInstanceKey() <= 0
                        || found.getProcessDefinitionKey() == null || found.getProcessDefinitionKey() <= 0
                        || found.getProcessDefinitionVersion() == null || found.getProcessDefinitionVersion() <= 0
                        || !Objects.equals(found.getTenantId(), intent.getTenantId())) {
                    intent.setState(WorkflowCorrelation.State.REQUIRES_INTERVENTION);
                } else {
                    intent.setProcessInstanceKey(found.getProcessInstanceKey());
                    intent.setProcessDefinitionKey(found.getProcessDefinitionKey());
                    intent.setProcessDefinitionVersion(found.getProcessDefinitionVersion());
                    intent.setState(WorkflowCorrelation.State.STARTED);
                }
                correlations.saveAndFlush(intent);
            } catch (RuntimeException ignored) { /* Keep the intent for another reconciliation pass. */ }
        }
    }
}
