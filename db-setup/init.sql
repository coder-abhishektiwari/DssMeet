-- Executed automatically by the postgres container on first boot.
-- The app user/database are created from POSTGRES_USER/POSTGRES_DB env vars.
ALTER ROLE dssmeet SET timezone TO 'UTC';
ALTER DATABASE dssmeet SET timezone TO 'UTC';
