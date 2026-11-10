#!/usr/bin/env python3
"""Validate the C1 dataset schema and its required coverage."""
from __future__ import annotations

import argparse
import json
import sys
from collections import Counter
from pathlib import Path
from typing import Any

from jsonschema import Draft7Validator, FormatChecker

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_DATASET = ROOT / "eval" / "c1" / "eval_set.json"
DEFAULT_SCHEMA = ROOT / "eval" / "c1" / "schema.json"
EXPECTED_INTENTS = {"concept", "source_code", "comparison", "best_practice"}


def validate_dataset(dataset_path: Path, schema_path: Path) -> list[str]:
    dataset: Any = json.loads(dataset_path.read_text(encoding="utf-8"))
    schema: Any = json.loads(schema_path.read_text(encoding="utf-8"))
    validator = Draft7Validator(schema, format_checker=FormatChecker())
    errors = [
        f"{'.'.join(map(str, error.absolute_path)) or '<root>'}: {error.message}"
        for error in validator.iter_errors(dataset)
    ]
    if errors:
        return errors

    samples = dataset["samples"]
    intent_counts = Counter(sample["intent"] for sample in samples)
    ids = [sample["id"] for sample in samples]

    if len(samples) != 100:
        errors.append(f"expected exactly 100 samples, found {len(samples)}")
    if len(ids) != len(set(ids)):
        errors.append("sample IDs must be unique")
    if dataset.get("total_samples") != len(samples):
        errors.append("total_samples must match the samples array length")
    if dataset.get("intent_distribution") != dict(intent_counts):
        errors.append("intent_distribution must match the samples array")
    if set(intent_counts) != EXPECTED_INTENTS or any(intent_counts[name] != 25 for name in EXPECTED_INTENTS):
        errors.append(f"expected 25 samples for each intent: {sorted(EXPECTED_INTENTS)}")

    sensitive_count = sum(sample["version_sensitive"] for sample in samples)
    if sensitive_count != 19:
        errors.append(f"expected 19 version-sensitive samples, found {sensitive_count}")
    if dataset.get("version_sensitive_count") != sensitive_count:
        errors.append("version_sensitive_count must match the samples array")
    missing_versions = [
        sample["id"]
        for sample in samples
        if sample["version_sensitive"] and not sample.get("spring_version")
    ]
    if missing_versions:
        errors.append(f"version-sensitive samples require spring_version: {missing_versions}")

    return errors


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", type=Path, default=DEFAULT_DATASET)
    parser.add_argument("--schema", type=Path, default=DEFAULT_SCHEMA)
    args = parser.parse_args(argv)
    errors = validate_dataset(args.dataset, args.schema)
    if errors:
        for error in errors:
            print(f"ERROR: {error}", file=sys.stderr)
        return 1
    print(f"Validated 100 C1 samples across {len(EXPECTED_INTENTS)} intents.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
