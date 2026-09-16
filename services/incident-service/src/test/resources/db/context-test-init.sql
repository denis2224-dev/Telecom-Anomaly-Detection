-- Disposable Spring context test database. ServiceConnection supplies its credentials.
-- Separate real runtime/migrator login permissions are tested in DatabaseMigrationTest.
CREATE ROLE incidents_app NOLOGIN;
CREATE SCHEMA app;
