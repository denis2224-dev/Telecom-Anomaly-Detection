"""The strict Windows artifact gate and pinned Linux runtime are both mandatory."""
import json
from pathlib import Path
import unittest
import yaml

ROOT=Path(__file__).resolve().parents[1]

class TraceLinkCiTests(unittest.TestCase):
    def test_model_jobs_use_the_exact_packaged_python_and_require_byte_reproduction(self):
        jobs=yaml.safe_load((ROOT/'.github/workflows/integration.yml').read_text())['jobs']
        version=json.loads((ROOT/'services/ml-service/candidate-models/trace-link-shadow-v1/manifest.json').read_text())['pythonVersion']
        for name in ('model-reproducibility','model-evaluation','processor-evaluation'):
            with self.subTest(job=name):
                job=jobs[name]
                setup=next(s for s in job['steps'] if s.get('uses','').startswith('actions/setup-python@'))
                self.assertEqual(version,setup['with']['python-version'])
                self.assertFalse(job.get('continue-on-error',False))
        self.assertEqual('windows-latest',jobs['model-reproducibility']['runs-on'])
        runs='\n'.join(s.get('run','') for s in jobs['model-reproducibility']['steps'])
        self.assertIn('unittest discover -s services/ml-service/tests -v',runs)
        self.assertIn('TraceLinkFeaturesTest',runs)
        self.assertEqual('model-reproducibility',jobs['model-evaluation']['needs'])
        self.assertEqual('model-evaluation',jobs['processor-evaluation']['needs'])
        linux_runs='\n'.join(s.get('run','') for s in jobs['model-evaluation']['steps'])
        self.assertIn('test_trace_link_failure_modes.py',linux_runs)
        self.assertIn('verify-trace-link-ml-http.py',linux_runs)
