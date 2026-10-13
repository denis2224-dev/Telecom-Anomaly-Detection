"""Unit tests for Grafana datasource, dashboard, SQL reporting views, and security boundaries."""
import json
from pathlib import Path
import re
import unittest
import yaml

ROOT = Path(__file__).resolve().parents[1]
GRAFANA_DIR = ROOT / 'infra/observability/grafana'
DATASOURCE_FILE = GRAFANA_DIR / 'provisioning/datasources/trace-link.yml'
DASHBOARD_PROVIDER_FILE = GRAFANA_DIR / 'provisioning/dashboards/trace-link.yml'
DASHBOARD_FILE = GRAFANA_DIR / 'dashboards/trace-link-overview.json'
SQL_VIEWS_FILE = GRAFANA_DIR / 'sql/trace-link-views.sql'


class TraceLinkGrafanaTests(unittest.TestCase):
    def test_datasource_configuration(self):
        self.assertTrue(DATASOURCE_FILE.is_file(), "Datasource YAML missing")
        data = yaml.safe_load(DATASOURCE_FILE.read_text(encoding='utf-8'))
        datasources = data.get('datasources', [])
        ds = next((d for d in datasources if d.get('uid') == 'trace-link-pg'), None)
        self.assertIsNotNone(ds, "Missing trace-link-pg datasource")
        self.assertEqual(ds.get('type'), 'postgres')
        self.assertEqual(ds.get('name'), 'Trace Link PostgreSQL')
        self.assertIn('jsonData', ds)
        self.assertEqual(ds['jsonData'].get('postgresVersion'), 1604)

    def test_dashboard_provider_configuration(self):
        self.assertTrue(DASHBOARD_PROVIDER_FILE.is_file(), "Dashboard provider YAML missing")
        data = yaml.safe_load(DASHBOARD_PROVIDER_FILE.read_text(encoding='utf-8'))
        providers = data.get('providers', [])
        self.assertTrue(any(p.get('options', {}).get('path') == '/var/lib/grafana/dashboards' for p in providers))

    def test_dashboard_structure_and_panels(self):
        self.assertTrue(DASHBOARD_FILE.is_file(), "Dashboard JSON missing")
        dashboard = json.loads(DASHBOARD_FILE.read_text(encoding='utf-8'))
        self.assertEqual(dashboard.get('uid'), 'trace-link-overview')
        self.assertGreaterEqual(dashboard.get('schemaVersion', 0), 39)

        # 14 distinct panels excluding row headers
        panels = [p for p in dashboard.get('panels', []) if p.get('type') != 'row']
        self.assertEqual(len(panels), 14, f"Expected exactly 14 panels, found {len(panels)}")

        # Check that all panels query reporting schema
        for p in panels:
            targets = p.get('targets', [])
            self.assertTrue(len(targets) > 0, f"Panel {p.get('id')} has no target query")
            raw_sql = targets[0].get('rawSql', '')
            self.assertIn('reporting.', raw_sql, f"Panel {p.get('title')} does not query reporting schema")

    def test_dashboard_variables_and_annotations(self):
        dashboard = json.loads(DASHBOARD_FILE.read_text(encoding='utf-8'))
        var_names = {v.get('name') for v in dashboard.get('templating', {}).get('list', [])}
        for expected in ('city', 'country', 'direction', 'resolution', 'dataset'):
            self.assertIn(expected, var_names, f"Missing variable: {expected}")

        annotations = dashboard.get('annotations', {}).get('list', [])
        self.assertTrue(any('reporting.trace_incident_annotations' in a.get('rawQuery', '') for a in annotations))

    def test_sql_views_and_least_privilege(self):
        self.assertTrue(SQL_VIEWS_FILE.is_file(), "SQL views file missing")
        sql = SQL_VIEWS_FILE.read_text(encoding='utf-8')

        for view in [
            'reporting.trace_inventory_city',
            'reporting.trace_inventory_link',
            'reporting.trace_metric_series',
            'reporting.trace_link_snapshot',
            'reporting.trace_roaming_snapshot',
            'reporting.trace_incident_annotations',
        ]:
            pattern = rf'CREATE\s+(?:OR\s+REPLACE\s+)?VIEW\s+{re.escape(view)}\b'
            self.assertTrue(re.search(pattern, sql, re.IGNORECASE), f"Missing view: {view}")

        self.assertIn('trace_link_reader', sql)
        self.assertTrue(re.search(r'REVOKE\s+ALL\s+ON\s+SCHEMA\s+app\s+FROM\s+trace_link_reader', sql, re.IGNORECASE))

        # Check that analyst private tables and secrets are never selected
        for forbidden in ['app.analysts', 'app.incident_audit', 'push_subscriptions', 'vapid']:
            self.assertNotIn(forbidden, sql.lower())


if __name__ == '__main__':
    unittest.main()
