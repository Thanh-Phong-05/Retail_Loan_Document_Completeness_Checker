# Durable Confirm SQL Server integration test

`DurableConfirmSqlServerIT` is opt-in. It uses a real SQL Server JDBC connection and
test-only review facts, case scope, and Camunda task client. It does **not** prove
the combined real Camunda + SQL Server flow.

## Required isolation

Use only a local SQL Server instance under your control and a database explicitly
dedicated to this test: `RetailLoanConfirm02Q_IT`. Confirm permission to create the
database and verify that the name is unused before any DDL. Do not point the test
at `RetailLoanDB` or another shared database. The test writes synthetic rows and
does not delete them afterward.

The repository does not contain the approved baseline SQL Server schema. For
R8, an isolated SQL Server 2022 Developer container (`confirm02q-sqlserver-it`,
bound only to `127.0.0.1:11433`) was used to create a new, empty
`RetailLoanConfirm02Q_IT` database. Its schema was generated once from current
JPA metadata by `SqlServerSchemaBootstrapIT` with test-only
`ddl-auto=create-only`. That bootstrap requires both
`CONFIRM_SQLSERVER_SCHEMA_BOOTSTRAP_ENABLED=true` and the exact JDBC URL below.
Run it only after verifying that this dedicated database has zero user tables:

```powershell
.\mvnw.cmd '-Dmaven.repo.local=target/02q-m2' '-Dtest=SqlServerSchemaBootstrapIT' test -q
```

The bootstrap created seven synthetic tables: `applications`, `audit_logs`,
`completeness_results`, `confirm_operations`, `document_files`,
`dossier_versions`, and `workflow_correlations`. The two `Instant` columns
checked by the SQL IT are `datetimeoffset(7)`. Clear the bootstrap flag before
running the main IT. The main IT retains `ddl-auto=validate` and does not
create or update tables. Its timestamp assertions check UTC offset zero and
the six-digit microsecond value after casting to `datetime2(6)`; they do not
claim that the underlying columns are `datetime2(6)`.

The candidate migration was **not** applied to the R8 database. R9 instead
uses a separate disposable database; see below. Neither database is an approved
team baseline.

## Run after the database is approved and prepared

Set these environment variables in the current PowerShell session through your
local credential handling method; do not put credentials in source, shell history,
or test logs:

- `CONFIRM_SQLSERVER_IT_ENABLED=true`
- `CONFIRM_SQLSERVER_IT_JDBC_URL=jdbc:sqlserver://127.0.0.1:11433;databaseName=RetailLoanConfirm02Q_IT;encrypt=false`
- `CONFIRM_SQLSERVER_IT_USER` and `CONFIRM_SQLSERVER_IT_PASSWORD`

The test URL guard accepts only `127.0.0.1:11433` and the R8, R9, or R10
database names, with no extra connection properties. The current JPA mapping
requires `nvarchar` for Confirm/Audit text, so use only the prepared R10
`RetailLoanConfirm02Q_UnicodeIT` database for current test execution. R8/R9
are preserved historical databases with the old `varchar` schema.
Run:

```powershell
.\mvnw.cmd '-Dmaven.repo.local=target/02q-m2' '-Dtest=DurableConfirmSqlServerIT' test -q
```

Check `target/surefire-reports/TEST-com.example.retail_loan_document_completeness_checker.controllers.DurableConfirmSqlServerIT.xml`.
All six tests must execute with zero skipped; a skipped result is **NOT RUN** for
SQL Server validation. Maven's default `*IT` exclusions mean the full `test` suite
does not include this class unless selected with `-Dtest`.

The test checks Hibernate startup validation, both new JPA-generated tables,
UTC offset and six-digit timestamp values, nanosecond evidence after persistence, stale/expired
facts, version rejection, READY plus one audit, recovery/idempotency, and rollback
after an audit failure. Camunda task responses are test doubles; run the separate
`DurableConfirmCamundaIT` for real-engine coverage.

## R9: migration on an isolated JPA-derived synthetic baseline

The approved team baseline is still unavailable. R9 created a separate database,
`RetailLoanConfirm02Q_MigrationIT`, in the existing isolated SQL Server container
only after checking that name was unused and the container bound to
`127.0.0.1:11433`. The R8 database and its rows were left intact.

On a **new, empty** MigrationIT database, set the same JDBC username/password
environment variables listed above, use the MigrationIT JDBC URL, and set
`CONFIRM_SQLSERVER_MIGRATION_BOOTSTRAP_ENABLED=true`. Run the one-time JPA
bootstrap:

```powershell
.\mvnw.cmd '-Dmaven.repo.local=target/02q-m2' '-Dtest=SqlServerMigrationBootstrapIT' test -q
```

The bootstrap generates seven JPA tables. Run the guarded test-only
`src/test/resources/sqlserver-migration-baseline-only.sql` **only in** MigrationIT;
it checks the database name, seven empty JPA tables and absence of incoming FKs,
then removes only `confirm_operations` and `workflow_correlations`. Verify that
the five remaining tables are `applications`, `audit_logs`,
`completeness_results`, `document_files` and `dossier_versions`. This is a
synthetic baseline derived from current JPA metadata, not a reconstruction of
the team's approved SQL Server baseline.

Apply `docs/db/confirm-02q-sqlserver-additive.sql` to this five-table database
with `sqlcmd -b`; the script contains the required SQL Server filtered-index
session `SET` options and a transaction with rollback on error. Confirm the two
new tables, `datetimeoffset(7)` columns, primary/unique/foreign/check constraints
and indexes. Clear the bootstrap flag. Set `CONFIRM_SQLSERVER_IT_ENABLED=true`
and run the main IT command above with the MigrationIT JDBC URL. The profile
keeps `ddl-auto=validate`; all five tests must execute, not skip. The guarded
`src/test/resources/sqlserver-migration-unique-smoke.sql` then verifies a
duplicate operation cycle is rejected by SQL Server and rolls back its inserts.
Finally run the full Maven regression.

Passing R9 proves the additive script works against this **synthetic** baseline.
It does not prove compatibility with an approved team baseline or production
readiness. Do not rerun the bootstrap or migration against an already prepared
database, and do not use either script on R8 or a shared database.

## R10: Vietnamese Unicode persistence

The R9 database uses `SQL_Latin1_General_CP1_CI_AS`. Its
`confirm_operations.reviewer_name`, `confirm_operations.note`, and
`audit_logs.details` columns were `varchar`, with no UTF-8 collation. A real
HTTP/JPA/SQL Server round-trip changed a Vietnamese reviewer name after commit.
R8 and R9 schemas were not altered to fix this.

R10 uses a newly created, disposable `RetailLoanConfirm02Q_UnicodeIT` database
in the same loopback-only container. Before any schema work, verify that this
name is unused and the database has zero user tables. The one-time
`SqlServerMigrationBootstrapIT` accepts this exact JDBC URL with
`CONFIRM_SQLSERVER_MIGRATION_BOOTSTRAP_ENABLED=true` and creates seven JPA
tables. On this **empty R10 database only**, prepare the five-table synthetic
baseline: verify the expected seven tables and empty new tables, change
`audit_logs.details` to nullable `varchar(1000)` to model the older baseline,
then remove the empty `confirm_operations` and `workflow_correlations` tables.
Apply `docs/db/confirm-02q-sqlserver-additive.sql` once with `sqlcmd -b`.
It converts `audit_logs.details` to `nvarchar(1000)` and creates the two new
tables with `nvarchar(200)` reviewer name and `nvarchar(1000)` note.

With the bootstrap flag unset, set `CONFIRM_SQLSERVER_IT_ENABLED=true`, the
R10 JDBC URL, and the same locally supplied database credentials. Run:

```powershell
.\mvnw.cmd '-Dmaven.repo.local=target/02q-m2' '-Dtest=DurableConfirmSqlServerIT' test -q
```

The six tests must execute with zero skipped. The Unicode test sends Vietnamese
text through Confirm HTTP, checks committed operation text after a fresh reload,
then checks the persisted AuditLog. It also rejects a note longer than 1000
Java UTF-16 code units. Hibernate remains in `ddl-auto=validate` mode.

The migration remains a candidate until the approved team baseline and its
dependent objects are reviewed. Existing already-corrupted `varchar` values
cannot be reconstructed by changing the column type. Never rerun the one-time
bootstrap or migration on R8, R9, or a shared database.
