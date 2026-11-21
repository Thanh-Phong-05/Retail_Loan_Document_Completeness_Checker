# Opt-in real Camunda Confirm test

`DurableConfirmCamundaIT` uses the real Camunda 8.10.9 client, the real Backend
Confirm components, and an isolated H2 test database. Its reviewer identity,
case scope, and accepted review facts exist only in the test class. The fixture
`src/test/resources/workflow/confirm-02q-camunda-it.bpmn` has the dedicated
process ID `retail-loan-confirm-02q-it`; it does not exercise the team's DMN or
full BPMN. The ordinary `mvn test` suite does not select the `*IT` class.

1. Obtain the **complete** official [Camunda 8.10 Docker Compose distribution](https://github.com/camunda/camunda-distributions/releases/tag/docker-compose-8.10) and extract it into a new directory outside this repository. Keep its `.env` and mounted configuration files. In that copy, pin `CAMUNDA_VERSION=8.10.9`. Use its lightweight Compose file. Verify the resolved image tag, ports, networks, and volume names with `docker compose -p unit02-confirm-02q-it config` before starting it. Stop if ports `26500` or `8088` are already used by another cluster.
2. From the extracted distribution directory, run `docker compose -p unit02-confirm-02q-it up -d` and `docker compose -p unit02-confirm-02q-it ps`. The Compose project name gives this run its own resources. Do not use this repository's root `docker-compose.yml`: that file is Camunda 8.2. Do not use a shared/team cluster. Do not use `docker compose down -v` or remove any existing project volumes.
3. In PowerShell, from the repository root, run:

   ```powershell
   $env:CAMUNDA_IT_ISOLATED = 'true'
   $env:CAMUNDA_IT_GRPC_ADDRESS = 'http://127.0.0.1:26500'
   $env:CAMUNDA_IT_REST_ADDRESS = 'http://127.0.0.1:8088'
   .\mvnw.cmd '-Dmaven.repo.local=target/02q-m2' '-Dtest=DurableConfirmCamundaIT' test
   ```

   Set `CAMUNDA_IT_ISOLATED` only after verifying project and port ownership.
   The test rejects a missing flag, wrong engine version, failed deployment,
   unavailable task, incomplete operation, or polling timeout. It prints the
   observed gateway, deployment, process, task, HTTP status, and operation IDs
   on success. A `202` is followed by real reconciliation until completion.
   The test leaves its isolated process and operation data in the test cluster
   for inspection; it does not perform destructive cleanup.

The Backend database in this test is H2 with `create-drop`; this test does not
validate the SQL Server migration or production authorization/review facts.
