"""Shared Day 1 authority fixtures and contract negatives; no live acceptance claims."""
import copy
from pathlib import Path
import sys
import unittest
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
from geography_contract import (validate_day1_contracts, validate_catalogue, validate_coverage,
                               coverage_snapshot, window_id, resolve, authority_scopes, ROOT, read_json)
from observation_contract import ObservationBatch, validate_observation
from jsonschema import ValidationError

class GeographyContractTests(unittest.TestCase):
    def setUp(self):
        self.catalogue = read_json(ROOT/'contracts/geography/demo-geography-v1.json')
        self.authority = read_json(ROOT/'contracts/topology/geographic-scopes-v2.json')
        self.events = read_json(ROOT/'contracts/fixtures/geography/complete-city-observations-v1.json')

    def test_all_shared_goldens_and_rejections(self):
        validate_day1_contracts()

    def test_unauthorized_sources_wrong_service_and_city(self):
        scopes = authority_scopes(self.authority)
        for index, changes in [(0,{'sourceId':'VOLTE-ADAPTER'}),(1,{'sourceId':'IMS-A'}),
                               (0,{'service':'SMS'}),(1,{'nodeId':'IMS-MD-BAL-01'})]:
            with self.subTest(changes=changes), self.assertRaises((ValueError,ValidationError)):
                validate_observation(dict(self.events[index],**changes),scopes)

    def test_duplicate_content_conflict_does_not_change_identity(self):
        batch=ObservationBatch(authority_scopes(self.authority))
        event=copy.deepcopy(self.events[0])
        self.assertEqual('ACCEPTED',batch.accept(event))
        self.assertEqual('DUPLICATE',batch.accept(copy.deepcopy(event)))
        changed=copy.deepcopy(event);changed['metrics']['sip503Count']=1
        with self.assertRaisesRegex(ValueError,'CONFLICT'): batch.accept(changed)
        self.assertEqual('DUPLICATE',batch.accept(event))

    def test_strict_authority_invalid_inventory_and_legacy_preservation(self):
        for mode in ['duplicate-scope','duplicate-node','reporter','legacy','extra','source-collision']:
            authority=copy.deepcopy(self.authority)
            if mode=='duplicate-scope':authority['scopes'].append(authority['scopes'][2])
            if mode=='duplicate-node':authority['scopes'][2]['nodes'].append(authority['scopes'][2]['nodes'][0])
            if mode=='reporter':authority['scopes'][2]['nodes'][1]['sourceId']=authority['scopes'][2]['nodes'][0]['sourceId']
            if mode=='legacy':authority['scopes'][0]['serviceSourceId']='CHANGED'
            if mode=='extra':authority['scopes'][2]['cityId']='CHI'
            if mode=='source-collision':authority['scopes'][2]['serviceSourceId']=authority['scopes'][2]['nodes'][0]['sourceId']
            with self.subTest(mode=mode),self.assertRaises(ValueError):validate_catalogue(self.catalogue,authority)

    def test_legacy_resolution_and_wrong_service_fail_closed(self):
        self.assertEqual('IMS-A',resolve(self.catalogue,self.authority,'VOLTE-MD-CENTRAL','VOLTE_IMS')['nodeId'])
        self.assertEqual('SMSC-A',resolve(self.catalogue,self.authority,'SMS-MD-ROUTE-A','SMS_SMSC')['nodeId'])
        self.assertEqual('TRANSPORT-A',resolve(self.catalogue,self.authority,'SMS-MD-ROUTE-A','SMS_TRANSPORT')['nodeId'])
        with self.assertRaisesRegex(ValueError,'Wrong service role'):resolve(self.catalogue,self.authority,'VOLTE-MD-CHI','SMS_SMSC')

    def test_coverage_rejects_wrong_window_scope_and_conflict(self):
        start=self.events[0]['windowStart']
        for receipts in [[dict(self.events[0],scopeId='VOLTE-MD-BAL')],
                         [dict(self.events[0],windowStart='2026-09-15T08:01:00Z',windowEnd='2026-09-15T08:02:00Z',emittedAt='2026-09-15T08:02:00Z')],
                         [self.events[0],dict(self.events[0],emittedAt='2026-09-15T08:01:01Z')]]:
            with self.subTest(receipts=receipts),self.assertRaises(ValueError):
                coverage_snapshot(self.catalogue,self.authority,'VOLTE-MD-CHI',start,receipts)

    def test_coverage_identity_and_sets_reject_inconsistent_payloads(self):
        value=coverage_snapshot(self.catalogue,self.authority,'VOLTE-MD-CHI',self.events[0]['windowStart'],[])
        for field,new in [('coverageId','0'*64),('topologyVersion','wrong'),('service','SMS'),
                          ('expectedSourceIds',[]),('sourceIssues',[]),('receivedSourceIds',['UNAUTHORIZED']),
                          ('usableSourceIds',value['expectedSourceIds'])]:
            modified=copy.deepcopy(value);modified[field]=new
            with self.subTest(field=field),self.assertRaises((ValueError,ValidationError)):
                validate_coverage(modified,self.catalogue,self.authority)

    def test_activation_version_and_unknown_fields(self):
        for field,new in [('topologyVersion','wrong'),('synthetic',False),('unknown',True),
                          ('activation',{'status':'ACTIVE','effectiveFrom':None})]:
            modified=copy.deepcopy(self.catalogue);modified[field]=new
            with self.subTest(field=field),self.assertRaises((ValueError,ValidationError)):
                validate_catalogue(modified,self.authority)

    def test_coverage_rejects_valid_looking_hash_for_wrong_feature_window_identity(self):
        value = coverage_snapshot(self.catalogue, self.authority, 'VOLTE-MD-CHI', self.events[0]['windowStart'], [])
        validate_coverage(value, self.catalogue, self.authority)
        modified = copy.deepcopy(value)
        modified['windowId'] = '0' * 64
        with self.assertRaisesRegex(ValueError, '^Coverage window identity mismatch$'):
            validate_coverage(modified, self.catalogue, self.authority)

    def test_window_identity_matches_existing_finalized_feature_fixture(self):
        feature = read_json(ROOT / 'contracts/fixtures/features/voice-worked-v2.json')
        self.assertEqual(2, feature['featureVersion'])
        self.assertEqual('VOLTE-MD-CENTRAL', feature['scopeId'])
        self.assertEqual('2026-09-15T08:00:00Z', feature['windowStart'])
        self.assertEqual(feature['windowId'], window_id(feature['scopeId'], feature['windowStart']))
