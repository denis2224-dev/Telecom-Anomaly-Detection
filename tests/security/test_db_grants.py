"""Discoverable unittest adapter for security grant and boundary checks."""

import importlib.util
from pathlib import Path
import unittest

TARGET = Path(__file__).resolve().parent / "check-db-grants.py"
spec = importlib.util.spec_from_file_location("check_db_grants", TARGET)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

DatabaseGrantTests = module.DatabaseGrantTests

if __name__ == "__main__":
    unittest.main()
