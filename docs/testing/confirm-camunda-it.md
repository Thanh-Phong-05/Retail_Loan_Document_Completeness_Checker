# Opt-in real Camunda 8.5 Confirm test

`DurableConfirmCamundaIT` uses the pinned `io.camunda.spring:spring-boot-starter-camunda:8.5.0`, real Zeebe commands, Tasklist task queries, Operate process/variable queries and an isolated H2 database. Identity, case scope and accepted review facts are test-only fixtures. The ordinary Maven suite does not select this `*IT` class.

The fixture `src/test/resources/workflow/confirm-02q-camunda-it.bpmn` uses process `retail-loan-confirm-02q-it` and task `Activity_03e0s8k`. These explicit test overrides are separate from the production defaults `retail-loan-document-completeness` / `Activity_0462f5z` selected from Linh's `workflow/retail_loan_process.bpmn`. Passing this test does not validate the team's OCR/DMN/full workflow.

## Isolated engine

Use `docs/testing/confirm-camunda85.compose.yaml`, based on the official [8.5.0 core distribution](https://github.com/camunda/camunda-platform/tree/8.5.0). It pins Zeebe, Operate and Tasklist to 8.5.0 and Elasticsearch to 8.13.0. Published ports bind only to loopback; Elasticsearch is internal. The SDK's `simple` authentication mode uses the local distribution's development login. This is not a production authentication setup.

Verify project ownership, free ports and sufficient Docker memory before starting:

```powershell
docker compose -p confirm85it -f docs/testing/confirm-camunda85.compose.yaml config --quiet
docker ps -a --filter label=com.docker.compose.project=confirm85it
docker compose -p confirm85it -f docs/testing/confirm-camunda85.compose.yaml up -d
docker compose -p confirm85it -f docs/testing/confirm-camunda85.compose.yaml ps
```

| Service | Host endpoint |
| --- | --- |
| Zeebe gRPC | `127.0.0.1:27500` |
| Zeebe REST | `http://127.0.0.1:18088` |
| Zeebe management | `http://127.0.0.1:19600` |
| Operate | `http://127.0.0.1:18091` |
| Tasklist | `http://127.0.0.1:18092` |

Do not use the root legacy 8.2 Compose file, a shared cluster, or the old 8.10 cluster. Never reuse 8.10 data volumes in 8.5. The separate `confirm85it` project preserves previous environments. Do not delete volumes or use `down -v`.

## Execute

After the services are ready, run from the repository root:

```powershell
$env:CAMUNDA_IT_ISOLATED = 'true'
$env:CAMUNDA_IT_GRPC_ADDRESS = 'http://127.0.0.1:27500'
$env:CAMUNDA_IT_REST_ADDRESS = 'http://127.0.0.1:18088'
$env:CAMUNDA_IT_OPERATE_ADDRESS = 'http://127.0.0.1:18091'
$env:CAMUNDA_IT_TASKLIST_ADDRESS = 'http://127.0.0.1:18092'
.\mvnw.cmd -o '-Daether.syncContext.named.factory=noop' '-Dtest=DurableConfirmCamundaIT' test
Remove-Item Env:CAMUNDA_IT_ISOLATED
```

Offline execution requires the existing Maven cache. The test fails rather than skips when the opt-in flag, engine version, deployment, task evidence or finalization is wrong. It verifies gateway/brokers 8.5.0, deployment, persisted process correlation, native OPEN task, stale-version rejection, Confirm HTTP 200/202, COMPLETED operation, READY, exactly one audit, completed task/process and duplicate-confirm rejection. Inspect the newly generated `target/surefire-reports/*DurableConfirmCamundaIT*` reports and `CAMUNDA_IT` evidence line; ordinary regression results are not live-test evidence.

Completion uses the Zeebe native User Task command. Recovery verifies Tasklist state plus Operate root `confirmOperationId`; an outage or ambiguous evidence cannot produce READY. Process-start recovery uses a persisted `workflowStartIntentId` root variable because 8.5 has no 8.10 business-ID search. Indexing is asynchronous.

The H2 schema uses `create-drop`; no SQL Server migration runs. This test supplies neither production identity/case assignment nor authoritative review facts. Keep logs local and redact credentials/cookies before sharing. The starter's environment-bearing INFO log is suppressed in application/test configuration.

## Verified execution — November 26, 2026

The command above executed 1 test: 1 passed, 0 failures/errors/skips on the uncommitted 8.5 port over `a392565`.

- Gateway/brokers: 8.5.0; partition 1 healthy LEADER.
- Deployment: `2251799813685261`; process definition: `2251799813685260`.
- Process instance: `2251799813685262`; native task: `2251799813685270`.
- Confirm: HTTP 202; operation `c7442d12-a5c2-42c8-9598-1736b15c19f5` reached COMPLETED.
- Application READY, exactly one confirmed audit, task/process COMPLETED; duplicate Confirm rejected without a second audit.
- Operate lookup by the persisted start-intent marker returned the same process. This verifies actual query compatibility, not an injected lost-create-response scenario.
- Local evidence: `target/camunda85-live-warm.log` and the corresponding Surefire report. The first run failed while waiting for the native task: cold exporter indexes were created after the 60-second polling window. Once indexes were populated and the cluster GREEN, the unchanged assertions and polling limit passed. Do not confuse container UP with ready query indexes.

API split reference: [Camunda 8.5 native User Task migration guide](https://unsupported.docs.camunda.io/8.5/docs/apis-tools/tasklist-api-rest/migrate-to-zeebe-user-tasks/).

## Separate team BPMN deployment check

On November 26, the unchanged `workflow/retail_loan_process.bpmn` Git blob from develop `cf008abc2b0a98b631e7a87d5d01f8c83c14a144` was copied under ignored `target/` and submitted to the isolated engine. After startup-related topology/deployment timeouts, the warm-engine attempt returned `INVALID_ARGUMENT`: `Flow_0gcvgk9 > conditionExpression` cannot parse the leading `=` left by the source expression `== overallStatus = "READY_FOR_CREDIT_REVIEW"`. The source BPMN was not modified and no team process instance was started. Local evidence: `target/camunda85-team-deployment-warm.log` (1 test, 1 error).

The BPMN owner should review changing that expression to `= overallStatus = "READY_FOR_CREDIT_REVIEW"`, then redeploy and verify the actual OCR/DMN/branch behavior. This correction is proposed only. Matching process/task IDs and passing the isolated Confirm test do not resolve this team-workflow deployment blocker.
