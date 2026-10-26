-- SQL Server: stop all API/worker instances, back up the database, then run with sqlcmd -b.
-- Upgrades the existing Spring Boot schema (dbo.users/folders/media_files/media_jobs).
-- Does not delete or reassign orphaned data. Re-running is safe.
SET XACT_ABORT ON;
DECLARE @applied bit=0;
IF OBJECT_ID(N'dbo.media_schema_migrations',N'U') IS NOT NULL
    EXEC sys.sp_executesql N'SELECT @done=1 FROM dbo.media_schema_migrations WHERE version=1',N'@done bit OUTPUT',@done=@applied OUTPUT;
IF @applied=1 RETURN;
BEGIN TRY
BEGIN TRANSACTION;
IF OBJECT_ID(N'dbo.media_files',N'U') IS NULL OR OBJECT_ID(N'dbo.folders',N'U') IS NULL OR OBJECT_ID(N'dbo.media_jobs',N'U') IS NULL
    THROW 51000, 'Existing media_files/folders/media_jobs schema is required; verify database and table naming.', 1;
IF EXISTS (SELECT 1 FROM dbo.media_files f LEFT JOIN dbo.folders p ON p.id=f.folder_id WHERE f.folder_id IS NOT NULL AND (p.id IS NULL OR p.user_id<>f.user_id))
    THROW 51001, 'Resolve orphaned or cross-owner media_files.folder_id before migrating.', 1;
IF EXISTS (SELECT 1 FROM dbo.folders f LEFT JOIN dbo.folders p ON p.id=f.parent_folder_id WHERE f.parent_folder_id IS NOT NULL AND (p.id IS NULL OR p.user_id<>f.user_id OR p.id=f.id))
    THROW 51002, 'Resolve invalid folders.parent_folder_id before migrating.', 1;
IF EXISTS (SELECT 1 FROM dbo.media_jobs j LEFT JOIN dbo.folders p ON p.id=j.folder_id WHERE j.folder_id IS NOT NULL AND j.status IN ('QUEUED','RUNNING','PUBLISH_FAILED') AND (p.id IS NULL OR p.user_id<>j.user_id))
    THROW 51003, 'Resolve active jobs with missing or cross-owner destination folders before migrating.', 1;

ALTER TABLE dbo.media_files ALTER COLUMN title nvarchar(255) NOT NULL;
ALTER TABLE dbo.folders ALTER COLUMN folder_name nvarchar(255) NOT NULL;
IF COL_LENGTH('dbo.media_files','checksum') IS NULL ALTER TABLE dbo.media_files ADD checksum varchar(64) NULL;
IF COL_LENGTH('dbo.media_files','search_title') IS NULL EXEC(N'ALTER TABLE dbo.media_files ADD search_title nvarchar(255) COLLATE Latin1_General_100_CI_AI NULL');
IF COL_LENGTH('dbo.folders','search_name') IS NULL EXEC(N'ALTER TABLE dbo.folders ADD search_name nvarchar(255) COLLATE Latin1_General_100_CI_AI NULL');
-- Accent-insensitive collation supports old names; new writes store Java-normalized text.
EXEC(N'UPDATE dbo.media_files SET search_title=LOWER(REPLACE(REPLACE(title,N''đ'',N''d''),N''Đ'',N''D'')) WHERE search_title IS NULL');
EXEC(N'UPDATE dbo.folders SET search_name=LOWER(REPLACE(REPLACE(folder_name,N''đ'',N''d''),N''Đ'',N''D'')) WHERE search_name IS NULL');

-- Hibernate's old enum CHECK rejects the new workflow states. Replace only the
-- status-only enum constraint, retaining checks involving other columns.
DECLARE @dropStatusChecks nvarchar(max)=N'';
SELECT @dropStatusChecks=@dropStatusChecks+N'ALTER TABLE dbo.media_jobs DROP CONSTRAINT '+QUOTENAME(c.name)+N';'
FROM sys.check_constraints c
WHERE c.parent_object_id=OBJECT_ID(N'dbo.media_jobs')
  AND c.definition LIKE '%QUEUED%' AND c.definition LIKE '%PUBLISH_FAILED%'
  AND c.definition NOT LIKE '%PENDING_DISPATCH%'
  AND EXISTS(SELECT 1 FROM sys.sql_expression_dependencies d WHERE d.referencing_id=c.object_id AND d.referenced_id=c.parent_object_id AND d.referenced_minor_id=COLUMNPROPERTY(c.parent_object_id,'status','ColumnId'))
  AND NOT EXISTS(SELECT 1 FROM sys.sql_expression_dependencies d WHERE d.referencing_id=c.object_id AND d.referenced_id=c.parent_object_id AND d.referenced_minor_id NOT IN (0,COLUMNPROPERTY(c.parent_object_id,'status','ColumnId')));
IF LEN(@dropStatusChecks)>0 EXEC sys.sp_executesql @dropStatusChecks;
IF NOT EXISTS(SELECT 1 FROM sys.check_constraints WHERE parent_object_id=OBJECT_ID(N'dbo.media_jobs') AND name='ck_media_job_status')
ALTER TABLE dbo.media_jobs WITH CHECK ADD CONSTRAINT ck_media_job_status CHECK(status IN ('PENDING_DISPATCH','QUEUED','RUNNING','RETRYING','CANCEL_REQUESTED','COMPLETED','FAILED','CANCELLED','PUBLISH_FAILED'));

IF COL_LENGTH('dbo.media_jobs','request_key') IS NULL ALTER TABLE dbo.media_jobs ADD request_key varchar(100) NULL;
IF COL_LENGTH('dbo.media_jobs','request_hash') IS NULL ALTER TABLE dbo.media_jobs ADD request_hash varchar(64) NULL;
IF COL_LENGTH('dbo.media_jobs','lease_token') IS NULL ALTER TABLE dbo.media_jobs ADD lease_token varchar(36) NULL;
IF COL_LENGTH('dbo.media_jobs','lease_until') IS NULL ALTER TABLE dbo.media_jobs ADD lease_until datetimeoffset(6) NULL;
IF COL_LENGTH('dbo.media_jobs','next_attempt_at') IS NULL ALTER TABLE dbo.media_jobs ADD next_attempt_at datetimeoffset(6) NULL;
IF COL_LENGTH('dbo.media_jobs','attempts') IS NULL ALTER TABLE dbo.media_jobs ADD attempts int NOT NULL CONSTRAINT df_job_attempts DEFAULT 0 WITH VALUES;
IF COL_LENGTH('dbo.media_jobs','max_attempts') IS NULL ALTER TABLE dbo.media_jobs ADD max_attempts int NOT NULL CONSTRAINT df_job_max_attempts DEFAULT 2 WITH VALUES;
IF COL_LENGTH('dbo.media_jobs','progress') IS NULL ALTER TABLE dbo.media_jobs ADD progress int NOT NULL CONSTRAINT df_job_progress DEFAULT 0 WITH VALUES;
IF COL_LENGTH('dbo.media_jobs','reserved_bytes') IS NULL ALTER TABLE dbo.media_jobs ADD reserved_bytes bigint NOT NULL CONSTRAINT df_job_reserve DEFAULT 0 WITH VALUES;
IF COL_LENGTH('dbo.media_jobs','error_code') IS NULL ALTER TABLE dbo.media_jobs ADD error_code varchar(50) NULL;
IF COL_LENGTH('dbo.media_jobs','error_message') IS NULL ALTER TABLE dbo.media_jobs ADD error_message nvarchar(300) NULL;
-- The old workers must be stopped before releasing their ownership. The outbox repairs delivery.
EXEC(N'UPDATE dbo.media_jobs SET status=''RETRYING'',lease_token=NULL,lease_until=NULL,next_attempt_at=SYSDATETIMEOFFSET(),updated_at=SYSDATETIMEOFFSET() WHERE status=''RUNNING''');

IF OBJECT_ID(N'dbo.job_outbox',N'U') IS NULL
CREATE TABLE dbo.job_outbox (
    id varchar(36) NOT NULL PRIMARY KEY,
    sent bit NOT NULL,
    next_attempt_at datetimeoffset(6) NOT NULL,
    lease_token varchar(36) NULL,
    lease_until datetimeoffset(6) NULL,
    attempts int NOT NULL
);
IF OBJECT_ID(N'dbo.upload_sessions',N'U') IS NULL
CREATE TABLE dbo.upload_sessions (
    id varchar(36) NOT NULL PRIMARY KEY,
    user_id varchar(36) NOT NULL,
    upload_id varchar(36) NOT NULL,
    folder_id varchar(36) NULL,
    filename nvarchar(255) NULL,
    total_chunks int NOT NULL,
    expected_bytes bigint NULL,
    reserved_bytes bigint NOT NULL,
    result_file_id varchar(36) NOT NULL,
    status varchar(20) NOT NULL,
    expires_at datetimeoffset(6) NOT NULL,
    lease_token varchar(36) NULL,
    lease_until datetimeoffset(6) NULL,
    content_hash varchar(64) NULL,
    cleaned bit NOT NULL CONSTRAINT df_upload_cleaned DEFAULT 0,
    CONSTRAINT uk_upload_owner UNIQUE(user_id,upload_id)
);
IF OBJECT_ID(N'dbo.storage_identities',N'U') IS NULL
CREATE TABLE dbo.storage_identities(id varchar(20) NOT NULL PRIMARY KEY,token varchar(36) NOT NULL);

IF NOT EXISTS(SELECT 1 FROM sys.foreign_keys WHERE name='fk_media_folder')
ALTER TABLE dbo.media_files WITH CHECK ADD CONSTRAINT fk_media_folder FOREIGN KEY(folder_id) REFERENCES dbo.folders(id);
IF NOT EXISTS(SELECT 1 FROM sys.foreign_keys WHERE name='fk_folder_parent')
ALTER TABLE dbo.folders WITH CHECK ADD CONSTRAINT fk_folder_parent FOREIGN KEY(parent_folder_id) REFERENCES dbo.folders(id);
IF NOT EXISTS(SELECT 1 FROM sys.indexes WHERE object_id=OBJECT_ID('dbo.media_jobs') AND name='uk_job_request')
EXEC(N'CREATE UNIQUE INDEX uk_job_request ON dbo.media_jobs(user_id,request_key) WHERE request_key IS NOT NULL');
IF NOT EXISTS(SELECT 1 FROM sys.indexes WHERE object_id=OBJECT_ID('dbo.media_jobs') AND name='idx_job_owner_status')
CREATE INDEX idx_job_owner_status ON dbo.media_jobs(user_id,status);
IF NOT EXISTS(SELECT 1 FROM sys.indexes WHERE object_id=OBJECT_ID('dbo.media_jobs') AND name='idx_job_lease')
EXEC(N'CREATE INDEX idx_job_lease ON dbo.media_jobs(status,lease_until)');
IF NOT EXISTS(SELECT 1 FROM sys.indexes WHERE object_id=OBJECT_ID('dbo.media_jobs') AND name='idx_job_owner_folder')
CREATE INDEX idx_job_owner_folder ON dbo.media_jobs(user_id,folder_id);
IF NOT EXISTS(SELECT 1 FROM sys.indexes WHERE object_id=OBJECT_ID('dbo.job_outbox') AND name='idx_outbox_due')
CREATE INDEX idx_outbox_due ON dbo.job_outbox(sent,next_attempt_at,lease_until);
IF NOT EXISTS(SELECT 1 FROM sys.indexes WHERE object_id=OBJECT_ID('dbo.upload_sessions') AND name='idx_upload_owner_status')
CREATE INDEX idx_upload_owner_status ON dbo.upload_sessions(user_id,status);
IF NOT EXISTS(SELECT 1 FROM sys.indexes WHERE object_id=OBJECT_ID('dbo.upload_sessions') AND name='idx_upload_expiry')
CREATE INDEX idx_upload_expiry ON dbo.upload_sessions(expires_at);
IF NOT EXISTS(SELECT 1 FROM sys.indexes WHERE object_id=OBJECT_ID('dbo.media_files') AND name='idx_file_listing')
CREATE INDEX idx_file_listing ON dbo.media_files(user_id,folder_id,status,title,id);
IF NOT EXISTS(SELECT 1 FROM sys.indexes WHERE object_id=OBJECT_ID('dbo.media_files') AND name='idx_file_status')
CREATE INDEX idx_file_status ON dbo.media_files(status);
IF NOT EXISTS(SELECT 1 FROM sys.indexes WHERE object_id=OBJECT_ID('dbo.folders') AND name='idx_folder_listing')
CREATE INDEX idx_folder_listing ON dbo.folders(user_id,parent_folder_id,folder_name,id);
IF OBJECT_ID(N'dbo.media_schema_migrations',N'U') IS NULL
CREATE TABLE dbo.media_schema_migrations(version int NOT NULL PRIMARY KEY,applied_at datetimeoffset(6) NOT NULL);
IF NOT EXISTS(SELECT 1 FROM dbo.media_schema_migrations WHERE version=1)
INSERT INTO dbo.media_schema_migrations VALUES(1,SYSDATETIMEOFFSET());
COMMIT;
END TRY
BEGIN CATCH
IF @@TRANCOUNT>0 ROLLBACK;
THROW;
END CATCH;
