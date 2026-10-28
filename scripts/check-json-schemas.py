#!/usr/bin/env python3
"""Validate the schema examples emitted by JsonWireConformanceTest against draft 2020-12."""

import json
import sys
import xml.etree.ElementTree as ET
from decimal import Decimal
from pathlib import Path

from jsonschema import Draft202012Validator, validators


# Preserve decimal/exponent integers beyond binary64's exact range when reading fixtures.
def is_integer(checker, value):
    if isinstance(value, bool):
        return False
    if isinstance(value, Decimal):
        return value == value.to_integral_value()
    return isinstance(value, int)


Validator = validators.extend(
    Draft202012Validator,
    type_checker=Draft202012Validator.TYPE_CHECKER.redefine("integer", is_integer),
)


def main():
    report_dir = Path(sys.argv[1] if len(sys.argv) > 1 else "build/test-results/jvmTest")
    groups = 0
    examples = 0
    failures = []
    for report in sorted(report_dir.glob("TEST-*.xml")):
        suite = ET.parse(report).getroot()
        for output in suite.iter("system-out"):
            for line in (output.text or "").splitlines():
                if not line.startswith("JSON_SCHEMA_CASE:"):
                    continue
                case = json.loads(line.removeprefix("JSON_SCHEMA_CASE:"), parse_float=Decimal)
                schema = case["schema"]
                Validator.check_schema(schema)
                validator = Validator(schema)
                groups += 1
                for category, expected in (("valid", True), ("invalid", False)):
                    for instance in case[category]:
                        examples += 1
                        errors = list(validator.iter_errors(instance))
                        if (not errors) != expected:
                            failures.append(
                                f"{case['name']}: expected {category}: {instance!r}; "
                                + "; ".join(error.message for error in errors)
                            )
    if not groups:
        raise SystemExit(f"No JSON Schema examples found in {report_dir}; run ./gradlew jvmTest first")
    if failures:
        raise SystemExit("\n".join(failures))
    print(f"Validated {examples} JSON Schema examples in {groups} groups (draft 2020-12)")


if __name__ == "__main__":
    main()
