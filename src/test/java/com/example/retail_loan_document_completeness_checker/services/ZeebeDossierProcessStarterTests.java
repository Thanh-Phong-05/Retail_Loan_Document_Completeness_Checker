package com.example.retail_loan_document_completeness_checker.services;

import com.example.retail_loan_document_completeness_checker.models.WorkflowCorrelation;
import com.example.retail_loan_document_completeness_checker.repositories.WorkflowCorrelationRepository;
import io.camunda.zeebe.client.ZeebeClient;
import io.camunda.zeebe.client.api.response.ProcessInstanceEvent;
import tools.jackson.databind.json.JsonMapper;
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
    private final ZeebeClient engine = mock(ZeebeClient.class, RETURNS_DEEP_STUBS);
    private final WorkflowCorrelationRepository repository = mock(WorkflowCorrelationRepository.class);
    private final Camunda85QueryClient queries = mock(Camunda85QueryClient.class);
    private final ZeebeDossierProcessStarter starter = new ZeebeDossierProcessStarter(
            engine, queries, repository, PROCESS, ELEMENT);

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
                .variables(anyMap()).send().join()).thenReturn(created);

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
    void reconcilesLostCreateResponseByPersistedIntentWithoutStartingAnotherProcess() {
        WorkflowCorrelation intent = intent();
        when(repository.findByState(WorkflowCorrelation.State.START_PENDING)).thenReturn(List.of(intent));
        var json = JsonMapper.builder().build();
        var found = json.readTree("""
                {"key":501,"processDefinitionKey":601,"processVersion":7,
                 "bpmnProcessId":"retail-loan-document-completeness","tenantId":"<default>"}
                """);
        when(queries.processesForStartIntent("synthetic-intent")).thenReturn(List.of(found));
        when(queries.rootVariable(501, "applicationId", "<default>")).thenReturn(json.readTree("\"APP-1\""));
        when(queries.rootVariable(501, "dossierVersion", "<default>")).thenReturn(json.readTree("3"));

        starter.reconcileStarts();
        assertThat(intent.getState()).isEqualTo(WorkflowCorrelation.State.STARTED);
        assertThat(intent.getProcessInstanceKey()).isEqualTo(501L);
        verify(engine, never()).newCreateInstanceCommand();
    }

    @Test
    void ambiguousStartRemainsInterventionAndIsNeverRecreated() {
        WorkflowCorrelation intent = intent();
        when(repository.findByState(WorkflowCorrelation.State.START_PENDING)).thenReturn(List.of(intent));
        var process = JsonMapper.builder().build().readTree("{\"key\":501}");
        when(queries.processesForStartIntent(intent.getId())).thenReturn(List.of(process, process));
        starter.reconcileStarts();
        assertThat(intent.getState()).isEqualTo(WorkflowCorrelation.State.REQUIRES_INTERVENTION);
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
