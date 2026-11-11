-- 017: app release registry.
--
-- One row per published build of a client app (kiosk APK / warden desktop).
-- The registry holds METADATA ONLY (version, download URL, sha256) — the
-- binaries themselves live in GitHub Releases, so the backend never has to
-- store or serve large files.
--
-- `mandatory` = the client must NOT keep working on the old version: the
-- kiosk blocks its screen, the warden dashboard shows a blocking overlay.
-- `optional`  = a dismissible "new version available" notice.

CREATE TABLE IF NOT EXISTS app_releases (
  id           TEXT PRIMARY KEY,
  app          TEXT NOT NULL,           -- 'kiosk' | 'warden'
  platform     TEXT NOT NULL,           -- 'android' | 'windows' | 'linux'
  version      TEXT NOT NULL,           -- human version, e.g. '1.2.0'
  version_code INT,                     -- monotonic int (kiosk compares this)
  mandatory    BOOLEAN NOT NULL DEFAULT false,
  download_url TEXT NOT NULL,
  sha256       TEXT,
  size_bytes   BIGINT,
  notes        TEXT,
  released_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  data         JSONB NOT NULL DEFAULT '{}'::jsonb
);

CREATE INDEX IF NOT EXISTS idx_app_releases_app_platform
  ON app_releases (app, platform, released_at DESC);
CREATE INDEX IF NOT EXISTS idx_app_releases_mandatory
  ON app_releases (mandatory);

CREATE OR REPLACE FUNCTION set_updated_at() RETURNS TRIGGER AS $$
BEGIN
  NEW.updated_at = now();
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_app_releases_updated ON app_releases;
CREATE TRIGGER trg_app_releases_updated BEFORE UPDATE ON app_releases
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();
