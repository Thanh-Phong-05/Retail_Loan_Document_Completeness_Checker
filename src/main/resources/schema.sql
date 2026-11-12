IF OBJECT_ID('dbo.Applications', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.Applications (
        application_id VARCHAR(50) NOT NULL PRIMARY KEY,
        product_type VARCHAR(100) NOT NULL,
        applicant_name NVARCHAR(200) NOT NULL,
        submission_date DATE NULL,
        completeness_status VARCHAR(40) NULL
    );
END;

IF OBJECT_ID('dbo.DossierVersions', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.DossierVersions (
        dossier_version_id VARCHAR(50) NOT NULL PRIMARY KEY,
        application_id VARCHAR(50) NOT NULL,
        version_number INT NOT NULL,
        created_at DATETIME2 NOT NULL,
        CONSTRAINT FK_DossierVersions_Applications
            FOREIGN KEY (application_id) REFERENCES dbo.Applications(application_id),
        CONSTRAINT UQ_DossierVersions_ApplicationVersion
            UNIQUE (application_id, version_number)
    );
END;

IF OBJECT_ID('dbo.DocumentFiles', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.DocumentFiles (
        file_id VARCHAR(50) NOT NULL PRIMARY KEY,
        dossier_version_id VARCHAR(50) NOT NULL,
        doc_type VARCHAR(40) NOT NULL,
        file_name NVARCHAR(255) NOT NULL,
        stored_file_name VARCHAR(100) NOT NULL,
        storage_path NVARCHAR(500) NOT NULL,
        content_type VARCHAR(100) NOT NULL,
        file_size BIGINT NOT NULL,
        file_hash_sha256 CHAR(64) NOT NULL,
        status VARCHAR(30) NULL,
        missing_period NVARCHAR(100) NULL,
        page_count INT NOT NULL DEFAULT 0,
        page_anchor INT NOT NULL DEFAULT 0,
        CONSTRAINT FK_DocumentFiles_DossierVersions
            FOREIGN KEY (dossier_version_id) REFERENCES dbo.DossierVersions(dossier_version_id)
    );
END;

IF OBJECT_ID('dbo.CompletenessResults', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.CompletenessResults (
        result_id VARCHAR(50) NOT NULL PRIMARY KEY,
        file_id VARCHAR(50) NOT NULL,
        status VARCHAR(30) NOT NULL,
        reason NVARCHAR(500) NULL,
        missing_period NVARCHAR(100) NULL,
        page_anchor INT NULL,
        created_at DATETIME2 NOT NULL,
        CONSTRAINT FK_CompletenessResults_DocumentFiles
            FOREIGN KEY (file_id) REFERENCES dbo.DocumentFiles(file_id)
    );
END;

IF OBJECT_ID('dbo.AuditLogs', 'U') IS NULL
BEGIN
    CREATE TABLE dbo.AuditLogs (
        audit_log_id VARCHAR(50) NOT NULL PRIMARY KEY,
        application_id VARCHAR(50) NOT NULL,
        action VARCHAR(100) NOT NULL,
        details NVARCHAR(1000) NULL,
        created_at DATETIME2 NOT NULL,
        CONSTRAINT FK_AuditLogs_Applications
            FOREIGN KEY (application_id) REFERENCES dbo.Applications(application_id)
    );
END;
