-- TASK 02Q candidate for a separately provisioned SQL Server database.
-- REVIEW AGAINST THE ACTUAL SCHEMA AND TEST ON AN ISOLATED COPY BEFORE EXECUTION.
-- AuditLogs.details is converted in place to nvarchar(1000); review the actual
-- baseline, dependent objects, existing data and deployment lock window first.
-- No existing table or row is dropped. Apply once under migration control.
-- Runtime Hibernate schema mode must be validate, never update, after migration.
-- Required by the filtered unique index, including when applied with sqlcmd.
SET ANSI_NULLS ON;
SET ANSI_PADDING ON;
SET ANSI_WARNINGS ON;
SET ARITHABORT ON;
SET CONCAT_NULL_YIELDS_NULL ON;
SET NUMERIC_ROUNDABORT OFF;
SET QUOTED_IDENTIFIER ON;
SET XACT_ABORT ON;

IF OBJECT_ID(N'dbo.applications', N'U') IS NULL
    THROW 51000, 'Applications baseline table is missing; stop migration.', 1;
IF OBJECT_ID(N'dbo.audit_logs', N'U') IS NULL
    THROW 51003, 'AuditLogs baseline table is missing; stop migration.', 1;

IF OBJECT_ID(N'dbo.workflow_correlations', N'U') IS NOT NULL
    THROW 51001, 'WorkflowCorrelations already exists; inspect schema before migration.', 1;
IF OBJECT_ID(N'dbo.confirm_operations', N'U') IS NOT NULL
    THROW 51002, 'ConfirmOperations already exists; inspect schema before migration.', 1;

BEGIN TRY
BEGIN TRANSACTION;

IF NOT EXISTS (SELECT 1 FROM sys.columns c JOIN sys.types t ON t.user_type_id=c.user_type_id
               WHERE c.object_id=OBJECT_ID(N'dbo.audit_logs') AND c.name=N'details'
                 AND t.name IN (N'varchar', N'nvarchar')
                 AND c.max_length=CASE t.name WHEN N'varchar' THEN 1000 ELSE 2000 END
                 AND c.is_nullable=1)
    THROW 51004, 'Unexpected AuditLogs.details baseline; stop migration.', 1;
IF EXISTS (SELECT 1 FROM sys.columns c JOIN sys.types t ON t.user_type_id=c.user_type_id
           WHERE c.object_id=OBJECT_ID(N'dbo.audit_logs') AND c.name=N'details'
             AND t.name=N'varchar')
    ALTER TABLE dbo.audit_logs ALTER COLUMN details nvarchar(1000) NULL;

CREATE TABLE dbo.workflow_correlations (
    correlation_id varchar(36) NOT NULL PRIMARY KEY,
    application_id varchar(50) NOT NULL,
    dossier_version bigint NOT NULL,
    process_instance_key bigint NULL,
    process_definition_key bigint NULL,
    process_definition_version int NULL,
    process_id varchar(150) NOT NULL,
    tenant_id varchar(100) NOT NULL,
    confirm_element_id varchar(100) NOT NULL,
    created_at datetimeoffset(7) NOT NULL,
    state varchar(30) NOT NULL,
    CONSTRAINT UQ_WorkflowCorrelation_Cycle UNIQUE (application_id, dossier_version),
    CONSTRAINT FK_WorkflowCorrelation_Application FOREIGN KEY (application_id)
        REFERENCES dbo.applications(application_id),
    CONSTRAINT CK_WorkflowCorrelation_State CHECK
        (state IN ('START_PENDING', 'STARTED', 'REQUIRES_INTERVENTION'))
);
CREATE UNIQUE INDEX UX_WorkflowCorrelation_Process
    ON dbo.workflow_correlations(process_instance_key)
    WHERE process_instance_key IS NOT NULL;

CREATE TABLE dbo.confirm_operations (
    operation_id varchar(36) NOT NULL PRIMARY KEY,
    application_id varchar(50) NOT NULL,
    dossier_version bigint NOT NULL,
    actor_id varchar(200) NOT NULL,
    actor_role varchar(50) NOT NULL,
    reviewer_name nvarchar(200) NOT NULL,
    note nvarchar(1000) NULL,
    snapshot_manifest varchar(128) NOT NULL,
    snapshot_context varchar(128) NOT NULL,
    review_fingerprint varchar(64) NOT NULL,
    evidence_valid_until datetimeoffset(7) NOT NULL,
    process_instance_key bigint NOT NULL,
    user_task_key bigint NULL,
    audit_log_id varchar(36) NULL,
    state varchar(30) NOT NULL,
    attempts int NOT NULL,
    last_error varchar(200) NULL,
    created_at datetimeoffset(7) NOT NULL,
    updated_at datetimeoffset(7) NOT NULL,
    CONSTRAINT UQ_ConfirmOperation_Cycle UNIQUE (application_id, dossier_version),
    CONSTRAINT FK_ConfirmOperation_Application FOREIGN KEY (application_id)
        REFERENCES dbo.applications(application_id),
    CONSTRAINT CK_ConfirmOperation_State CHECK
        (state IN ('PENDING', 'COMPLETING', 'RECONCILING', 'COMPLETED', 'REQUIRES_INTERVENTION'))
);
CREATE INDEX IX_ConfirmOperations_State ON dbo.confirm_operations(state);

COMMIT TRANSACTION;
END TRY
BEGIN CATCH
    IF @@TRANCOUNT > 0 ROLLBACK TRANSACTION;
    THROW;
END CATCH;

-- Manual rollback plan, only if no operations/correlations have been created:
-- DROP TABLE dbo.confirm_operations; DROP TABLE dbo.workflow_correlations;
-- Never run rollback against populated tables without a separately approved data plan.
