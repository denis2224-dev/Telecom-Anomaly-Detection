#!/usr/bin/env python3
"""Validate database privilege boundaries, role isolation, and security grant constraints."""

import os
import re
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent


def read_file(relative_path: str) -> str:
    path = ROOT / relative_path
    if not path.is_file():
        raise FileNotFoundError(f"Required security target file not found: {path}")
    return path.read_text(encoding="utf-8")


class DatabaseSecurityPolicy:
    """Represents the intended security grant and isolation matrix for the platform."""

    DATABASES = ["processing_db", "incidents_db", "keycloak_db"]
    ROLES = [
        "processing_app",
        "processing_migrator",
        "incidents_app",
        "incidents_migrator",
        "keycloak_app",
    ]

    ALLOWED_CONNECT = {
        "processing_db": {"processing_app", "processing_migrator"},
        "incidents_db": {"incidents_app", "incidents_migrator"},
        "keycloak_db": {"keycloak_app"},
    }

    SCHEMA_OWNERS = {
        "processing_db": "processing_migrator",
        "incidents_db": "incidents_migrator",
    }

    RUNTIME_ROLES = {
        "processing_db": "processing_app",
        "incidents_db": "incidents_app",
    }


class DatabaseGrantTests(unittest.TestCase):
    """Static and structural assertions on database privilege scripts and configurations."""

    @classmethod
    def setUpClass(cls):
        cls.init_script = read_file("infra/postgres/init/01-create-schemas.sh")
        cls.proxy_conf = read_file("infra/proxy/default.conf")

    def test_all_declared_roles_are_provisioned(self):
        for role in DatabaseSecurityPolicy.ROLES:
            self.assertIn(
                f"'{role}'",
                self.init_script,
                f"Role {role} must be explicitly declared in postgres provisioning script",
            )

    def test_all_logical_databases_are_provisioned_with_migrator_ownership(self):
        for db, owner in DatabaseSecurityPolicy.SCHEMA_OWNERS.items():
            pattern = rf"\('{db}',\s*'{owner}'\)"
            self.assertTrue(
                re.search(pattern, self.init_script),
                f"Database {db} must be provisioned with owner {owner}",
            )
        self.assertTrue(
            re.search(r"\('keycloak_db',\s*'keycloak_app'\)", self.init_script),
            "Database keycloak_db must be owned by keycloak_app",
        )

    def test_public_connect_is_revoked_from_all_databases(self):
        pattern = r"REVOKE\s+CONNECT\s+ON\s+DATABASE\s+([a-z0-9_,\s]+)\s+FROM\s+PUBLIC;"
        match = re.search(pattern, self.init_script, re.IGNORECASE)
        self.assertIsNotNone(match, "REVOKE CONNECT ON DATABASE ... FROM PUBLIC must be explicitly present")
        revoked_dbs = {db.strip() for db in match.group(1).split(",")}
        for db in DatabaseSecurityPolicy.DATABASES:
            self.assertIn(db, revoked_dbs, f"Database {db} must revoke CONNECT from PUBLIC")

    def test_cross_database_connect_privileges_are_isolated(self):
        for db, allowed_roles in DatabaseSecurityPolicy.ALLOWED_CONNECT.items():
            pattern = rf"GRANT\s+CONNECT\s+ON\s+DATABASE\s+{db}\s+TO\s+([a-z0-9_,\s]+);"
            match = re.search(pattern, self.init_script, re.IGNORECASE)
            self.assertIsNotNone(match, f"GRANT CONNECT ON DATABASE {db} statement must exist")
            granted_roles = {role.strip() for role in match.group(1).split(",")}
            self.assertEqual(
                allowed_roles,
                granted_roles,
                f"Database {db} must grant connect strictly to {allowed_roles}, but got {granted_roles}",
            )

    def test_runtime_roles_are_denied_ddl_and_create_in_schema(self):
        self.assertIn(
            'REVOKE CREATE ON SCHEMA app FROM :"runtime";',
            self.init_script,
            "Runtime role must have CREATE revoked from app schema to deny runtime DDL",
        )
        self.assertIn(
            "REVOKE CREATE ON SCHEMA public FROM PUBLIC;",
            self.init_script,
            "PUBLIC must have CREATE revoked from public schema",
        )

    def test_runtime_roles_receive_only_dml_default_privileges(self):
        pattern_tables = (
            r'ALTER\s+DEFAULT\s+PRIVILEGES\s+FOR\s+ROLE\s+:"migrator"\s+IN\s+SCHEMA\s+app\s+'
            r'GRANT\s+SELECT,\s*INSERT,\s*UPDATE,\s*DELETE\s+ON\s+TABLES\s+TO\s+:"runtime";'
        )
        self.assertTrue(
            re.search(pattern_tables, self.init_script, re.IGNORECASE),
            "Migrator default table privileges must strictly grant SELECT, INSERT, UPDATE, DELETE to runtime",
        )
        pattern_seq = (
            r'ALTER\s+DEFAULT\s+PRIVILEGES\s+FOR\s+ROLE\s+:"migrator"\s+IN\s+SCHEMA\s+app\s+'
            r'GRANT\s+USAGE,\s*SELECT\s+ON\s+SEQUENCES\s+TO\s+:"runtime";'
        )
        self.assertTrue(
            re.search(pattern_seq, self.init_script, re.IGNORECASE),
            "Migrator default sequence privileges must strictly grant USAGE, SELECT to runtime",
        )

    def test_proxy_hardening_and_trusted_forwarded_headers(self):
        self.assertIn("add_header X-Content-Type-Options \"nosniff\"", self.proxy_conf)
        self.assertIn("add_header X-Frame-Options \"SAMEORIGIN\"", self.proxy_conf)
        self.assertIn("add_header Referrer-Policy \"strict-origin-when-cross-origin\"", self.proxy_conf)
        self.assertIn("proxy_set_header Forwarded \"\";", self.proxy_conf, "Untrusted Forwarded header must be stripped")
        self.assertIn("proxy_set_header X-Forwarded-For $remote_addr;", self.proxy_conf)
        self.assertIn("return 405;", self.proxy_conf, "Dangerous HTTP methods (TRACE/TRACK) must be rejected")
        self.assertIn("proxy_redirect off;", self.proxy_conf, "Proxy redirects must be disabled to prevent open redirects")

    def test_auth_security_error_contracts(self):
        sec_config = read_file("services/incident-service/src/main/java/md/utm/telecom/incidents/security/SecurityConfig.java")
        self.assertIn('"UNAUTHENTICATED"', sec_config, "Unauthenticated access must report UNAUTHENTICATED")
        self.assertIn('"CSRF_INVALID"', sec_config, "Invalid CSRF token must report CSRF_INVALID")
        self.assertIn('"FORBIDDEN"', sec_config, "Permission denial must report FORBIDDEN")
        self.assertIn('deleteCookies("JSESSIONID")', sec_config, "Logout must delete JSESSIONID cookie")


def run_checks() -> int:
    suite = unittest.defaultTestLoader.loadTestsFromTestCase(DatabaseGrantTests)
    runner = unittest.TextTestRunner(verbosity=2)
    result = runner.run(suite)
    if result.wasSuccessful():
        print(f"PASS: database grant isolation, proxy hardening, and security boundaries ({result.testsRun} checks)")
        return 0
    return 1


if __name__ == "__main__":
    sys.exit(run_checks())
