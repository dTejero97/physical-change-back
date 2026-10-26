-- =====================================================================
-- V5 · Archivos en Cloudflare R2 (ADR-005) · PostgreSQL 16
-- storage: dónde está el contenido (DB = stored_file_data, R2 = bucket).
-- status:  PENDING mientras el navegador sube directamente a R2; READY al comprobarlo.
-- purpose: se añade VIDEO.
--
-- Reversión (solo si no hay archivos en R2 ni vídeos):
--   DELETE FROM stored_file WHERE storage = 'R2' OR purpose = 'VIDEO';
--   DROP INDEX IF EXISTS ix_file_pending;
--   ALTER TABLE stored_file DROP CONSTRAINT stored_file_purpose_check;
--   ALTER TABLE stored_file ADD CONSTRAINT stored_file_purpose_check CHECK (purpose IN ('DOCUMENT', 'IMAGE'));
--   ALTER TABLE stored_file DROP COLUMN status, DROP COLUMN storage;
--   DELETE FROM flyway_schema_history WHERE version = '5';
-- =====================================================================

ALTER TABLE stored_file
    ADD COLUMN storage VARCHAR(10) NOT NULL DEFAULT 'DB' CHECK (storage IN ('DB', 'R2')),
    ADD COLUMN status  VARCHAR(10) NOT NULL DEFAULT 'READY' CHECK (status IN ('PENDING', 'READY'));

ALTER TABLE stored_file DROP CONSTRAINT stored_file_purpose_check;
ALTER TABLE stored_file ADD CONSTRAINT stored_file_purpose_check CHECK (purpose IN ('DOCUMENT', 'IMAGE', 'VIDEO'));

-- Limpieza de subidas abandonadas.
CREATE INDEX ix_file_pending ON stored_file (owner_id, created_at) WHERE status = 'PENDING';
