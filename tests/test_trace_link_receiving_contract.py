import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
from trace_link_receiving_contract import validate_receiving_contracts


class TraceLinkReceivingContractTest(unittest.TestCase):
    def test_exact_producer_bytes_independent_decimal_results_and_separate_policies(self):
        self.assertEqual(5, validate_receiving_contracts())
