package com.example.retail_loan_document_completeness_checker.services;

import com.example.retail_loan_document_completeness_checker.dto.UploadDossierRequest;
import com.example.retail_loan_document_completeness_checker.models.CompletenessStatus;
import com.example.retail_loan_document_completeness_checker.models.ConfirmOperation;
import com.example.retail_loan_document_completeness_checker.models.WorkflowCorrelation;
import com.example.retail_loan_document_completeness_checker.repositories.ApplicationRepository;
import com.example.retail_loan_document_completeness_checker.repositories.AuditLogRepository;
import com.example.retail_loan_document_completeness_checker.repositories.ConfirmOperationRepository;
import com.example.retail_loan_document_completeness_checker.repositories.WorkflowCorrelationRepository;
import com.example.retail_loan_document_completeness_checker.security.ConfirmCaseScope;
import io.camunda.zeebe.client.ZeebeClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Opt in with -Dtest=DurableConfirmCamundaIT against an isolated Camunda 8.5.0 cluster.
 * Surefire's default *Tests pattern does not select this *IT class during mvn test.
 * No Camunda component is mocked. Authentication and review facts are synthetic test-only fixtures.
 */
@SpringBootTest(properties = {
        "app.camunda.enabled=true",
        "app.camunda.process-id=retail-loan-confirm-02q-it",
        "app.camunda.confirm-element-id=Activity_03e0s8k",
        "app.camunda.confirm-recovery-ms=600000",
        "camunda.client.zeebe.gateway-url=${CAMUNDA_IT_GRPC_ADDRESS:http://127.0.0.1:27500}",
        "camunda.client.zeebe.base-url=${CAMUNDA_IT_REST_ADDRESS:http://127.0.0.1:18088}",
        "camunda.client.operate.base-url=${CAMUNDA_IT_OPERATE_ADDRESS:http://127.0.0.1:18091}",
        "camunda.client.tasklist.base-url=${CAMUNDA_IT_TASKLIST_ADDRESS:http://127.0.0.1:18092}",
        "spring.datasource.url=jdbc:h2:mem:durable-confirm-camunda-it;MODE=MSSQLServer;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DurableConfirmCamundaIT {
    private static final String PROCESS_ID = "retail-loan-confirm-02q-it";
    private static final String TASK_ID = "Activity_03e0s8k";
    private static final Duration API_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration INDEX_TIMEOUT = Duration.ofSeconds(60);

    @Autowired ZeebeClient engine;
    @Autowired Camunda85QueryClient queries;
    @Autowired Camunda85UserTaskClient taskClient;
    @Autowired ZeebeDossierProcessStarter processStarter;
    @Autowired DossierPersistenceService persistence;
    @Autowired DurableConfirmCoordinator coordinator;
    @Autowired ApplicationRepository applications;
    @Autowired WorkflowCorrelationRepository correlations;
    @Autowired ConfirmOperationRepository operations;
    @Autowired AuditLogRepository audits;
    @Autowired MockMvc mvc;
    @MockitoBean ConfirmCaseScope caseScope;
    @MockitoBean ConfirmReviewFactsSource reviewFacts;

    @Test
    void realNativeUserTaskCompletesOnceAndFinalizesOneAudit() throws Exception {
        assertThat(System.getenv("CAMUNDA_IT_ISOLATED"))
                .as("Set CAMUNDA_IT_ISOLATED=true only for a separately owned Camunda 8.5.0 test cluster")
                .isEqualTo("true");
        var topology = engine.newTopologyRequest().requestTimeout(API_TIMEOUT).send().join();
        assertThat(topology.getGatewayVersion()).isEqualTo("8.5.0");
        assertThat(topology.getBrokers()).isNotEmpty()
                .allSatisfy(broker -> assertThat(broker.getVersion()).isEqualTo("8.5.0"));

        var deployment = engine.newDeployResourceCommand()
                .addResourceFromClasspath("workflow/confirm-02q-camunda-it.bpmn")
                .requestTimeout(API_TIMEOUT).send().join();
        var definition = deployment.getProcesses().stream()
                .filter(process -> PROCESS_ID.equals(process.getBpmnProcessId()))
                .reduce((first, second) -> { throw new AssertionError("Duplicate IT process definition"); })
                .orElseThrow(() -> new AssertionError("IT process was not deployed"));
        assertThat(definition.getProcessDefinitionKey()).isPositive();
        assertThat(definition.getVersion()).isPositive();

        String applicationId = "APP-IT-" + UUID.randomUUID().toString().substring(0, 8);
        String fileId = UUID.randomUUID().toString();
        persistence.saveUpload(new UploadDossierRequest(applicationId, "SYNTHETIC_PRODUCT", "Synthetic Applicant"),
                List.of(new StoredFile(fileId, "synthetic.pdf", "synthetic.pdf",
                        Path.of("synthetic-tests", "synthetic.pdf"), "application/pdf", 100, "a".repeat(64))));
        WorkflowCorrelation intent = correlations.findByApplicationIdAndDossierVersion(applicationId, 1).orElseThrow();
        assertThat(intent.getState()).isEqualTo(WorkflowCorrelation.State.START_PENDING);
        assertThat(intent.getProcessInstanceKey()).isNull();
        assertThat(intent.getProcessId()).isEqualTo(PROCESS_ID);
        assertThat(intent.getConfirmElementId()).isEqualTo(TASK_ID);

        processStarter.start(applicationId, 1);
        WorkflowCorrelation started = correlations.findByApplicationIdAndDossierVersion(applicationId, 1).orElseThrow();
        assertThat(started.getState()).isEqualTo(WorkflowCorrelation.State.STARTED);
        assertThat(started.getProcessInstanceKey()).isPositive();
        assertThat(started.getProcessDefinitionKey()).isEqualTo(definition.getProcessDefinitionKey());
        assertThat(started.getProcessDefinitionVersion()).isEqualTo(definition.getVersion());

        var expected = new Camunda85UserTaskClient.Correlation(applicationId, 1,
                started.getProcessInstanceKey(), started.getProcessDefinitionKey(),
                started.getProcessId(), started.getConfirmElementId(), started.getTenantId());
        var open = await("OPEN native User Task", INDEX_TIMEOUT, () -> taskClient.lookup(expected),
                lookup -> lookup.state() == Camunda85UserTaskClient.LookupState.OPEN);
        var task = queries.task(open.taskKey());
        assertThat(task.path("taskState").asString()).isEqualTo("CREATED");
        assertThat(task.path("processInstanceKey").asLong()).isEqualTo(started.getProcessInstanceKey());
        assertThat(task.path("processDefinitionKey").asLong()).isEqualTo(started.getProcessDefinitionKey());
        assertThat(task.path("taskDefinitionId").asString()).isEqualTo(TASK_ID);
        assertThat(task.path("tenantId").asString()).isEqualTo(started.getTenantId());
        var process = queries.process(started.getProcessInstanceKey());
        assertThat(process.path("bpmnProcessId").asString()).isEqualTo(PROCESS_ID);
        assertThat(process.path("processVersion").asInt()).isEqualTo(started.getProcessDefinitionVersion());

        var recoveredProcesses = await("unique persisted start-intent marker", INDEX_TIMEOUT,
                () -> queries.processesForStartIntent(started.getId()), matches -> matches.size() == 1);
        assertThat(recoveredProcesses.get(0).path("key").asLong()).isEqualTo(started.getProcessInstanceKey());

        given(caseScope.mayConfirm(any(), eq(applicationId))).willAnswer(invocation ->
                "reviewer-it".equals(((org.springframework.security.core.Authentication)
                        invocation.getArgument(0)).getName()));
        given(reviewFacts.read(applicationId, 1)).willReturn(acceptedFacts(applicationId, fileId));
        assertThat(applications.findById(applicationId).orElseThrow().getCompletenessStatus())
                .isEqualTo(CompletenessStatus.REVIEW_NEEDED);
        assertThat(audits.countByApplicationApplicationIdAndAction(applicationId, "DOSSIER_CONFIRMED")).isZero();

        mvc.perform(confirm(applicationId, 2)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_DOSSIER_VERSION"));
        assertThat(operations.findByApplicationIdAndDossierVersion(applicationId, 1)).isEmpty();

        var response = mvc.perform(confirm(applicationId, 1)).andReturn().getResponse();
        assertThat(response.getStatus()).isIn(200, 202);
        ConfirmOperation admitted = operations.findByApplicationIdAndDossierVersion(applicationId, 1).orElseThrow();
        assertThat(response.getContentAsString()).contains(admitted.getId());
        assertThat(admitted.getProcessInstanceKey()).isEqualTo(started.getProcessInstanceKey());
        if (response.getStatus() == 202) {
            assertThat(response.getHeader("Location")).contains(admitted.getId());
        }

        ConfirmOperation completed = await("persisted COMPLETED operation", INDEX_TIMEOUT, () -> {
            coordinator.recover(admitted.getId());
            return operations.findById(admitted.getId()).orElseThrow();
        }, operation -> operation.getState() == ConfirmOperation.State.COMPLETED);
        assertThat(completed.getUserTaskKey()).isEqualTo(open.taskKey());
        assertThat(completed.getAuditLogId()).isNotBlank();
        assertThat(applications.findById(applicationId).orElseThrow().getCompletenessStatus())
                .isEqualTo(CompletenessStatus.READY_FOR_CREDIT_REVIEW);
        assertThat(audits.countByApplicationApplicationIdAndAction(applicationId, "DOSSIER_CONFIRMED")).isEqualTo(1);
        var audit = audits.findById(completed.getAuditLogId()).orElseThrow();
        assertThat(audit.getAction()).isEqualTo("DOSSIER_CONFIRMED");
        assertThat(audit.getApplication().getApplicationId()).isEqualTo(applicationId);
        assertThat(audit.getDetails()).contains("applicationId=" + applicationId.length() + ":" + applicationId,
                ";version=1;", ";actor=" + "reviewer-it".length() + ":reviewer-it");
        assertThat(audit.getCreatedAt()).isNotNull();
        mvc.perform(get("/api/v1/dossiers/" + applicationId + "/confirm-operations/" + completed.getId())
                        .with(user("reviewer-it").roles("REVIEWER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("COMPLETED"));

        await("completed engine User Task", INDEX_TIMEOUT,
                () -> queries.task(open.taskKey()).path("taskState").asString(),
                state -> "COMPLETED".equals(state));
        await("process advanced to end event", INDEX_TIMEOUT,
                () -> queries.process(started.getProcessInstanceKey()).path("state").asString(),
                state -> "COMPLETED".equals(state));
        assertThat(taskClient.completedBy(expected, open.taskKey(), completed.getId())).isTrue();

        mvc.perform(confirm(applicationId, 1)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DOSSIER_ALREADY_CONFIRMED"));
        coordinator.recover(completed.getId());
        assertThat(operations.count()).isEqualTo(1);
        assertThat(audits.countByApplicationApplicationIdAndAction(applicationId, "DOSSIER_CONFIRMED")).isEqualTo(1);
        System.out.printf("CAMUNDA_IT gateway=%s deployment=%d definition=%d instance=%d task=%d http=%d operation=%s%n",
                topology.getGatewayVersion(), deployment.getKey(), definition.getProcessDefinitionKey(),
                started.getProcessInstanceKey(), open.taskKey(), response.getStatus(), completed.getId());
    }

    private static ConfirmReviewFactsSource.ReviewFacts acceptedFacts(String app, String fileId) {
        Instant now = Instant.now();
        String manifest = "synthetic-manifest-" + fileId;
        var snapshot = new ConfirmEligibilityService.SnapshotFacts(app, 1L,
                manifest, "synthetic-context", now.minusSeconds(120), now.minusSeconds(60),
                now.plusSeconds(1800).truncatedTo(ChronoUnit.MICROS), true, true, true, true);
        var requirement = new ConfirmEligibilityService.RequirementFact("SYNTHETIC-REQUIREMENT",
                ConfirmEligibilityService.Applicability.APPLICABLE, null,
                ConfirmEligibilityService.EvidenceState.ACCEPTED, List.of(fileId),
                ConfirmEligibilityService.ExceptionState.NONE);
        var checklist = new ConfirmEligibilityService.ChecklistFacts(app, 1L,
                manifest, "synthetic-context", "synthetic-policy", true, List.of(requirement));
        return new ConfirmReviewFactsSource.ReviewFacts(1L, snapshot, checklist);
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder confirm(
            String applicationId, int version) {
        return post("/api/v1/dossiers/" + applicationId + "/confirm")
                .with(user("reviewer-it").roles("REVIEWER")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reviewerName\":\"Synthetic Reviewer\",\"note\":\"Isolated engine test\",\"expectedVersion\":"
                        + version + "}");
    }

    private static <T> T await(String label, Duration timeout, Supplier<T> observe, Predicate<T> ready)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        T last;
        do {
            last = observe.get();
            if (ready.test(last)) return last;
            if (System.nanoTime() >= deadline) break;
            Thread.sleep(250);
        } while (true);
        throw new AssertionError("Timed out waiting for " + label + "; last observation=" + last);
    }
}
