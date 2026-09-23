"""Canonical detection contracts must remain compatible with the existing API."""

import copy
from datetime import datetime, timedelta, timezone
import hashlib
import json
import unittest

from jsonschema import Draft202012Validator
import test_shared_contract as shared
from test_utils import FORMAT_CHECKER, ROOT, read_json


class DetectionContractTests(unittest.TestCase):
    def test_policy_values_and_schema(self):
        policy = read_json(ROOT / 'contracts/policies/service-rules-v2.json')
        schema = read_json(ROOT / 'contracts/policies/service-rules-v2.schema.json')
        validator = Draft202012Validator(schema)
        validator.validate(policy)
        self.assertEqual(policy['voice'], {
            'minAttempts': 100, 'dropPpStrictlyGreaterThan': 1.0,
            'recoveryDropPpAtMost': 0.5, 'highExtraFailuresAtLeast': 50,
            'criticalExtraFailuresAtLeast': 200,
            'imsCapacityCpuPctAtLeast': 90, 'imsCapacitySip503CountAtLeast': 50,
            'imsCapacityAccessSrDropPpAtMost': 0.5,
        })
        self.assertEqual(policy['openAfterBreachedWindows'], 2)
        self.assertEqual(policy['recoverAfterHealthyWindows'], 3)
        bad = copy.deepcopy(policy)
        bad['voice']['minAttempts'] = 0
        self.assertFalse(validator.is_valid(bad))

    def test_current_detection_validates_without_changing_api(self):
        schema = read_json(ROOT / 'contracts/detections/service-detection-v2.schema.json')
        detection = read_json(ROOT / 'contracts/fixtures/incidents/incident-open.json')['latestDetection']
        Draft202012Validator(schema, format_checker=FORMAT_CHECKER).validate(detection)
        shared.SharedContractTests.setUpClass()
        shared.SharedContractTests.detection_validator.validate(detection)

    def test_illustrative_detection_matches_feature_evidence_and_public_api(self):
        detection = read_json(ROOT / 'contracts/fixtures/detections/voice-open-illustrative-v2.json')
        feature = read_json(ROOT / 'contracts/fixtures/features/voice-worked-v2.json')
        schema = read_json(ROOT / 'contracts/detections/service-detection-v2.schema.json')
        validator = Draft202012Validator(schema, format_checker=FORMAT_CHECKER)
        validator.validate(detection)
        shared.SharedContractTests.setUpClass()
        shared.SharedContractTests.detection_validator.validate(detection)
        self.assertEqual(detection['kpis'], feature['kpis'])
        self.assertEqual(detection['evidence'][0]['sourceEventIds'], feature['sourceEventIds'])
        self.assertEqual(detection['impact']['extraFailedAttempts'], 53)
        self.assertEqual(detection['mlStatus'], 'UNAVAILABLE')
        canonical = lambda values: json.dumps(values, ensure_ascii=True, separators=(',', ':')).encode()
        self.assertEqual(detection['correlationKey'], hashlib.sha256(canonical([
            detection['service'], detection['scopeId'], detection['anomalyType'],
            detection['rulesetVersion']])).hexdigest())
        self.assertEqual(detection['episodeId'], hashlib.sha256(canonical([
            detection['correlationKey'], detection['firstObservedAt']])).hexdigest())
        self.assertEqual(detection['detectionId'], hashlib.sha256(canonical([
            detection['episodeId'], detection['windowStart'], detection['phase'],
            detection['rulesetVersion']])).hexdigest())
        self.assertFalse(validator.is_valid(dict(detection, anomalyRank=0)))
        self.assertFalse(validator.is_valid(dict(detection, mlStatus='OK')))
        wrong = copy.deepcopy(detection)
        wrong['impact']['uniqueSubscribers'] = 53
        self.assertFalse(validator.is_valid(wrong))

    def test_feature_schema_rejects_wrong_order_and_fake_ineligible_vector(self):
        schema = read_json(ROOT / 'contracts/features/service-feature-window-v2.schema.json')
        names = read_json(ROOT / 'contracts/features/feature-order-v2.json')['models']['VOLTE']
        payload = dict(schemaVersion=2, featureVersion=2, windowId='test-window',
                       scopeId='VOLTE-MD-CENTRAL', service='VOLTE',
                       windowStart='2026-09-15T08:00:00Z', windowEnd='2026-09-15T08:01:00Z',
                       quality='COMPLETE', baselineVersion='baseline-v2', topologyVersion='2-baseline',
                       kpis=[], featureNames=names, featureValues=[0, 0, 0, 0, 0, 35],
                       mlEligible=True, sourceEventIds=[])
        validator = Draft202012Validator(schema, format_checker=FORMAT_CHECKER)
        validator.validate(payload)
        shared.SharedContractTests.setUpClass()
        shared.SharedContractTests.api_validator(shared.SharedContractTests.schemas['ServiceKpiWindow']).validate(payload)
        self.assertFalse(validator.is_valid(dict(payload, featureNames=list(reversed(names)))))
        self.assertFalse(validator.is_valid(dict(payload, quality='INCOMPLETE')))
        self.assertFalse(validator.is_valid(dict(payload, mlEligible=False)))
        validator.validate(dict(payload, mlEligible=False, featureNames=[], featureValues=[]))

    def _validate_volte_scenario(self, scenario):
        policy_contract = read_json(ROOT / 'contracts/policies/service-rules-v2.json')
        baseline_contract = read_json(ROOT / 'contracts/baselines/demo-baseline-v2.json')
        topology_contract = read_json(ROOT / 'contracts/topology/demo-scopes-v2.json')
        feature_contract = read_json(ROOT / 'contracts/features/feature-order-v2.json')

        # 1. Topology validation: scope/service/sources must match topology
        self.assertEqual(scenario['topologyVersion'], topology_contract['topologyVersion'])
        scope = next((s for s in topology_contract['scopes'] if s['scopeId'] == scenario['scopeId']), None)
        self.assertIsNotNone(scope, f"Scope {scenario['scopeId']} not found in topology")
        self.assertEqual(scenario['service'], scope['service'])
        self.assertEqual(scope['serviceSourceId'], 'VOLTE-ADAPTER')

        topology_node_sources = {n['sourceId'] for n in scope['nodes']}
        emitted_sources = {s['sourceId'] for s in scenario['generator']['emittedSources']}
        allowed_not_emitted = {s['sourceId'] for s in scenario['generator']['allowedTopologySourcesNotEmitted']}

        self.assertIn(scope['serviceSourceId'], emitted_sources)
        self.assertTrue(emitted_sources.issubset(topology_node_sources | {scope['serviceSourceId']}))
        self.assertTrue(allowed_not_emitted.issubset(topology_node_sources))
        self.assertIn('IMS-A', emitted_sources)
        self.assertIn('TRANSPORT-A', allowed_not_emitted)
        self.assertNotIn('TRANSPORT-A', emitted_sources)

        # 2. Policy values validation: retained policy values must match service-rules-v2.json
        self.assertEqual(scenario['rulesetVersion'], policy_contract['rulesetVersion'])
        self.assertEqual(scenario['windowDurationSec'], policy_contract['windowSec'])
        self.assertEqual(scenario['allowedLatenessSec'], policy_contract['allowedLatenessSec'])

        vp = policy_contract['voice']
        sp = scenario['policy']
        self.assertEqual(sp['minAttempts'], vp['minAttempts'])
        self.assertEqual(sp['dropPpStrictlyGreaterThan'], vp['dropPpStrictlyGreaterThan'])
        self.assertEqual(sp['recoveryDropPpAtMost'], vp['recoveryDropPpAtMost'])
        self.assertEqual(sp['highExtraFailuresAtLeast'], vp['highExtraFailuresAtLeast'])
        self.assertEqual(sp['criticalExtraFailuresAtLeast'], vp['criticalExtraFailuresAtLeast'])
        self.assertEqual(sp['openAfterBreachedWindows'], policy_contract['openAfterBreachedWindows'])
        self.assertEqual(sp['recoverAfterHealthyWindows'], policy_contract['recoverAfterHealthyWindows'])

        # 3. Baseline reference validation: baseline reference must match demo-baseline-v2.json
        self.assertEqual(scenario['baselineVersion'], baseline_contract['baselineVersion'])
        baseline_entry = next((b for b in baseline_contract['baselines']
                               if b['scopeId'] == scenario['scopeId'] and b['service'] == scenario['service']), None)
        self.assertIsNotNone(baseline_entry, "Baseline entry for scope/service not found")
        baseline_cssr = baseline_entry['values']['cssrPct']
        self.assertEqual(baseline_cssr, 99.3)

        # 4. Feature contract metadata validation: featureVersion and order metadata
        self.assertEqual(scenario['featureVersion'], feature_contract['featureVersion'])
        volte_features = feature_contract['models']['VOLTE']
        self.assertIn('cssrDeltaPp', volte_features)
        self.assertIn('imsCpuPct', volte_features)
        self.assertIn('packetLossRatio', volte_features)

        ml_semantics = scenario['mlSemantics']
        self.assertFalse(ml_semantics['transportEmitted'])
        self.assertIsNone(ml_semantics['packetLossRatio'])
        self.assertFalse(ml_semantics['mlEligible'])
        self.assertEqual(ml_semantics['featureNames'], [])
        self.assertEqual(ml_semantics['featureValues'], [])
        self.assertEqual(ml_semantics['mlStatus'], 'INSUFFICIENT_DATA')

        # Generator metadata checks
        self.assertEqual(scenario['seed'], 15092026)
        self.assertEqual(scenario['generator']['seed'], 15092026)
        self.assertEqual(scenario['generator']['source'], 'services/event-generator/src/main/java/md/utm/telecom/generator/VoiceScenario.java')

        # 5. Expected window count and contiguous minute offsets
        total_windows = scenario['expectedTotalWindows']
        self.assertEqual(total_windows, 8)
        self.assertEqual(len(scenario['windows']), total_windows)
        offsets = [w['minuteOffset'] for w in scenario['windows']]
        self.assertEqual(offsets, list(range(total_windows)))

        # 8 & 9. Derived timestamps and identity formulas using derived timestamps
        ref_start_str = scenario['referenceStart']
        ref_start = datetime.fromisoformat(ref_start_str.replace('Z', '+00:00'))

        canonical = lambda values: json.dumps(values, ensure_ascii=True, separators=(',', ':')).encode()
        h = lambda values: hashlib.sha256(canonical(values)).hexdigest()

        # Validate correlationKey formula: hash([service, scopeId, anomalyType, rulesetVersion])
        anomaly_type = 'VOLTE_SETUP_DEGRADATION'
        correlation_key = h([
            scenario['service'], scenario['scopeId'], anomaly_type, scenario['rulesetVersion']
        ])
        illustrative = read_json(ROOT / 'contracts/fixtures/detections/voice-open-illustrative-v2.json')
        self.assertEqual(correlation_key, illustrative['correlationKey'])

        active = False
        consecutive_breaches = 0
        consecutive_healthy = 0
        first_breached_window_start = None
        open_detection_window_start = None

        for w in scenario['windows']:
            # Derived timestamps: referenceStart + minuteOffset
            window_start_dt = ref_start + timedelta(minutes=w['minuteOffset'])
            window_start_str = window_start_dt.strftime('%Y-%m-%dT%H:%M:%SZ')

            # Validate windowId formula: hash([scopeId, windowStart, featureVersion])
            derived_window_id = h([scenario['scopeId'], window_start_str, scenario['featureVersion']])
            self.assertEqual(len(derived_window_id), 64)
            if w['minuteOffset'] == 0:
                worked_fixture = read_json(ROOT / 'contracts/fixtures/features/voice-worked-v2.json')
                self.assertEqual(derived_window_id, worked_fixture['windowId'])

            m = w.get('measurements')
            # Determine eligibility per VoiceSetupRule semantics: COMPLETE service quality, measurements present, attempts >= minAttempts
            eligible = (
                w.get('serviceQuality') == 'COMPLETE'
                and m is not None
                and m.get('eligibleAttempts', 0) >= sp['minAttempts']
            )

            if eligible:
                # Minimum attempts enforcement from policy
                self.assertGreaterEqual(m['eligibleAttempts'], sp['minAttempts'])
                # Arithmetic enforcement: attempts = technicalSuccesses + technicalFailures + userOutcomes
                self.assertEqual(m['attempts'], m['technicalSuccesses'] + m['technicalFailures'] + m['userOutcomes'])
                eligible_attempts = m['attempts'] - m['userOutcomes']
                self.assertEqual(m['eligibleAttempts'], eligible_attempts)
                if 'kpis' in w and w['kpis'] and w['kpis'].get('eligibleAttempts') is not None:
                    self.assertEqual(w['kpis']['eligibleAttempts'], eligible_attempts)

                # Calculated CSSR and breach / recovery check against baseline and policy
                calculated_cssr = 100.0 * m['technicalSuccesses'] / eligible_attempts
                if 'kpis' in w and w['kpis'] and w['kpis'].get('cssrPct') is not None:
                    self.assertAlmostEqual(w['kpis']['cssrPct'], calculated_cssr, places=5)

                cssr_drop_pp = baseline_cssr - calculated_cssr
                breached = cssr_drop_pp > sp['dropPpStrictlyGreaterThan']
                healthy = cssr_drop_pp <= sp['recoveryDropPpAtMost']
            else:
                breached = False
                healthy = False

            self.assertEqual(w.get('breached', False), breached)

            if breached:
                if consecutive_breaches == 0:
                    first_breached_window_start = window_start_str
                    self.assertTrue(w.get('candidateStart', False))
                else:
                    self.assertFalse(w.get('candidateStart', False))
                consecutive_breaches += 1
            else:
                consecutive_breaches = 0
                self.assertFalse(w.get('candidateStart', False))

            if healthy:
                consecutive_healthy += 1
            else:
                consecutive_healthy = 0

            # Phase state machine per VoiceEpisode semantics
            if not active:
                if consecutive_breaches >= sp['openAfterBreachedWindows']:
                    expected_phase = 'OPEN'
                    active = True
                    open_detection_window_start = window_start_str
                else:
                    expected_phase = None
            else:
                if not eligible:
                    expected_phase = 'UNKNOWN'
                elif consecutive_healthy >= sp['recoverAfterHealthyWindows']:
                    expected_phase = 'RECOVERY'
                    active = False
                else:
                    expected_phase = 'UPDATE'

            self.assertEqual(w.get('episodePhase'), expected_phase)

            # Metadata assertions for specific phases
            if expected_phase == 'OPEN':
                self.assertEqual(w.get('severity'), 'HIGH')
                self.assertEqual(w.get('sequence'), 1)
                self.assertEqual(w.get('firstObservedAtOffset'), 1)
                self.assertEqual(w.get('mlStatus'), 'INSUFFICIENT_DATA')
            elif expected_phase == 'RECOVERY':
                self.assertEqual(w.get('technicalState'), 'RECOVERED')
                self.assertEqual(w.get('mlStatus'), 'INSUFFICIENT_DATA')
            elif expected_phase == 'UNKNOWN':
                self.assertEqual(w.get('technicalState'), 'UNKNOWN')
                self.assertEqual(w.get('mlStatus'), 'INSUFFICIENT_DATA')
            elif expected_phase == 'UPDATE':
                self.assertEqual(w.get('mlStatus'), 'INSUFFICIENT_DATA')

        # 6. Opening must occur after configured consecutive breaches
        self.assertIsNotNone(first_breached_window_start)
        self.assertIsNotNone(open_detection_window_start)
        first_breach_dt = datetime.fromisoformat(first_breached_window_start.replace('Z', '+00:00'))
        open_dt = datetime.fromisoformat(open_detection_window_start.replace('Z', '+00:00'))
        self.assertEqual(open_dt - first_breach_dt, timedelta(minutes=sp['openAfterBreachedWindows'] - 1))

        # 7 & 9. Identity hash formulas using derived timestamps
        # episodeId = hash([correlationKey, firstBreachedWindowStart])
        episode_id = h([correlation_key, first_breached_window_start])
        # detectionId = hash([episodeId, detectionWindowStart, phase, rulesetVersion])
        open_detection_id = h([
            episode_id, open_detection_window_start, 'OPEN', scenario['rulesetVersion']
        ])
        self.assertEqual(len(episode_id), 64)
        self.assertEqual(len(open_detection_id), 64)

        # 10. Replay expectations
        replay = scenario['replay']
        self.assertEqual(replay['expectedIncidentCount'], 1)
        self.assertEqual(replay['expectedNewIncidents'], 0)
        self.assertEqual(replay['expectedIngestionResult'], 'DUPLICATE')
        self.assertEqual(replay['expectedLatestSequence'], 6)
        self.assertEqual(replay['expectedTechnicalState'], 'RECOVERED')

    def test_volte_first_slice_spec_matches_canonical_contracts(self):
        scenario = read_json(ROOT / 'tests/e2e/scenarios/volte-first-slice.json')
        self._validate_volte_scenario(scenario)

    def test_volte_scenario_rejects_eligible_non_healthy_recovery_mutation(self):
        """Case 1: CSSR drop = 0.7 pp (between 0.5 pp recovery and 1.0 pp breach) is non-healthy and resets recovery."""
        scenario = read_json(ROOT / 'tests/e2e/scenarios/volte-first-slice.json')
        mutated = copy.deepcopy(scenario)
        w5 = mutated['windows'][5]
        # Set consistent counters: attempts=1020, userOutcomes=20 -> eligible=1000
        # technicalSuccesses=986 -> CSSR=98.6%, baseline=99.3% -> drop=0.7 pp
        w5['measurements']['attempts'] = 1020
        w5['measurements']['eligibleAttempts'] = 1000
        w5['measurements']['userOutcomes'] = 20
        w5['measurements']['technicalSuccesses'] = 986
        w5['measurements']['technicalFailures'] = 14
        w5['kpis']['eligibleAttempts'] = 1000
        w5['kpis']['cssrPct'] = 98.6
        w5['breached'] = False
        with self.assertRaises(AssertionError) as ctx:
            self._validate_volte_scenario(mutated)
        self.assertIn("RECOVERY", str(ctx.exception))

    def test_volte_scenario_rejects_incomplete_recovery_window_mutation(self):
        """Case 2: An INCOMPLETE service window during an active episode transitions to UNKNOWN and resets streaks."""
        scenario = read_json(ROOT / 'tests/e2e/scenarios/volte-first-slice.json')
        mutated = copy.deepcopy(scenario)
        mutated['windows'][5]['serviceQuality'] = 'INCOMPLETE'
        mutated['windows'][5]['breached'] = False
        with self.assertRaises(AssertionError) as ctx:
            self._validate_volte_scenario(mutated)
        self.assertIn("UNKNOWN", str(ctx.exception))
