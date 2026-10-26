-- SQL Server. Apply 001 first; stop API/worker instances and back up before migration.
SET XACT_ABORT ON;
BEGIN TRY
BEGIN TRANSACTION;
IF OBJECT_ID(N'dbo.media_schema_migrations',N'U') IS NULL
    THROW 51020, 'Apply migration 001 before migration 002.', 1;
IF NOT EXISTS(SELECT 1 FROM dbo.media_schema_migrations WHERE version=1)
    THROW 51021, 'Migration 001 has not been recorded.', 1;
IF EXISTS(SELECT 1 FROM dbo.media_schema_migrations WHERE version=2)
BEGIN
    COMMIT;
    RETURN;
END;
IF OBJECT_ID(N'dbo.media_files',N'U') IS NULL
    THROW 51022, 'Expected dbo.media_files was not found.', 1;
IF OBJECT_ID(N'dbo.file_shares',N'U') IS NULL
EXEC(N'CREATE TABLE dbo.file_shares (
    id varchar(36) NOT NULL PRIMARY KEY,
    user_id varchar(36) NOT NULL,
    file_id varchar(36) NOT NULL,
    token_hash varchar(64) NOT NULL,
    created_at datetimeoffset(6) NOT NULL,
    expires_at datetimeoffset(6) NOT NULL,
    revoked_at datetimeoffset(6) NULL,
    CONSTRAINT uk_file_share_token UNIQUE(token_hash),
    CONSTRAINT fk_share_file FOREIGN KEY(file_id) REFERENCES dbo.media_files(id)
)');
IF NOT EXISTS(SELECT 1 FROM sys.indexes WHERE object_id=OBJECT_ID(N'dbo.file_shares') AND name='idx_share_owner_file')
EXEC(N'CREATE INDEX idx_share_owner_file ON dbo.file_shares(user_id,file_id,created_at)');
IF NOT EXISTS(SELECT 1 FROM sys.indexes WHERE object_id=OBJECT_ID(N'dbo.file_shares') AND name='idx_share_expiry')
EXEC(N'CREATE INDEX idx_share_expiry ON dbo.file_shares(expires_at)');
INSERT INTO dbo.media_schema_migrations(version,applied_at) VALUES(2,SYSDATETIMEOFFSET());
COMMIT;
END TRY
BEGIN CATCH
IF @@TRANCOUNT>0 ROLLBACK;
THROW;
END CATCH;
