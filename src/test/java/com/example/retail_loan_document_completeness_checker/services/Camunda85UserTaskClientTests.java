package com.example.retail_loan_document_completeness_checker.services;

import com.sun.net.httpserver.HttpServer;
import io.camunda.common.auth.Authentication;
import io.camunda.zeebe.client.ZeebeClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class Camunda85UserTaskClientTests {
    private static final Camunda85UserTaskClient.Correlation CASE = new Camunda85UserTaskClient.Correlation(
            "APP-A", 3, 100, 200, "retail-loan-document-completeness", "Activity_0462f5z", "<default>");
    private final JsonMapper json = JsonMapper.builder().build();
    private final ZeebeClient engine = mock(ZeebeClient.class, RETURNS_DEEP_STUBS);
    private final Authentication authentication = mock(Authentication.class);
    private HttpServer server;
    private Camunda85UserTaskClient client;
    private Camunda85QueryClient queries;
    private String taskState = "CREATED", app = "APP-A", tenant = "<default>", element = "Activity_0462f5z";
    private String marker = "operation-a";
    private long version = 3, definition = 200;
    private int taskCount = 1, variableCount = 1, responseStatus = 200;
    private boolean truncated;
    private final List<JsonNode> variableQueries = new ArrayList<>();

    @BeforeEach void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Object response;
            if (path.equals("/v1/tasks/search")) {
                response = taskCount == 0 ? List.of() : taskCount == 1 ? List.of(task()) : List.of(task(), task());
            } else if (path.startsWith("/v1/tasks/")) {
                response = task();
            } else if (path.startsWith("/v1/process-instances/")) {
                response = Map.of("key", 100, "processDefinitionKey", 200, "processVersion", 1,
                        "bpmnProcessId", CASE.processId(), "tenantId", "<default>");
            } else {
                JsonNode filter = json.readTree(body).path("filter");
                variableQueries.add(filter);
                String name = filter.path("name").asString();
                Object value = switch (name) {
                    case "applicationId" -> app;
                    case "dossierVersion" -> version;
                    case "confirmOperationId" -> marker;
                    default -> "intent-a";
                };
                var variable = Map.of("name", name, "value", json.writeValueAsString(value),
                        "scopeKey", 100, "processInstanceKey", 100, "tenantId", "<default>", "truncated", truncated);
                response = Map.of("total", variableCount, "items",
                        variableCount == 0 ? List.of() : variableCount == 1 ? List.of(variable) : List.of(variable, variable));
            }
            byte[] bytes = json.writeValueAsBytes(response);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort();
        queries = new Camunda85QueryClient(authentication, url, url);
        client = new Camunda85UserTaskClient(engine, queries);
    }

    @AfterEach void tearDown() { server.stop(0); }

    @Test void findsOnlyTheTaskBoundToExpectedProcessCaseAndVersion() {
        assertThat(client.lookup(CASE)).isEqualTo(new Camunda85UserTaskClient.Lookup(Camunda85UserTaskClient.LookupState.OPEN, 300L));
        assertThat(variableQueries).hasSize(2).allSatisfy(filter -> {
            assertThat(filter.path("scopeKey").asLong()).isEqualTo(100);
            assertThat(filter.path("processInstanceKey").asLong()).isEqualTo(100);
        });
    }

    @Test void emptyEventuallyConsistentSearchDoesNotProveAbsence() {
        taskCount = 0;
        assertThat(client.lookup(CASE).state()).isEqualTo(Camunda85UserTaskClient.LookupState.UNKNOWN);
    }

    @Test void multipleMatchingTasksAreNotArbitrarilySelected() {
        taskCount = 2;
        assertThat(client.lookup(CASE).state()).isEqualTo(Camunda85UserTaskClient.LookupState.MULTIPLE_MATCHES);
    }

    @Test void wrongCaseAndVersionAreRejectedBeforeCompletion() {
        app = "APP-B";
        assertThat(client.complete(CASE, 300, Map.of())).isEqualTo(Camunda85UserTaskClient.CompletionState.WRONG_TASK);
        app = "APP-A"; version = 2;
        assertThat(client.complete(CASE, 300, Map.of())).isEqualTo(Camunda85UserTaskClient.CompletionState.WRONG_TASK);
        verifyNoInteractions(engine);
    }

    @Test void wrongTenantProcessOrElementIsRejected() {
        tenant = "other";
        assertThat(client.get(CASE, 300).state()).isEqualTo(Camunda85UserTaskClient.LookupState.WRONG_CASE_OR_VERSION);
        tenant = "<default>"; definition = 201;
        assertThat(client.get(CASE, 300).state()).isEqualTo(Camunda85UserTaskClient.LookupState.WRONG_CASE_OR_VERSION);
        definition = 200; element = "other";
        assertThat(client.get(CASE, 300).state()).isEqualTo(Camunda85UserTaskClient.LookupState.WRONG_CASE_OR_VERSION);
    }

    @Test void nativeUserTaskCompletionUsesVerifiedKeyAndBoundedTimeout() {
        assertThat(client.complete(CASE, 300, Map.of("confirmOperationId", marker)))
                .isEqualTo(Camunda85UserTaskClient.CompletionState.COMPLETED);
        verify(engine.newUserTaskCompleteCommand(300).variables(Map.of("confirmOperationId", marker)))
                .requestTimeout(java.time.Duration.ofSeconds(5));
    }

    @Test void completedTaskIsRecognizedWithoutRepeatingEngineSideEffect() {
        taskState = "COMPLETED";
        assertThat(client.complete(CASE, 300, Map.of())).isEqualTo(Camunda85UserTaskClient.CompletionState.ALREADY_COMPLETED);
        verifyNoInteractions(engine);
    }

    @Test void timeoutAfterPossibleEngineSuccessRemainsUnknownAndIsNotRetried() {
        when(engine.newUserTaskCompleteCommand(300).variables(anyMap()).requestTimeout(any()).send().join())
                .thenThrow(new IllegalStateException("lost response"));
        assertThat(client.complete(CASE, 300, Map.of())).isEqualTo(Camunda85UserTaskClient.CompletionState.UNKNOWN);
    }

    @Test void engineOutageFailsClosedAndOperationMarkerIsRequiredForReconciliation() {
        responseStatus = 503;
        assertThat(client.lookup(CASE).state()).isEqualTo(Camunda85UserTaskClient.LookupState.LOOKUP_FAILED);
        responseStatus = 200; taskState = "COMPLETED";
        assertThat(client.completedBy(CASE, 300, "other-operation")).isFalse();
        assertThat(client.completedBy(CASE, 300, marker)).isTrue();
    }

    @Test void duplicateMissingAndTruncatedRootVariablesCannotAuthorizeCompletion() {
        variableCount = 2;
        assertThat(client.complete(CASE, 300, Map.of())).isEqualTo(Camunda85UserTaskClient.CompletionState.UNKNOWN);
        variableCount = 0;
        assertThat(client.complete(CASE, 300, Map.of())).isEqualTo(Camunda85UserTaskClient.CompletionState.UNKNOWN);
        variableCount = 1; truncated = true;
        assertThat(client.complete(CASE, 300, Map.of())).isEqualTo(Camunda85UserTaskClient.CompletionState.UNKNOWN);
        verifyNoInteractions(engine);
    }

    @Test void unauthorizedQueriesResetCredentialsButNeverCompleteTasks() {
        responseStatus = 401;
        assertThat(client.lookup(CASE).state()).isEqualTo(Camunda85UserTaskClient.LookupState.LOOKUP_FAILED);
        verify(authentication).resetToken(io.camunda.common.auth.Product.TASKLIST);
        verifyNoInteractions(engine);
    }

    @Test void startRecoverySearchUsesPersistedIntentAndChecksRootScope() {
        assertThat(queries.processesForStartIntent("intent-a")).hasSize(1);
        assertThat(variableQueries.get(0).path("value").asString()).isEqualTo("\"intent-a\"");
        truncated = true;
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> queries.processesForStartIntent("intent-a"))
                .isInstanceOf(IllegalStateException.class);
    }

    private Map<String, Object> task() {
        return Map.of("id", "300", "processInstanceKey", "100", "processDefinitionKey", Long.toString(definition),
                "taskDefinitionId", element, "tenantId", tenant, "taskState", taskState);
    }
}
