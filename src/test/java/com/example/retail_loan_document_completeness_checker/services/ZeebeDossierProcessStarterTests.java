package com.example.retail_loan_document_completeness_checker.services;

import com.example.retail_loan_document_completeness_checker.models.WorkflowCorrelation;
import com.example.retail_loan_document_completeness_checker.repositories.WorkflowCorrelationRepository;
import io.camunda.client.CamundaClient;
import io.camunda.client.api.response.ProcessInstanceEvent;
import io.camunda.client.api.search.response.ProcessInstance;
import io.camunda.client.api.search.response.SearchResponse;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ZeebeDossierProcessStarterTests {
    private static final String PROCESS = "retail-loan-document-completeness";
    private static final String ELEMENT = "Activity_03e0s8k";
    private final CamundaClient engine = mock(CamundaClient.class, RETURNS_DEEP_STUBS);
    private final WorkflowCorrelationRepository repository = mock(WorkflowCorrelationRepository.class);
    private final ZeebeDossierProcessStarter starter = new ZeebeDossierProcessStarter(
            engine, repository, PROCESS, ELEMENT);

    @Test
    void storesActualEngineProcessKeysAfterDurableIntent() {
        WorkflowCorrelation intent = intent();
        when(repository.findByApplicationIdAndDossierVersion("APP-1", 3)).thenReturn(Optional.of(intent));
        ProcessInstanceEvent created = mock(ProcessInstanceEvent.class);
        when(created.getProcessInstanceKey()).thenReturn(501L);
        when(created.getProcessDefinitionKey()).thenReturn(601L);
        when(created.getBpmnProcessId()).thenReturn(PROCESS);
        when(created.getVersion()).thenReturn(7);
        when(created.getTenantId()).thenReturn("<default>");
        when(engine.newCreateInstanceCommand().bpmnProcessId(PROCESS).latestVersion()
                .businessId("APP-1:3").variables(anyMap()).send().join()).thenReturn(created);

        starter.start("APP-1", 3);
        assertThat(intent.getState()).isEqualTo(WorkflowCorrelation.State.STARTED);
        assertThat(intent.getProcessInstanceKey()).isEqualTo(501L);
        assertThat(intent.getProcessDefinitionKey()).isEqualTo(601L);
        assertThat(intent.getProcessDefinitionVersion()).isEqualTo(7);
        verify(repository).saveAndFlush(intent);
    }

    @Test
    void missingIntentCannotStartUntrackedProcess() {
        assertThatThrownBy(() -> starter.start("APP-1", 3))
                .isInstanceOf(com.example.retail_loan_document_completeness_checker.exceptions.ProcessStartException.class);
        verify(engine, never()).newCreateInstanceCommand();
    }

    @Test
    @SuppressWarnings("unchecked")
    void reconcilesLostCreateResponseByUniqueBusinessIdWithoutStartingAnotherProcess() {
        WorkflowCorrelation intent = intent();
        when(repository.findByState(WorkflowCorrelation.State.START_PENDING)).thenReturn(List.of(intent));
        ProcessInstance found = mock(ProcessInstance.class);
        when(found.getBusinessId()).thenReturn("APP-1:3");
        when(found.getProcessDefinitionId()).thenReturn(PROCESS);
        when(found.getProcessInstanceKey()).thenReturn(501L);
        when(found.getProcessDefinitionKey()).thenReturn(601L);
        when(found.getProcessDefinitionVersion()).thenReturn(7);
        when(found.getTenantId()).thenReturn("<default>");
        SearchResponse<ProcessInstance> response = mock(SearchResponse.class);
        when(response.items()).thenReturn(List.of(found));
        when(engine.newProcessInstanceSearchRequest().filter(any(java.util.function.Consumer.class))
                .page(any(java.util.function.Consumer.class)).requestTimeout(any()).send().join()).thenReturn(response);

        starter.reconcileStarts();
        assertThat(intent.getState()).isEqualTo(WorkflowCorrelation.State.STARTED);
        assertThat(intent.getProcessInstanceKey()).isEqualTo(501L);
        verify(engine, never()).newCreateInstanceCommand();
    }

    private static WorkflowCorrelation intent() {
        WorkflowCorrelation correlation = new WorkflowCorrelation();
        correlation.setId("synthetic-intent");
        correlation.setApplicationId("APP-1");
        correlation.setDossierVersion(3);
        correlation.setProcessId(PROCESS);
        correlation.setConfirmElementId(ELEMENT);
        correlation.setTenantId("<default>");
        correlation.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        correlation.setState(WorkflowCorrelation.State.START_PENDING);
        return correlation;
    }
}
