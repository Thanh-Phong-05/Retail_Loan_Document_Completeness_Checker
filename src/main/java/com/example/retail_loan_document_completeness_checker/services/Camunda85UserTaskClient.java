package com.example.retail_loan_document_completeness_checker.services;

import io.camunda.zeebe.client.ZeebeClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/** Camunda 8.5 native User Tasks. Empty search is never proof of absence. */
@Component
@ConditionalOnProperty(name = "app.camunda.enabled", havingValue = "true")
public final class Camunda85UserTaskClient {

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

    private final ZeebeClient client;
    private final Camunda85QueryClient queries;
    private final Duration timeout;

    @Autowired
    public Camunda85UserTaskClient(ZeebeClient client, Camunda85QueryClient queries) {
        this(client, queries, Duration.ofSeconds(5));
    }

    Camunda85UserTaskClient(ZeebeClient client, Camunda85QueryClient queries, Duration timeout) {
        this.client = Objects.requireNonNull(client);
        this.queries = Objects.requireNonNull(queries);
        this.timeout = Objects.requireNonNull(timeout);
    }

    public Lookup lookup(Correlation correlation) {
        try {
            var tasks = queries.tasks(correlation.processInstanceKey(), correlation.elementId());
            if (tasks == null || !tasks.isArray()) return new Lookup(LookupState.LOOKUP_FAILED, null);
            if (tasks.isEmpty()) return new Lookup(LookupState.UNKNOWN, null);
            if (tasks.size() != 1) return new Lookup(LookupState.MULTIPLE_MATCHES, null);
            long key = tasks.get(0).path("id").asLong();
            return key <= 0 ? new Lookup(LookupState.LOOKUP_FAILED, null) : get(correlation, key);
        } catch (RuntimeException exception) {
            return new Lookup(LookupState.LOOKUP_FAILED, null);
        }
    }

    public Lookup get(Correlation correlation, long taskKey) {
        if (taskKey <= 0) throw new IllegalArgumentException("taskKey must be positive");
        try {
            var task = queries.task(taskKey);
            if (task == null) return new Lookup(LookupState.LOOKUP_FAILED, null);
            var process = queries.process(correlation.processInstanceKey());
            if (task.path("id").asLong() != taskKey
                    || task.path("processInstanceKey").asLong() != correlation.processInstanceKey()
                    || task.path("processDefinitionKey").asLong() != correlation.processDefinitionKey()
                    || !correlation.elementId().equals(task.path("taskDefinitionId").asString())
                    || !correlation.tenantId().equals(task.path("tenantId").asString())
                    || process.path("key").asLong() != correlation.processInstanceKey()
                    || process.path("processDefinitionKey").asLong() != correlation.processDefinitionKey()
                    || !correlation.processId().equals(process.path("bpmnProcessId").asString())
                    || !correlation.tenantId().equals(process.path("tenantId").asString())
                    || !matchesVariables(correlation)) {
                return new Lookup(LookupState.WRONG_CASE_OR_VERSION, null);
            }
            if ("CREATED".equals(task.path("taskState").asString())) return new Lookup(LookupState.OPEN, taskKey);
            if ("COMPLETED".equals(task.path("taskState").asString())) return new Lookup(LookupState.ALREADY_COMPLETED, taskKey);
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
            client.newUserTaskCompleteCommand(taskKey).variables(Map.copyOf(variables))
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
            var marker = queries.rootVariable(correlation.processInstanceKey(), "confirmOperationId", correlation.tenantId());
            return marker.isString() && operationId.equals(marker.asString());
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private boolean matchesVariables(Correlation correlation) {
        var id = queries.rootVariable(correlation.processInstanceKey(), "applicationId", correlation.tenantId());
        var version = queries.rootVariable(correlation.processInstanceKey(), "dossierVersion", correlation.tenantId());
        return id.isString() && correlation.applicationId().equals(id.asString())
                && version.isIntegralNumber() && version.canConvertToLong()
                && correlation.dossierVersion() == version.asLong();
    }
}
