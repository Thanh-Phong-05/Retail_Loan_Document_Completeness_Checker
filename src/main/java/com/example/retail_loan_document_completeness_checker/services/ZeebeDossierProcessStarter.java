package com.example.retail_loan_document_completeness_checker.services;

import com.example.retail_loan_document_completeness_checker.exceptions.ProcessStartException;
import com.example.retail_loan_document_completeness_checker.models.WorkflowCorrelation;
import com.example.retail_loan_document_completeness_checker.repositories.WorkflowCorrelationRepository;
import io.camunda.zeebe.client.ZeebeClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.Map;
import java.util.Objects;

@Component
@ConditionalOnProperty(name = "app.camunda.enabled", havingValue = "true")
public class ZeebeDossierProcessStarter implements DossierProcessStarter {

    private final ZeebeClient camundaClient;
    private final Camunda85QueryClient queries;
    private final String processId;
    private final String confirmElementId;
    private final WorkflowCorrelationRepository correlations;

    public ZeebeDossierProcessStarter(
            ZeebeClient camundaClient,
            Camunda85QueryClient queries,
            WorkflowCorrelationRepository correlations,
            @Value("${app.camunda.process-id:retail-loan-document-completeness}") String processId,
            @Value("${app.camunda.confirm-element-id:Activity_0462f5z}") String confirmElementId
    ) {
        this.camundaClient = camundaClient;
        this.queries = queries;
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
                    .variables(Map.of(
                            "workflowStartIntentId", correlation.getId(),
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

    /** Reconcile a lost create response by the persisted start-intent marker; never blindly create again. */
    @Scheduled(fixedDelayString = "${app.camunda.confirm-recovery-ms:15000}")
    public void reconcileStarts() {
        for (WorkflowCorrelation intent : correlations.findByState(WorkflowCorrelation.State.START_PENDING)) {
            try {
                var matches = queries.processesForStartIntent(intent.getId());
                if (matches == null || matches.isEmpty()) continue; // Search may lag the command stream.
                if (matches.size() != 1) {
                    intent.setState(WorkflowCorrelation.State.REQUIRES_INTERVENTION);
                    correlations.saveAndFlush(intent);
                    continue;
                }
                var found = matches.get(0);
                long key = found.path("key").asLong();
                if (!Objects.equals(found.path("bpmnProcessId").asString(), intent.getProcessId())
                        || key <= 0 || found.path("processDefinitionKey").asLong() <= 0
                        || found.path("processVersion").asInt() <= 0
                        || !Objects.equals(found.path("tenantId").asString(), intent.getTenantId())
                        || !intent.getApplicationId().equals(queries.rootVariable(key, "applicationId", intent.getTenantId()).asString())
                        || queries.rootVariable(key, "dossierVersion", intent.getTenantId()).asLong(-1) != intent.getDossierVersion()) {
                    intent.setState(WorkflowCorrelation.State.REQUIRES_INTERVENTION);
                } else {
                    intent.setProcessInstanceKey(key);
                    intent.setProcessDefinitionKey(found.path("processDefinitionKey").asLong());
                    intent.setProcessDefinitionVersion(found.path("processVersion").asInt());
                    intent.setState(WorkflowCorrelation.State.STARTED);
                }
                correlations.saveAndFlush(intent);
            } catch (RuntimeException ignored) { /* Keep the intent for another reconciliation pass. */ }
        }
    }
}
