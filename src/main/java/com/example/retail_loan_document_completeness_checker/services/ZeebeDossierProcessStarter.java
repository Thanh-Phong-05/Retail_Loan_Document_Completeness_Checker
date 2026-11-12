package com.example.retail_loan_document_completeness_checker.services;

import com.example.retail_loan_document_completeness_checker.exceptions.ProcessStartException;
import io.camunda.zeebe.client.ZeebeClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@ConditionalOnProperty(name = "app.camunda.enabled", havingValue = "true")
public class ZeebeDossierProcessStarter implements DossierProcessStarter {

    private final ZeebeClient zeebeClient;
    private final String processId;

    public ZeebeDossierProcessStarter(
            ZeebeClient zeebeClient,
            @Value("${app.camunda.process-id:retail-loan-document-completeness}") String processId
    ) {
        this.zeebeClient = zeebeClient;
        this.processId = processId;
    }

    @Override
    public void start(String applicationId, int dossierVersion) {
        try {
            zeebeClient.newCreateInstanceCommand()
                    .bpmnProcessId(processId)
                    .latestVersion()
                    .variables(Map.of(
                            "applicationId", applicationId,
                            "dossierVersion", dossierVersion
                    ))
                    .send()
                    .join();
        } catch (RuntimeException exception) {
            throw new ProcessStartException("The dossier was stored but its Camunda process could not be started", exception);
        }
    }
}
