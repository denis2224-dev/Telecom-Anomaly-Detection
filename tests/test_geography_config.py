import unittest

from scripts.check_geography_config import validate_config


def rendered(enabled="false", effective_from="2026-01-01T00:00:00Z"):
    environment = {
        "TELECOM_GEOGRAPHY_ENABLED": enabled,
        "TELECOM_GEOGRAPHY_EFFECTIVE_FROM": effective_from,
    }
    return {
        "services": {
            "event-generator": {"environment": environment.copy()},
            "processor": {"environment": environment.copy()},
        }
    }


class GeographyConfigTest(unittest.TestCase):
    def test_matching_configuration_is_accepted(self):
        self.assertEqual([], validate_config(rendered()))

    def test_mismatched_activation_is_rejected(self):
        config = rendered(enabled="true")
        config["services"]["processor"]["environment"]["TELECOM_GEOGRAPHY_ENABLED"] = "false"
        self.assertIn(
            "event-generator and processor geography settings do not match",
            validate_config(config),
        )

    def test_malformed_effective_from_is_rejected(self):
        errors = validate_config(rendered(effective_from="not-a-timestamp"))
        self.assertIn(
            "TELECOM_GEOGRAPHY_EFFECTIVE_FROM must be an ISO-8601 timestamp",
            errors,
        )

    def test_timezone_is_required(self):
        errors = validate_config(rendered(effective_from="2026-01-01T00:00:00"))
        self.assertIn(
            "TELECOM_GEOGRAPHY_EFFECTIVE_FROM must include a timezone",
            errors,
        )


if __name__ == "__main__":
    unittest.main()
