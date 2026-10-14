import copy
import unittest
from scripts.trace_link_domain_contract import read, validate_inventory, validate_domain_contracts


class TraceLinkDomainContractTest(unittest.TestCase):
    def test_reference_contracts_and_bounded_archive(self):
        validate_domain_contracts()

    def test_excluded_city_cannot_enter_reference_model(self):
        geography = read('geography/trace-link-geography-v2.json')
        geography['cities'][-1]['contractCityId'] = 'TIR'
        with self.assertRaises(ValueError):
            validate_inventory(geography, read('topology/trace-link-links-v1.json'))

    def test_reverse_direction_cannot_claim_source_measurement(self):
        topology = read('topology/trace-link-links-v1.json')
        topology['links'][0]['directions'][1]['sourceAvailable'] = True
        with self.assertRaises(ValueError):
            validate_inventory(read('geography/trace-link-geography-v2.json'), topology)

    def test_new_catalogue_cannot_activate_legacy_authority(self):
        geography = copy.deepcopy(read('geography/trace-link-geography-v2.json'))
        geography['activation']['status'] = 'ACTIVE'
        with self.assertRaises(ValueError):
            validate_inventory(geography, read('topology/trace-link-links-v1.json'))
