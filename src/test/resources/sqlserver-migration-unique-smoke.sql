-- R9 only: prove the migrated operation cycle constraint rejects a duplicate.
-- All inserts are rolled back inside the disposable MigrationIT database.
SET XACT_ABORT OFF;
IF DB_NAME() <> N'RetailLoanConfirm02Q_MigrationIT'
    THROW 51120, 'Wrong database for migration constraint smoke test.', 1;
IF NOT EXISTS (SELECT 1 FROM sys.indexes
               WHERE object_id = OBJECT_ID(N'dbo.confirm_operations')
                 AND name = N'UQ_ConfirmOperation_Cycle' AND is_unique = 1)
    THROW 51121, 'Operation cycle unique constraint is missing.', 1;

BEGIN TRY
    BEGIN TRANSACTION;
    DECLARE @application_id varchar(50) =
        (SELECT TOP (1) application_id FROM dbo.applications ORDER BY application_id);
    IF @application_id IS NULL
        THROW 51122, 'Run the migrated persistence IT before this smoke test.', 1;

    INSERT dbo.confirm_operations
        (operation_id, application_id, dossier_version, actor_id, actor_role,
         reviewer_name, note, snapshot_manifest, snapshot_context,
         review_fingerprint, evidence_valid_until, process_instance_key,
         user_task_key, audit_log_id, state, attempts, last_error, created_at,
         updated_at)
    VALUES
        (CONVERT(varchar(36), NEWID()), @application_id, 987654321,
         'r9-test', 'BACK_OFFICE', 'R9 Test', NULL, 'manifest', 'context',
         REPLICATE('a', 64), TODATETIMEOFFSET(CAST('2099-01-01T00:00:00.1234567' AS datetime2(7)), '+00:00'),
         123, NULL, NULL, 'PENDING', 0, NULL, SYSDATETIMEOFFSET(), SYSDATETIMEOFFSET());

    BEGIN TRY
        INSERT dbo.confirm_operations
            (operation_id, application_id, dossier_version, actor_id, actor_role,
             reviewer_name, note, snapshot_manifest, snapshot_context,
             review_fingerprint, evidence_valid_until, process_instance_key,
             user_task_key, audit_log_id, state, attempts, last_error, created_at,
             updated_at)
        VALUES
            (CONVERT(varchar(36), NEWID()), @application_id, 987654321,
             'r9-test', 'BACK_OFFICE', 'R9 Test', NULL, 'manifest', 'context',
             REPLICATE('a', 64), TODATETIMEOFFSET(CAST('2099-01-01T00:00:00.1234567' AS datetime2(7)), '+00:00'),
             123, NULL, NULL, 'PENDING', 0, NULL, SYSDATETIMEOFFSET(), SYSDATETIMEOFFSET());
        THROW 51123, 'Duplicate operation cycle was accepted.', 1;
    END TRY
    BEGIN CATCH
        IF ERROR_NUMBER() NOT IN (2601, 2627) THROW;
    END CATCH;

    ROLLBACK TRANSACTION;
    SELECT 'PASS: duplicate operation cycle rejected; transaction rolled back' AS result;
END TRY
BEGIN CATCH
    IF @@TRANCOUNT > 0 ROLLBACK TRANSACTION;
    THROW;
END CATCH;
