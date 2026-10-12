import copy
import importlib.util
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location('history_baseline', ROOT / 'scripts/trace_link_history_baseline.py')


class HistoryBaselineTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.module = importlib.util.module_from_spec(SPEC)
        SPEC.loader.exec_module(cls.module)
        cls.policy = json.loads((ROOT / 'contracts/baselines/trace-link-baseline-policy-v1.json').read_text())

    def key(self):
        return dict(datasetId='trace-link-md-30d-v1', domain='TRANSPORT', metricId='transport.traffic',
                    unit='GBPS', metricPolicyVersion='trace-link-kpi-v1', topologyVersion='trace-link-md-topology-v1',
                    scope=dict(namespace='trace-link-reference-v1', type='LINK_DIRECTION', id='chi-hin:a-to-b',
                               cityIds=['CHI', 'HIN'], directionId='a-to-b', partnerId=None, procedure=None))

    def request(self):
        rows = []
        for i, day in enumerate(range(14, 19)):
            rows.append(dict(measurementId=str(day), key=self.key(), from_=f'2026-09-{day}T09:00:00Z',
                             to=f'2026-09-{day}T09:05:00Z', value=[10, 11, 12, 13, 100][i], coverage=1, eligible=True))
        for row in rows:
            row['from'] = row.pop('from_')
        return dict(trainingFrom='2026-09-14T09:00:00Z', trainingTo='2026-09-18T09:05:00Z',
                    evaluationFrom='2026-09-18T09:05:00Z', sourceManifestSha256='a' * 64,
                    topologySnapshotSha256='b' * 64, exclusions=[], samples=rows)

    def test_median_mad_and_order_determinism(self):
        request = self.request()
        result = self.module.fit(request, self.policy)
        self.assertEqual((12, 1, 5, 1), tuple(result['cells'][0][k] for k in ('center', 'mad', 'distinctDays', 'coverage')))
        request['samples'].reverse()
        self.assertEqual(result, self.module.fit(request, self.policy))
        self.module.validate_artifact(result)

    def test_leakage_duplicate_truth_and_nonfinite_rejected(self):
        for change in ('future', 'duplicate', 'truth', 'nan'):
            request = self.request()
            if change == 'future': request['samples'][0]['to'] = '2026-09-19T09:05:00Z'
            if change == 'duplicate':
                row = copy.deepcopy(request['samples'][0]); row['value'] = 4; request['samples'].append(row)
            if change == 'truth': request['samples'][0]['expectedLabel'] = 'NORMAL'
            if change == 'nan': request['samples'][0]['value'] = float('nan')
            with self.subTest(change=change), self.assertRaises(ValueError):
                self.module.fit(request, self.policy)

    def test_exclusion_overlap_and_coverage_are_separate(self):
        request = self.request()
        request['exclusions'] = [dict(from_='2026-09-18T09:02:00Z', to='2026-09-18T09:03:00Z', reason='DECLARED_INJECTION')]
        request['exclusions'][0]['from'] = request['exclusions'][0].pop('from_')
        result = self.module.fit(request, self.policy)
        self.assertEqual(1, result['excludedCount'])
        self.assertEqual(11.5, result['cells'][0]['center'])
        self.assertEqual(1, result['cells'][0]['coverage'])
        request['exclusions'] = []
        request['samples'][0]['coverage'] = .4
        self.assertEqual([], self.module.fit(request, self.policy)['cells'])

    def test_absent_excluded_windows_do_not_become_missing(self):
        request = self.request()
        request['exclusions'] = [dict(**{'from': '2026-09-18T09:02:00Z'},
                                      to='2026-09-18T09:03:00Z', reason='DECLARED_INJECTION')]
        supplied = self.module.fit(request, self.policy)
        request['samples'].pop()
        absent = self.module.fit(request, self.policy)
        for field in ('expectedCount', 'acceptedCount', 'excludedCount', 'missingCount', 'invalidCount', 'cells'):
            self.assertEqual(supplied[field], absent[field], field)
        self.assertEqual((1153, 4, 1, 1148), tuple(absent[k] for k in
                         ('expectedCount', 'acceptedCount', 'excludedCount', 'missingCount')))
        request['samples'][0]['value'] = None
        null_row = self.module.fit(request, self.policy)
        self.assertEqual((3, 1, 1149), tuple(null_row[k] for k in ('acceptedCount', 'excludedCount', 'missingCount')))
        duplicate = copy.deepcopy(request['samples'][1])
        duplicate['measurementId'] = 'different-id-same-key-window'
        request['samples'].append(duplicate)
        with self.assertRaises(ValueError): self.module.fit(request, self.policy)

    def test_zero_and_minimum_dates(self):
        request = self.request()
        for row in request['samples']: row['value'] = 0
        cell = self.module.fit(request, self.policy)['cells'][0]
        self.assertEqual((0, 0), (cell['center'], cell['mad']))
        request['samples'] = request['samples'][:2]
        self.assertEqual([], self.module.fit(request, self.policy)['cells'])

    def test_identical_duplicates_have_no_statistical_weight(self):
        request = self.request()
        expected = self.module.fit(request, self.policy)
        request['samples'].append(copy.deepcopy(request['samples'][0]))
        self.assertEqual(expected, self.module.fit(request, self.policy))

    def test_timezone_slot_and_dst_fold(self):
        self.assertEqual((144, 'WEEKDAY', '2026-09-14'), self.module.local_slot('2026-09-14T09:00:00Z', self.policy))
        # Both UTC instants represent the same autumn civil slot in Chisinau.
        self.assertEqual(self.module.local_slot('2026-10-25T00:30:00Z', self.policy),
                         self.module.local_slot('2026-10-25T01:30:00Z', self.policy))
        request = self.request()
        request.update(trainingFrom='2026-10-10T00:00:00Z', trainingTo='2026-10-25T03:00:00Z', evaluationFrom='2026-10-25T03:00:00Z')
        request['samples'] = [dict(measurementId=str(d), key=self.key(), **{'from':f'2026-10-{d}T00:30:00Z', 'to':f'2026-10-{d}T00:35:00Z'},
                                   value=10, coverage=1, eligible=True) for d in ('10','11','17','18','24','25')]
        request['samples'].append(dict(measurementId='fold', key=self.key(), **{'from':'2026-10-25T01:30:00Z', 'to':'2026-10-25T01:35:00Z'}, value=100, coverage=1, eligible=True))
        cell = self.module.fit(request, self.policy)['cells'][0]
        self.assertEqual((6, 7, 1, 10, 0), tuple(cell[k] for k in ('distinctDays','sampleCount','coverage','center','mad')))

    def test_policy_rejects_unknown_and_bad_thresholds(self):
        for name, value in [('minimumDistinctDays', 0), ('minimumCoverage', 1.1), ('hiddenConstant', 99)]:
            policy = copy.deepcopy(self.policy)
            policy[name] = value
            with self.assertRaises(ValueError): self.module.validate_policy(policy)

    def test_peer_and_artifact_integrity(self):
        policy = copy.deepcopy(self.policy)
        left, right = self.key(), self.key()
        right['scope']['id'] = 'chi-orh:a-to-b'; right['scope']['cityIds'] = ['CHI', 'ORH']
        policy['peerFallbacks'] = [dict(key=left, peerKey=right)]
        self.module.validate_policy(policy)
        right['scope']['directionId'] = 'b-to-a'; right['scope']['id'] = 'chi-orh:b-to-a'
        with self.assertRaises(ValueError): self.module.validate_policy(policy)
        artifact = self.module.fit(self.request(), self.policy)
        artifact['cells'][0]['expectedDays'] = 6
        with self.assertRaises(ValueError): self.module.validate_artifact(artifact)

    def test_artifact_pins_estimator_configuration_and_timezone_data(self):
        artifact = self.module.fit(self.request(), self.policy)
        self.assertEqual(self.policy, artifact['estimatorConfiguration'])
        self.assertEqual('2026b', artifact['timezoneRulesVersion'])
        artifact['estimatorConfiguration']['minimumRateAttempts'] = 999
        with self.assertRaises(ValueError): self.module.validate_artifact(artifact, self.policy)

    def test_percent_samples_and_loaded_centers_obey_physical_range(self):
        request = self.request()
        key = dict(datasetId='trace-link-md-30d-v1', domain='ROAMING', metricId='roaming.data.success',
                   unit='PERCENT', metricPolicyVersion='trace-link-kpi-v1', topologyVersion='trace-link-md-topology-v1',
                   scope=dict(namespace='trace-link-reference-v1', type='PARTNER_DIRECTION', id='RO-DEMO-01:out:DATA',
                              cityIds=[], directionId='out', partnerId='RO-DEMO-01', procedure='DATA'))
        for row in request['samples']: row['key'] = copy.deepcopy(key)
        for value in (-1, 200):
            request['samples'][0]['value'] = value
            with self.assertRaises(ValueError): self.module.fit(request, self.policy)
        request['samples'][0]['value'] = 0
        artifact = self.module.fit(request, self.policy)
        artifact['cells'][0]['center'] = 200
        with self.assertRaises(ValueError): self.module.validate_artifact(artifact)


if __name__ == '__main__':
    unittest.main()
