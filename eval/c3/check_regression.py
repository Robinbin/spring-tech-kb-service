#!/usr/bin/env python3
"""C3 quality gate for a C2 evaluation result."""
from __future__ import annotations

import argparse
import json
import logging
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).parent.parent / "c2"))
from metrics import check_regression

logging.basicConfig(level=logging.INFO, format="%(message)s")
log = logging.getLogger(__name__)

THRESHOLDS: dict[str, float] = {
    "keyword_recall": 0.75,
    "faithfulness": 0.75,
    "citation_correctness": 0.90,
    "version_mismatch_rate": 0.02,
}
CHUNK_THRESHOLDS: dict[str, float] = {
    "context_recall_at5": 0.80,
    "context_precision_at5": 0.70,
    "mrr_at10": 0.65,
}


def _gha_annotation(level: str, message: str) -> None:
    safe_message = message.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
    print(f"::{level}::{safe_message}", flush=True)


def _read_result(path: Path) -> tuple[dict[str, Any], dict[str, Any]]:
    data = json.loads(path.read_text(encoding="utf-8"))
    metrics = data.get("metrics")
    if not isinstance(metrics, dict) or not metrics:
        raise ValueError("Evaluation result must contain a non-empty metrics object")
    if metrics.get("sample_count") != 100:
        raise ValueError("C3 quality gate requires results for all 100 C1 samples")
    thresholds = dict(THRESHOLDS)
    if metrics.get("chunk_id_sample_count", 0) > 0:
        thresholds.update(CHUNK_THRESHOLDS)
    return data, thresholds


def _read_baseline(path: Path) -> dict[str, Any]:
    data = json.loads(path.read_text(encoding="utf-8"))
    metrics = data.get("metrics", data)
    if not isinstance(metrics, dict):
        raise ValueError("Baseline must contain a metrics object")
    return metrics


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("results_path", type=Path)
    parser.add_argument("--baseline", type=Path)
    parser.add_argument("--update-baseline", action="store_true",
                        help="Replace the baseline only after the current result passes thresholds")
    args = parser.parse_args(argv)

    try:
        if not args.results_path.is_file():
            raise ValueError(f"Results file not found: {args.results_path}")
        data, thresholds = _read_result(args.results_path)
        baseline_path = args.baseline
        baseline: dict[str, Any] = {}
        if baseline_path and baseline_path.is_file():
            baseline = _read_baseline(baseline_path)
            log.info("Baseline loaded from %s", baseline_path)
        elif baseline_path:
            log.info("No baseline at %s; applying hard thresholds only", baseline_path)

        if args.update_baseline:
            baseline = {}
        failures = check_regression(data["metrics"], baseline, thresholds)
        if failures:
            _gha_annotation("error", "Eval regression detected — merge blocked")
            for failure in failures:
                _gha_annotation("error", failure)
            return 1

        if args.update_baseline:
            if not baseline_path:
                raise ValueError("--update-baseline requires --baseline")
            baseline_path.parent.mkdir(parents=True, exist_ok=True)
            baseline_path.write_text(json.dumps({
                "updated_at": datetime.now(timezone.utc).isoformat(),
                "source_result": args.results_path.name,
                "metrics": data["metrics"],
            }, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
            log.info("Baseline updated: %s", baseline_path)

        log.info("All required evaluation metrics meet the quality gate.")
        for metric, value in data["metrics"].items():
            if isinstance(value, (int, float)):
                _gha_annotation("notice", f"{metric}={value}")
        return 0
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        _gha_annotation("error", str(exc))
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
