-- R9 only: reduce a freshly JPA-generated, empty disposable database to its five
-- preexisting tables. Never run against R8 or a shared/approved database.
SET XACT_ABORT ON;

IF DB_NAME() <> N'RetailLoanConfirm02Q_MigrationIT'
    THROW 51100, 'Wrong database for synthetic baseline preparation.', 1;
IF (SELECT COUNT(*) FROM sys.tables WHERE is_ms_shipped = 0) <> 7
    THROW 51101, 'Expected exactly seven JPA-generated tables.', 1;
IF OBJECT_ID(N'dbo.confirm_operations', N'U') IS NULL
    OR OBJECT_ID(N'dbo.workflow_correlations', N'U') IS NULL
    THROW 51102, 'Expected new JPA tables are missing.', 1;
IF EXISTS (SELECT 1 FROM dbo.confirm_operations)
    OR EXISTS (SELECT 1 FROM dbo.workflow_correlations)
    THROW 51103, 'Refusing to remove populated tables.', 1;
IF EXISTS (SELECT 1 FROM sys.foreign_keys
           WHERE referenced_object_id IN (OBJECT_ID(N'dbo.confirm_operations'),
                                          OBJECT_ID(N'dbo.workflow_correlations')))
    THROW 51104, 'Other tables reference a new JPA table.', 1;

BEGIN TRY
    BEGIN TRANSACTION;
    DROP TABLE dbo.confirm_operations;
    DROP TABLE dbo.workflow_correlations;
    IF (SELECT COUNT(*) FROM sys.tables WHERE is_ms_shipped = 0) <> 5
        THROW 51105, 'Synthetic baseline preparation did not leave five tables.', 1;
    COMMIT TRANSACTION;
END TRY
BEGIN CATCH
    IF @@TRANCOUNT > 0 ROLLBACK TRANSACTION;
    THROW;
END CATCH;
