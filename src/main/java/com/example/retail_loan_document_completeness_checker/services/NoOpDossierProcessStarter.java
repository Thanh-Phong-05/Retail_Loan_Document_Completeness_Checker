package com.example.retail_loan_document_completeness_checker.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.camunda.enabled", havingValue = "false", matchIfMissing = true)
public class NoOpDossierProcessStarter implements DossierProcessStarter {

    private static final Logger LOGGER = LoggerFactory.getLogger(NoOpDossierProcessStarter.class);

    @Override
    public void start(String applicationId, int dossierVersion) {
        LOGGER.info("Camunda is disabled; dossier {} version {} is stored without starting a process",
                applicationId, dossierVersion);
    }
}
