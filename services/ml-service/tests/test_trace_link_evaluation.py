"""Independent arithmetic and study-integrity oracles for Day 5."""
from copy import deepcopy
from pathlib import Path
import gzip
import json
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'training'))
from trace_link_evaluation import metrics, match_episodes, select_threshold, reject_truth, read_study
from trace_link_study import generate, digest, write_json


class EvaluationTests(unittest.TestCase):
    def test_counts_and_missing_denominators(self):
        result = metrics([True, True, False, False], [True, False, True, False])
        self.assertEqual((result['tp'], result['fp'], result['fn'], result['tn']), (1, 1, 1, 1))
        self.assertEqual(result['f1'], .5)
        self.assertIsNone(metrics([], [])['recall'])
        self.assertIsNone(metrics([False], [False])['precision'])
        with self.assertRaises(ValueError): metrics([True], [])

    def test_threshold_is_selected_only_from_validation_and_ties_favor_higher(self):
        self.assertEqual(select_threshold([True, False], [.999, .1], [.90, .95, .99]), .99)
        self.assertEqual(select_threshold([True, False], [.96, .92], [.90, .95, .99]), .95)

    def test_matching_is_one_to_one_and_direction_isolated(self):
        truth = [dict(group='g', domain='ROAMING', scope={'id':'p:out', 'directionId':'out'},
                      family='ROAMING_DATA', start=900, end=2100, firstFinalized=1200, recovered=2100)]
        detected = [dict(truth[0], id='a', start=1500, end=2700, detected=1501, recovered=2701),
                    dict(truth[0], id='b', start=1500, end=1800, detected=1501, recovered=None)]
        result = match_episodes(truth, detected, 300)
        self.assertEqual((result['tp'], result['fp'], result['duplicates']), (1, 1, 1))
        self.assertEqual(result['detectionDelaySeconds'], [601])
        self.assertEqual(result['finalizedDetectionDelaySeconds'], [301])
        self.assertEqual(result['recoveryDelaySeconds'], [601])
        wrong = deepcopy(detected[:1]); wrong[0]['scope']['directionId'] = 'in'
        self.assertEqual(match_episodes(truth, wrong, 300)['tp'], 0)

    def test_unmatched_is_a_miss_and_tolerance_has_an_exclusive_boundary(self):
        t = dict(group='g', domain='TRANSPORT', scope={}, family='LOSS', start=0, end=300,
                 firstFinalized=300, recovered=300)
        d = dict(t, id='d', start=600, end=900, detected=600)
        result = match_episodes([t], [d], 300)
        self.assertEqual((result['tp'], result['fn']), (0, 1))
        self.assertEqual(result['detectionDelaySeconds'], [])

    def test_nested_truth_is_rejected_before_builder_or_scoring(self):
        for key in ('label', 'scenarioId', 'expectedCause', 'futureOutcome'):
            with self.assertRaises(ValueError): reject_truth({'evidence':[{'nested':{key:'bad'}}]})
        reject_truth({'window':{'id':'opaque'}, 'evidence':[]})


class StudyIntegrityTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.folder = tempfile.TemporaryDirectory()
        cls.dataset = generate(Path(cls.folder.name) / 'study')

    @classmethod
    def tearDownClass(cls): cls.folder.cleanup()

    def test_generated_splits_are_separated_and_truth_is_external(self):
        manifest, rows, groups, protocol = read_study(self.dataset, self.dataset/'protocol.json')
        self.assertEqual(manifest['windows'], 3360)
        self.assertEqual(len(groups), 280)
        self.assertEqual(set(protocol['validationSeeds']) & set(protocol['testSeeds']), set())
        self.assertNotIn('label', rows[0]['input'])

    def test_checksum_mismatch_is_rejected_before_scoring(self):
        protocol = self.dataset/'protocol.json'
        original = protocol.read_bytes()
        try:
            protocol.write_bytes(original+b' ')
            with self.assertRaisesRegex(ValueError, 'checksum mismatch'): read_study(self.dataset, protocol)
        finally: protocol.write_bytes(original)

    def test_resealed_truth_leakage_and_training_overlap_are_rejected(self):
        inputs, manifest_path = self.dataset/'inputs.json.gz', self.dataset/'manifest.json'
        original, original_manifest = inputs.read_bytes(), manifest_path.read_bytes()
        for kind in ('truth', 'training', 'duplicate'):
            rows = json.loads(gzip.decompress(original))
            if kind == 'truth': rows[0]['input']['context']['label'] = 'FAULT'
            elif kind == 'duplicate': rows[1]['input']['context']['window']['id'] = rows[0]['input']['context']['window']['id']
            else:
                c = next(r['input']['context'] for r in rows if r['input']['context']['baselines'])
                next(iter(c['baselines'].values()))['trainingTo'] = '2027-01-01T00:00:00Z'
            try:
                inputs.write_bytes(gzip.compress(json.dumps(rows).encode(), mtime=0))
                manifest = json.loads(original_manifest); manifest['files']['inputs.json.gz'] = digest(inputs)
                write_json(manifest_path, manifest)
                with self.assertRaises(ValueError, msg=kind): read_study(self.dataset, self.dataset/'protocol.json')
            finally:
                inputs.write_bytes(original); manifest_path.write_bytes(original_manifest)


if __name__ == '__main__': unittest.main()
