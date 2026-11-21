package com.example.retail_loan_document_completeness_checker.services;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.search.enums.UserTaskState;
import io.camunda.client.api.search.response.UserTask;
import io.camunda.client.api.search.response.Variable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Camunda 8.10 Orchestration Cluster API. Empty search is never proof of absence. */
@Component
@ConditionalOnProperty(name = "app.camunda.enabled", havingValue = "true")
public final class Camunda810UserTaskClient {

    public enum LookupState { OPEN, ALREADY_COMPLETED, UNKNOWN, LOOKUP_FAILED, MULTIPLE_MATCHES, WRONG_CASE_OR_VERSION }
    public enum CompletionState { COMPLETED, ALREADY_COMPLETED, WRONG_TASK, UNKNOWN }

    public record Correlation(String applicationId, long dossierVersion, long processInstanceKey,
                              long processDefinitionKey, String processId, String elementId, String tenantId) {
        public Correlation {
            if (applicationId == null || applicationId.isBlank() || dossierVersion <= 0
                    || processInstanceKey <= 0 || processDefinitionKey <= 0
                    || processId == null || processId.isBlank()
                    || elementId == null || elementId.isBlank()
                    || tenantId == null || tenantId.isBlank()) {
                throw new IllegalArgumentException("Complete, positive Camunda correlation is required");
            }
        }
    }

    public record Lookup(LookupState state, Long taskKey) {
        public Lookup {
            Objects.requireNonNull(state);
            if ((state == LookupState.OPEN || state == LookupState.ALREADY_COMPLETED)
                    != (taskKey != null && taskKey > 0)) {
                throw new IllegalArgumentException("Only verified tasks can expose a key");
            }
        }
    }

    private final CamundaClient client;
    private final Duration timeout;

    @Autowired
    public Camunda810UserTaskClient(CamundaClient client) {
        this(client, Duration.ofSeconds(5));
    }

    Camunda810UserTaskClient(CamundaClient client, Duration timeout) {
        this.client = Objects.requireNonNull(client);
        this.timeout = Objects.requireNonNull(timeout);
    }

    public Lookup lookup(Correlation correlation) {
        try {
            List<UserTask> tasks = client.newUserTaskSearchRequest()
                    .filter(filter -> filter.processInstanceKey(correlation.processInstanceKey())
                            .elementId(correlation.elementId()))
                    .page(page -> page.limit(2))
                    .requestTimeout(timeout).send().join().items();
            if (tasks == null || tasks.isEmpty()) return new Lookup(LookupState.UNKNOWN, null);
            if (tasks.size() != 1) return new Lookup(LookupState.MULTIPLE_MATCHES, null);
            Long key = tasks.get(0).getUserTaskKey();
            return key == null || key <= 0 ? new Lookup(LookupState.LOOKUP_FAILED, null) : get(correlation, key);
        } catch (RuntimeException exception) {
            return new Lookup(LookupState.LOOKUP_FAILED, null);
        }
    }

    public Lookup get(Correlation correlation, long taskKey) {
        if (taskKey <= 0) throw new IllegalArgumentException("taskKey must be positive");
        try {
            UserTask task = client.newUserTaskGetRequest(taskKey).requestTimeout(timeout).send().join();
            if (task == null) return new Lookup(LookupState.LOOKUP_FAILED, null);
            if (!Objects.equals(task.getUserTaskKey(), taskKey)
                    || !Objects.equals(task.getProcessInstanceKey(), correlation.processInstanceKey())
                    || !Objects.equals(task.getProcessDefinitionKey(), correlation.processDefinitionKey())
                    || !Objects.equals(task.getBpmnProcessId(), correlation.processId())
                    || !Objects.equals(task.getElementId(), correlation.elementId())
                    || !Objects.equals(task.getTenantId(), correlation.tenantId())
                    || !matchesVariables(correlation)) {
                return new Lookup(LookupState.WRONG_CASE_OR_VERSION, null);
            }
            if (task.getState() == UserTaskState.CREATED) return new Lookup(LookupState.OPEN, taskKey);
            if (task.getState() == UserTaskState.COMPLETED) return new Lookup(LookupState.ALREADY_COMPLETED, taskKey);
            return new Lookup(LookupState.UNKNOWN, null);
        } catch (RuntimeException exception) {
            return new Lookup(LookupState.LOOKUP_FAILED, null);
        }
    }

    public CompletionState complete(Correlation correlation, long taskKey, Map<String, Object> variables) {
        Objects.requireNonNull(variables);
        Lookup current = get(correlation, taskKey);
        if (current.state() == LookupState.ALREADY_COMPLETED) return CompletionState.ALREADY_COMPLETED;
        if (current.state() == LookupState.WRONG_CASE_OR_VERSION) return CompletionState.WRONG_TASK;
        if (current.state() != LookupState.OPEN) return CompletionState.UNKNOWN;
        try {
            client.newCompleteUserTaskCommand(taskKey).variables(Map.copyOf(variables))
                    .requestTimeout(timeout).send().join();
            return CompletionState.COMPLETED;
        } catch (RuntimeException exception) {
            return CompletionState.UNKNOWN;
        }
    }

    /** A completed task alone is insufficient: the operation marker must be visible on its process. */
    public boolean completedBy(Correlation correlation, long taskKey, String operationId) {
        if (get(correlation, taskKey).state() != LookupState.ALREADY_COMPLETED) return false;
        try {
            List<Variable> variables = client.newVariableSearchRequest()
                    .filter(filter -> filter.processInstanceKey(correlation.processInstanceKey())
                            .scopeKey(correlation.processInstanceKey()))
                    .page(page -> page.limit(100)).requestTimeout(timeout).send().join().items();
            if (variables == null) return false;
            List<Variable> markers = variables.stream()
                    .filter(variable -> "confirmOperationId".equals(variable.getName())).toList();
            return markers.size() == 1 && operationId.equals(markers.get(0).getValueAsType(String.class));
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private boolean matchesVariables(Correlation correlation) {
        List<Variable> variables = client.newVariableSearchRequest()
                .filter(filter -> filter.processInstanceKey(correlation.processInstanceKey())
                        .scopeKey(correlation.processInstanceKey()))
                .page(page -> page.limit(100)).requestTimeout(timeout).send().join().items();
        if (variables == null) return false;
        List<Variable> ids = variables.stream().filter(v -> "applicationId".equals(v.getName())).toList();
        List<Variable> versions = variables.stream().filter(v -> "dossierVersion".equals(v.getName())).toList();
        return ids.size() == 1 && versions.size() == 1
                && correlation.applicationId().equals(ids.get(0).getValueAsType(String.class))
                && Objects.equals(correlation.dossierVersion(), versions.get(0).getValueAsType(Long.class));
    }
}
