package com.example.retail_loan_document_completeness_checker.services;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.search.enums.UserTaskState;
import io.camunda.client.api.search.response.SearchResponse;
import io.camunda.client.api.search.response.UserTask;
import io.camunda.client.api.search.response.Variable;
import io.camunda.client.api.search.filter.UserTaskFilter;
import io.camunda.client.api.search.filter.VariableFilter;
import io.camunda.client.api.search.page.AnyPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class Camunda810UserTaskClientTests {
    private static final Camunda810UserTaskClient.Correlation CASE =
            new Camunda810UserTaskClient.Correlation("APP-A", 3, 100, 200,
                    "retail-loan-document-completeness", "Activity_03e0s8k", "<default>");
    private CamundaClient engine;
    private Camunda810UserTaskClient client;

    @BeforeEach
    void setUp() {
        engine = mock(CamundaClient.class, RETURNS_DEEP_STUBS);
        client = new Camunda810UserTaskClient(engine, Duration.ofSeconds(5));
    }

    @Test
    void findsOnlyTheTaskBoundToExpectedProcessCaseAndVersion() {
        search(List.of(task(UserTaskState.CREATED, 100, 200, "Activity_03e0s8k", "<default>")));
        get(task(UserTaskState.CREATED, 100, 200, "Activity_03e0s8k", "<default>"));
        variables("APP-A", 3);
        assertThat(client.lookup(CASE)).isEqualTo(
                new Camunda810UserTaskClient.Lookup(Camunda810UserTaskClient.LookupState.OPEN, 300L));
    }

    @Test
    void emptyEventuallyConsistentSearchDoesNotProveAbsence() {
        search(List.of());
        assertThat(client.lookup(CASE).state()).isEqualTo(Camunda810UserTaskClient.LookupState.UNKNOWN);
    }

    @Test
    void multipleMatchingTasksAreNotArbitrarilySelected() {
        UserTask second = task(UserTaskState.CREATED, 100, 200, "Activity_03e0s8k", "<default>");
        when(second.getUserTaskKey()).thenReturn(301L);
        search(List.of(task(UserTaskState.CREATED, 100, 200, "Activity_03e0s8k", "<default>"), second));
        assertThat(client.lookup(CASE).state()).isEqualTo(Camunda810UserTaskClient.LookupState.MULTIPLE_MATCHES);
    }

    @Test
    void wrongCaseAndVersionAreRejectedBeforeCompletion() {
        get(task(UserTaskState.CREATED, 100, 200, "Activity_03e0s8k", "<default>"));
        variables("APP-B", 3);
        assertThat(client.complete(CASE, 300, Map.of())).isEqualTo(Camunda810UserTaskClient.CompletionState.WRONG_TASK);
        variables("APP-A", 2);
        assertThat(client.complete(CASE, 300, Map.of())).isEqualTo(Camunda810UserTaskClient.CompletionState.WRONG_TASK);
        verify(engine, org.mockito.Mockito.never()).newCompleteUserTaskCommand(300);
    }

    @Test
    void wrongTenantProcessOrElementIsRejected() {
        get(task(UserTaskState.CREATED, 100, 200, "Activity_03e0s8k", "other"));
        assertThat(client.get(CASE, 300).state()).isEqualTo(Camunda810UserTaskClient.LookupState.WRONG_CASE_OR_VERSION);
        get(task(UserTaskState.CREATED, 100, 201, "Activity_03e0s8k", "<default>"));
        assertThat(client.get(CASE, 300).state()).isEqualTo(Camunda810UserTaskClient.LookupState.WRONG_CASE_OR_VERSION);
        get(task(UserTaskState.CREATED, 100, 200, "other", "<default>"));
        assertThat(client.get(CASE, 300).state()).isEqualTo(Camunda810UserTaskClient.LookupState.WRONG_CASE_OR_VERSION);
    }

    @Test
    void nativeUserTaskCompletionUsesVerifiedKeyAndBoundedTimeout() {
        get(task(UserTaskState.CREATED, 100, 200, "Activity_03e0s8k", "<default>"));
        variables("APP-A", 3);
        assertThat(client.complete(CASE, 300, Map.of("handoffDecision", "CONFIRMED")))
                .isEqualTo(Camunda810UserTaskClient.CompletionState.COMPLETED);
        verify(engine, org.mockito.Mockito.atLeastOnce()).newCompleteUserTaskCommand(300);
    }

    @Test
    void completedTaskIsRecognizedWithoutRepeatingEngineSideEffect() {
        get(task(UserTaskState.COMPLETED, 100, 200, "Activity_03e0s8k", "<default>"));
        variables("APP-A", 3);
        assertThat(client.complete(CASE, 300, Map.of()))
                .isEqualTo(Camunda810UserTaskClient.CompletionState.ALREADY_COMPLETED);
        verify(engine, org.mockito.Mockito.never()).newCompleteUserTaskCommand(300);
    }

    @Test
    void timeoutAfterPossibleEngineSuccessRemainsUnknownAndIsNotRetried() {
        get(task(UserTaskState.CREATED, 100, 200, "Activity_03e0s8k", "<default>"));
        variables("APP-A", 3);
        when(engine.newCompleteUserTaskCommand(300).variables(anyMap())
                .requestTimeout(any()).send().join()).thenThrow(new IllegalStateException("lost response"));
        assertThat(client.complete(CASE, 300, Map.of())).isEqualTo(Camunda810UserTaskClient.CompletionState.UNKNOWN);
        verify(engine, org.mockito.Mockito.atLeastOnce()).newCompleteUserTaskCommand(300);
    }

    @Test
    void engineOutageFailsClosedAndOperationMarkerIsRequiredForReconciliation() {
        when(engine.newUserTaskSearchRequest().filter((Consumer<UserTaskFilter>) any())
                .page((Consumer<AnyPage>) any())
                .requestTimeout(any()).send().join()).thenThrow(new IllegalStateException("lookup outage"));
        assertThat(client.lookup(CASE).state()).isEqualTo(Camunda810UserTaskClient.LookupState.LOOKUP_FAILED);
        get(task(UserTaskState.COMPLETED, 100, 200, "Activity_03e0s8k", "<default>"));
        variables("APP-A", 3);
        assertThat(client.completedBy(CASE, 300, "OP-1")).isFalse();
        operationMarker("OP-1");
        assertThat(client.completedBy(CASE, 300, "OP-1")).isTrue();
    }

    @SuppressWarnings("unchecked")
    private void search(List<UserTask> tasks) {
        SearchResponse<UserTask> response = mock(SearchResponse.class);
        when(response.items()).thenReturn(tasks);
        when(engine.newUserTaskSearchRequest().filter((Consumer<UserTaskFilter>) any())
                .page((Consumer<AnyPage>) any())
                .requestTimeout(any()).send().join()).thenReturn(response);
    }

    private void get(UserTask task) {
        when(engine.newUserTaskGetRequest(300).requestTimeout(any()).send().join()).thenReturn(task);
    }

    @SuppressWarnings("unchecked")
    private void variables(String applicationId, long version) {
        Variable id = mock(Variable.class);
        when(id.getName()).thenReturn("applicationId");
        when(id.getValueAsType(String.class)).thenReturn(applicationId);
        Variable number = mock(Variable.class);
        when(number.getName()).thenReturn("dossierVersion");
        when(number.getValueAsType(Long.class)).thenReturn(version);
        SearchResponse<Variable> response = mock(SearchResponse.class);
        when(response.items()).thenReturn(List.of(id, number));
        when(engine.newVariableSearchRequest().filter((Consumer<VariableFilter>) any())
                .page((Consumer<AnyPage>) any())
                .requestTimeout(any()).send().join()).thenReturn(response);
    }

    @SuppressWarnings("unchecked")
    private void operationMarker(String operationId) {
        Variable id = mock(Variable.class);
        when(id.getName()).thenReturn("applicationId");
        when(id.getValueAsType(String.class)).thenReturn("APP-A");
        Variable number = mock(Variable.class);
        when(number.getName()).thenReturn("dossierVersion");
        when(number.getValueAsType(Long.class)).thenReturn(3L);
        Variable marker = mock(Variable.class);
        when(marker.getName()).thenReturn("confirmOperationId");
        when(marker.getValueAsType(String.class)).thenReturn(operationId);
        SearchResponse<Variable> response = mock(SearchResponse.class);
        when(response.items()).thenReturn(List.of(id, number, marker));
        when(engine.newVariableSearchRequest().filter((Consumer<VariableFilter>) any())
                .page((Consumer<AnyPage>) any())
                .requestTimeout(any()).send().join()).thenReturn(response);
    }

    private static UserTask task(UserTaskState state, long process, long definition,
                                 String element, String tenant) {
        UserTask task = mock(UserTask.class);
        when(task.getUserTaskKey()).thenReturn(300L);
        when(task.getProcessInstanceKey()).thenReturn(process);
        when(task.getProcessDefinitionKey()).thenReturn(definition);
        when(task.getBpmnProcessId()).thenReturn("retail-loan-document-completeness");
        when(task.getElementId()).thenReturn(element);
        when(task.getTenantId()).thenReturn(tenant);
        when(task.getState()).thenReturn(state);
        return task;
    }
}
