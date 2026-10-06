import unittest

from scripts.check_geography_alignment import validate_compose, validate_environment


def values(enabled="false", effective_from="2026-01-01T00:00:00Z"):
    return {
        "TELECOM_GEOGRAPHY_ENABLED": enabled,
        "TELECOM_GEOGRAPHY_EFFECTIVE_FROM": effective_from,
    }


def compose(generator=None, processor=None):
    return {
        "services": {
            "event-generator": {"environment": generator or values()},
            "processor": {"environment": processor or values()},
        }
    }


class GeographyAlignmentTest(unittest.TestCase):
    def test_host_defaults_are_valid(self):
        self.assertEqual([], validate_environment(values()))

    def test_host_requires_effective_from(self):
        self.assertTrue(validate_environment(values(effective_from="")))

    def test_compose_rejects_generator_only_activation(self):
        self.assertTrue(
            validate_compose(compose(generator=values(enabled="true")))
        )

    def test_compose_rejects_mismatched_effective_from(self):
        self.assertTrue(
            validate_compose(
                compose(
                    processor=values(effective_from="2026-02-01T00:00:00Z")
                )
            )
        )

    def test_compose_accepts_matching_values(self):
        self.assertEqual([], validate_compose(compose()))


if __name__ == "__main__":
    unittest.main()
