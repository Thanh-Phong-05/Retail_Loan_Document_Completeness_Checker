package com.example.retail_loan_document_completeness_checker.services;

import io.camunda.common.auth.Authentication;
import io.camunda.common.auth.Product;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Camunda 8.5 read APIs: Tasklist for tasks, Operate for process/root-variable evidence. */
@Component
@ConditionalOnProperty(name = "app.camunda.enabled", havingValue = "true")
public class Camunda85QueryClient {
    private final Authentication authentication;
    private final RestClient tasklist;
    private final RestClient operate;
    private final JsonMapper json = JsonMapper.builder().build();

    public Camunda85QueryClient(Authentication authentication,
            @Value("${camunda.client.tasklist.base-url}") String tasklistUrl,
            @Value("${camunda.client.operate.base-url}") String operateUrl) {
        this.authentication = authentication;
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.tasklist = RestClient.builder().baseUrl(tasklistUrl).requestFactory(factory).build();
        this.operate = RestClient.builder().baseUrl(operateUrl).requestFactory(factory).build();
    }

    public JsonNode tasks(long processKey, String elementId) {
        return request(Product.TASKLIST, "/v1/tasks/search", Map.of(
                "processInstanceKey", Long.toString(processKey), "taskDefinitionId", elementId, "pageSize", 2));
    }

    public JsonNode task(long key) { return request(Product.TASKLIST, "/v1/tasks/" + key, null); }
    public JsonNode process(long key) { return request(Product.OPERATE, "/v1/process-instances/" + key, null); }

    /** Query by name AND root scope; reject duplicates, truncated values and incomplete results. */
    public JsonNode rootVariable(long processKey, String name, String tenant) {
        JsonNode result = request(Product.OPERATE, "/v1/variables/search", Map.of("size", 2,
                "filter", Map.of("processInstanceKey", processKey, "scopeKey", processKey, "name", name)));
        JsonNode items = result.path("items");
        if (result.path("total").asLong(-1) != 1 || !items.isArray() || items.size() != 1) {
            throw new IllegalStateException("Root variable is not uniquely visible");
        }
        JsonNode variable = items.get(0);
        if (variable.path("processInstanceKey").asLong() != processKey
                || variable.path("scopeKey").asLong() != processKey
                || !name.equals(variable.path("name").asString())
                || !tenant.equals(variable.path("tenantId").asString())
                || variable.path("truncated").asBoolean(false) || !variable.path("value").isString()) {
            throw new IllegalStateException("Root variable correlation is invalid");
        }
        return json.readTree(variable.path("value").asString());
    }

    public List<JsonNode> processesForStartIntent(String intentId) {
        JsonNode result = request(Product.OPERATE, "/v1/variables/search", Map.of("size", 2,
                "filter", Map.of("name", "workflowStartIntentId", "value", json.writeValueAsString(intentId))));
        JsonNode items = result.path("items");
        long total = result.path("total").asLong(-1);
        if (!items.isArray() || total < 0 || total != items.size()) {
            throw new IllegalStateException("Start-intent query is incomplete or ambiguous");
        }
        List<JsonNode> processes = new ArrayList<>();
        for (JsonNode item : items) {
            long key = item.path("processInstanceKey").asLong();
            if (key <= 0 || item.path("scopeKey").asLong() != key
                    || item.path("truncated").asBoolean(false)
                    || !"workflowStartIntentId".equals(item.path("name").asString())
                    || !intentId.equals(json.readTree(item.path("value").asString()).asString())) {
                throw new IllegalStateException("Start-intent variable is not bound to its root process");
            }
            processes.add(process(key));
        }
        return List.copyOf(processes);
    }

    private JsonNode request(Product product, String path, Object body) {
        RestClient client = product == Product.TASKLIST ? tasklist : operate;
        try {
            var header = authentication.getTokenHeader(product);
            var request = client.method(body == null ? HttpMethod.GET : HttpMethod.POST).uri(path)
                    .accept(MediaType.APPLICATION_JSON);
            if (header != null) request.header(header.getKey(), header.getValue());
            if (body != null) request.contentType(MediaType.APPLICATION_JSON).body(json.writeValueAsString(body));
            String response = request.retrieve().body(String.class);
            if (response == null) throw new IllegalStateException("Empty Camunda query response");
            return json.readTree(response);
        } catch (RestClientResponseException failure) {
            if (failure.getStatusCode().value() == 401) authentication.resetToken(product);
            // Never expose response bodies, cookies or credentials in a recoverable operation error.
            throw new IllegalStateException("Camunda query rejected: HTTP " + failure.getStatusCode().value());
        }
    }
}
