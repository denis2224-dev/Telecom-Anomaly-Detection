import csv
from copy import deepcopy
from datetime import datetime, timezone
import json
from pathlib import Path
import sys
import tempfile
import unittest

ROOT=Path(__file__).resolve().parents[3]
sys.path[:0]=[str(ROOT/'services/ml-service'),str(ROOT/'services/ml-service/training')]


class RealEvaluationTests(unittest.TestCase):
    def fixture(self, directory):
        from generate_history import make_window
        window=make_window('SMS',datetime(2027,6,7,tzinfo=timezone.utc),9999999)
        features=directory/'features.jsonl'; labels=directory/'labels.csv'
        features.write_text(json.dumps(window)+'\n')
        labels.write_text('scopeId,windowStart,label,faultFamily,operatingProfile,severity\n'+
                          window['scopeId']+','+window['windowStart']+',NORMAL,,nominal,0\n')
        return window,features,labels
    def test_frozen_scoring_provenance_and_small_sample_cannot_pass(self):
        from app.validation.sms_real_data import evaluate
        from app.inference.sms_classifier import load_classifier, score
        with tempfile.TemporaryDirectory() as name:
            root=Path(name); window,features,labels=self.fixture(root)
            report=evaluate(features,labels,ROOT/'services/ml-service/candidate-models/sms-supervised-v1-2','test')
            prediction=score(window,load_classifier(ROOT/'services/ml-service/candidate-models/sms-supervised-v1-2'))['detection']
            self.assertEqual(report['overall']['fp'],int(prediction))
            self.assertEqual(report['acceptanceStatus'],'INSUFFICIENT_COVERAGE')
            self.assertEqual(report['missingCoverage']['unlabelledFeatures'],0)
            self.assertEqual(report['threshold'],.55)
            self.assertEqual(len(report['provenance']['featuresSha256']),64)
    def test_duplicate_labels_features_incompatible_baselines_and_nonfinite_rejected(self):
        from app.validation.sms_real_data import evaluate
        package=ROOT/'services/ml-service/candidate-models/sms-supervised-v1-2'
        with tempfile.TemporaryDirectory() as name:
            root=Path(name); window,features,labels=self.fixture(root)
            label_bytes=labels.read_bytes(); feature_bytes=features.read_bytes()
            labels.write_bytes(label_bytes+label_bytes.splitlines(keepends=True)[1])
            with self.assertRaisesRegex(ValueError,'Duplicate label'): evaluate(features,labels,package,'test')
            labels.write_bytes(label_bytes)
            features.write_bytes(feature_bytes*2)
            with self.assertRaisesRegex(ValueError,'Duplicate feature'): evaluate(features,labels,package,'test')
            for patch in ({'featureValues':[float('nan')]*6},{'baselineVersion':'wrong'},{'featureNames':[]},{'label':'FAULT'},
                          {'topologyVersion':'wrong'},{'scopeId':'UNKNOWN-SMS'}):
                features.write_text(json.dumps(dict(window,**patch))+'\n')
                with self.assertRaises(ValueError): evaluate(features,labels,package,'test')
            bad=deepcopy(window); bad['kpis'][0]['baseline']=1000
            features.write_text(json.dumps(bad)+'\n')
            with self.assertRaisesRegex(ValueError,'baseline'): evaluate(features,labels,package,'test')
    def test_reports_are_immutable_and_include_timing_and_coverage(self):
        from app.validation.sms_real_data import evaluate,write_report
        with tempfile.TemporaryDirectory() as name:
            root=Path(name); _,features,labels=self.fixture(root)
            report=evaluate(features,labels,dataset_id='report-test')
            output=root/'report.json'
            write_report(report,output)
            original=output.read_bytes()
            self.assertIn('Inference timing',output.with_suffix('.md').read_text())
            self.assertIn('Required coverage',output.with_suffix('.md').read_text())
            with self.assertRaises(FileExistsError): write_report(report,output)
            self.assertEqual(original,output.read_bytes())
    def test_metrics_gates_require_each_family_and_each_healthy_profile(self):
        from app.validation.sms_real_data import metrics, acceptance
        from app.validation.sms_real_data import FAMILIES, PROFILES
        rows=[dict(label='FAULT',faultFamily=family,operatingProfile='nominal',severity='1',detection=True)
              for family in FAMILIES for _ in range(100)]
        rows += [dict(label='NORMAL',faultFamily='',operatingProfile=profile,severity='0',detection=False)
                 for profile in PROFILES for _ in range(1000)]
        result=metrics(rows)
        self.assertEqual(acceptance(result,dict(unlabelledFeatures=0,labelsWithoutFeatures=0)), 'PASSED')
        rows[0]['detection']=False
        self.assertEqual(metrics(rows)['overall']['fn'],1)
        for row in rows:
            if row['faultFamily']==FAMILIES[0]: row['detection']=False
        self.assertEqual(acceptance(metrics(rows),dict(unlabelledFeatures=0,labelsWithoutFeatures=0)), 'FAILED_METRICS')

    def test_aggregated_observation_builder_matches_canonical_input_and_is_immutable(self):
        from app.validation.build_sms_features import build
        from app.validation.sms_real_data import validate_window
        with tempfile.TemporaryDirectory() as name:
            root=Path(name); source=root/'observations.jsonl'; output=root/'features.jsonl'
            observations=[json.loads((ROOT/'contracts/fixtures/observations'/f'{fixture}.json').read_bytes())
                          for fixture in ('normal-sms','normal-smsc')]
            source.write_text(''.join(json.dumps(event)+'\n' for event in observations))
            self.assertEqual(build(source,output),1)
            validate_window(json.loads(output.read_text()))
            with self.assertRaises(FileExistsError): build(source,output)
            source.write_text(source.read_text()+json.dumps(observations[0])+'\n')
            with self.assertRaisesRegex(ValueError,'Ambiguous'): build(source,root/'ambiguous.jsonl')
