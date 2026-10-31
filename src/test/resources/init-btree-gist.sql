-- Pre-create the btree_gist extension required by the Flyway migration.
-- Testcontainers runs this script against the fresh DB before the app starts.
CREATE EXTENSION IF NOT EXISTS btree_gist;

