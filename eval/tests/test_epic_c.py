from __future__ import annotations

import json
import sys
import tempfile
import unittest
import urllib.error
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "eval" / "c1"))
sys.path.insert(0, str(ROOT / "eval" / "c2"))
sys.path.insert(0, str(ROOT / "eval" / "c3"))

from config import EvalConfig
from metrics import (
    SampleResult,
    aggregate_metrics,
    check_regression,
    compute_citation_correctness,
    compute_context_precision,
    compute_context_recall,
    compute_keyword_recall,
    compute_mrr,
    compute_version_mismatch,
)
from run_eval import _parse_rag_sse, evaluate_sample, search_online
from validate_eval_set import validate_dataset
from check_regression import main as check_regression_main


class C1DatasetTests(unittest.TestCase):
    def test_committed_dataset_matches_schema_and_coverage(self) -> None:
        self.assertEqual(
            validate_dataset(ROOT / "eval/c1/eval_set.json", ROOT / "eval/c1/schema.json"),
            [],
        )

    def test_dataset_rejects_nonexistent_file(self) -> None:
        with self.assertRaises(FileNotFoundError):
            validate_dataset(ROOT / "missing-eval-set.json", ROOT / "eval/c1/schema.json")


class C2MetricTests(unittest.TestCase):
    def test_retrieval_metrics_use_actual_ranking(self) -> None:
        self.assertEqual(compute_context_recall(["x", "b"], ["a", "b"], 2), 0.5)
        self.assertEqual(compute_context_precision(["x", "b", "a"], ["a", "b"], 2), 0.5)
        self.assertEqual(compute_mrr(["x", "b", "a"], ["a", "b"], 2), 0.5)

    def test_keyword_version_and_citation_metrics(self) -> None:
        self.assertEqual(compute_keyword_recall(["ApplicationContext manages beans"], ["beans"]), 1.0)
        self.assertFalse(compute_version_mismatch(["boot-4.1.2"], "boot-4.x"))
        self.assertTrue(compute_version_mismatch(["boot-3.2.9"], "boot-4.x"))
        self.assertEqual(compute_citation_correctness(["chunk-1"], ["chunk-1"], []), 1.0)
        self.assertEqual(compute_citation_correctness(["unretrieved"], ["chunk-1"], []), 0.0)

    def test_regression_gate_fails_for_missing_required_metric(self) -> None:
        failures = check_regression(
            {"keyword_recall": 0.9},
            {},
            {"keyword_recall": 0.75, "citation_correctness": 0.9},
        )
        self.assertEqual(failures, ["citation_correctness: required metric missing"])

    def test_regression_gate_checks_threshold_and_baseline(self) -> None:
        failures = check_regression(
            {"keyword_recall": 0.80},
            {"keyword_recall": 0.90},
            {"keyword_recall": 0.75},
        )
        self.assertEqual(len(failures), 1)
        self.assertIn("dropped from baseline", failures[0])

    def test_aggregate_metrics_marks_chunk_id_coverage(self) -> None:
        result = SampleResult(
            sample_id="c1-001",
            query="query",
            intent="concept",
            spring_module="core",
            version_sensitive=False,
            expected_chunk_ids=["a"],
            retrieved_chunk_ids=["a"],
            context_recall=1.0,
            context_precision=1.0,
            reciprocal_rank=1.0,
        )
        metrics = aggregate_metrics([result])
        self.assertEqual(metrics["chunk_id_sample_count"], 1)
        self.assertEqual(metrics["context_recall_at5"], 1.0)


class C2RunnerTests(unittest.TestCase):
    def test_online_query_is_url_encoded_and_errors_are_not_hidden(self) -> None:
        config = EvalConfig(service_base_url="http://localhost:8080")

        class FakeResponse:
            def __enter__(self):
                return self

            def __exit__(self, *_):
                return False

            def read(self):
                return b'{"results":[]}'

        with patch("run_eval.urllib.request.urlopen", return_value=FakeResponse()) as urlopen:
            self.assertEqual(search_online("Spring MVC & IoC", config), [])
            self.assertIn("Spring+MVC+%26+IoC", urlopen.call_args.args[0].full_url)

    def test_online_endpoint_failure_is_not_converted_to_empty_results(self) -> None:
        with patch("run_eval.urllib.request.urlopen", side_effect=urllib.error.URLError("offline")):
            with self.assertRaisesRegex(RuntimeError, "request failed"):
                search_online("Spring MVC", EvalConfig())

    def test_sse_rag_response_collects_tokens_and_citations(self) -> None:
        answer, citations = _parse_rag_sse(
            'event: token\ndata: {"text":"Spring "}\n\n'
            'event: token\ndata: {"token":"MVC"}\n\n'
            'event: citation\ndata: {"id":"chunk-1"}\n\n'
            'event: done\ndata: {"answer":"ignored duplicate"}\n\n'
        )
        self.assertEqual(answer, "Spring MVC")
        self.assertEqual(citations, [{"id": "chunk-1"}])

    def test_sse_error_event_is_not_silently_ignored(self) -> None:
        with self.assertRaisesRegex(RuntimeError, "error event"):
            _parse_rag_sse('event: error\ndata: {"message":"failed"}\n\n')

    def test_snapshot_evaluation_uses_observed_results_and_citations(self) -> None:
        sample = {
            "id": "c1-001",
            "query": "What is IoC?",
            "intent": "concept",
            "spring_module": "core",
            "spring_version": None,
            "version_sensitive": False,
            "chunk_ids": ["chunk-1"],
            "expected_chunk_keywords": ["IoC", "container"],
            "expected_answer_points": [],
        }
        snapshot = {
            "schema_version": "c2-snapshot-v1",
            "results": {
                "c1-001": {
                    "retrieved": [{
                        "id": "chunk-1",
                        "content": "The IoC container manages beans.",
                        "springVersion": "6.1.4",
                        "source": {"url": "https://docs.spring.io/"},
                    }],
                    "answer": "The IoC container manages beans.",
                    "citations": [{"id": "chunk-1"}],
                    "faithfulness": 0.9,
                    "answer_relevance": 1.0,
                }
            },
        }
        result = evaluate_sample(sample, EvalConfig(), snapshot=snapshot, skip_judge=True)
        self.assertEqual(result.keyword_recall, 1.0)
        self.assertEqual(result.context_recall, 1.0)
        self.assertEqual(result.citation_correctness, 1.0)
        self.assertEqual(result.faithfulness_score, 0.9)

    def test_snapshot_missing_sample_is_an_error(self) -> None:
        with self.assertRaisesRegex(ValueError, "missing sample c1-001"):
            evaluate_sample(
                {"id": "c1-001", "query": "q", "intent": "concept",
                 "spring_module": "core", "version_sensitive": False},
                EvalConfig(),
                snapshot={"results": {}},
                skip_judge=True,
            )


class C3GateTests(unittest.TestCase):
    def test_gate_blocks_missing_metrics_and_passes_thresholds(self) -> None:
        metrics = {
            "sample_count": 100,
            "keyword_recall": 0.9,
            "faithfulness": 0.9,
            "citation_correctness": 0.95,
            "version_mismatch_rate": 0.0,
            "chunk_id_sample_count": 0,
        }
        with tempfile.TemporaryDirectory() as temp_dir:
            result_path = Path(temp_dir) / "result.json"
            result_path.write_text(json.dumps({"metrics": metrics}), encoding="utf-8")
            self.assertEqual(check_regression_main([str(result_path)]), 0)

            metrics.pop("faithfulness")
            result_path.write_text(json.dumps({"metrics": metrics}), encoding="utf-8")
            self.assertEqual(check_regression_main([str(result_path)]), 1)

    def test_gate_updates_baseline_only_after_passing(self) -> None:
        metrics = {
            "sample_count": 100,
            "keyword_recall": 0.9,
            "faithfulness": 0.9,
            "citation_correctness": 0.95,
            "version_mismatch_rate": 0.0,
            "chunk_id_sample_count": 0,
        }
        with tempfile.TemporaryDirectory() as temp_dir:
            result_path = Path(temp_dir) / "result.json"
            baseline_path = Path(temp_dir) / "baseline" / "latest.json"
            result_path.write_text(json.dumps({"metrics": metrics}), encoding="utf-8")
            self.assertEqual(check_regression_main([
                str(result_path), "--baseline", str(baseline_path), "--update-baseline",
            ]), 0)
            saved = json.loads(baseline_path.read_text(encoding="utf-8"))
            self.assertEqual(saved["metrics"]["sample_count"], 100)

    def test_explicit_baseline_update_rechecks_hard_thresholds(self) -> None:
        metrics = {
            "sample_count": 100,
            "keyword_recall": 0.80,
            "faithfulness": 0.90,
            "citation_correctness": 0.95,
            "version_mismatch_rate": 0.0,
            "chunk_id_sample_count": 0,
        }
        with tempfile.TemporaryDirectory() as temp_dir:
            result_path = Path(temp_dir) / "result.json"
            baseline_path = Path(temp_dir) / "baseline.json"
            baseline_path.write_text(json.dumps({"metrics": {"keyword_recall": 0.95}}), encoding="utf-8")
            result_path.write_text(json.dumps({"metrics": metrics}), encoding="utf-8")
            self.assertEqual(check_regression_main([
                str(result_path), "--baseline", str(baseline_path), "--update-baseline",
            ]), 0)
            saved = json.loads(baseline_path.read_text(encoding="utf-8"))
            self.assertEqual(saved["metrics"]["keyword_recall"], 0.80)


class D5ReviewSeedTests(unittest.TestCase):
    def test_seed_has_150_unique_pending_review_records_across_five_modules(self) -> None:
        seed_path = ROOT / "src/main/resources/knowledge/d5-review-seed.json"
        records = json.loads(seed_path.read_text(encoding="utf-8"))
        self.assertEqual(len(records), 150)
        self.assertEqual(len({record["id"] for record in records}), 150)
        self.assertEqual(
            {module: sum(record["module"] == module for record in records)
             for module in ("ioc", "aop", "mvc", "data", "security")},
            {"ioc": 30, "aop": 30, "mvc": 30, "data": 30, "security": 30},
        )
        self.assertTrue(all(record["review"]["status"] == "PENDING" for record in records))
        self.assertTrue(all(record["review"]["accuracy_score"] is None for record in records))
        self.assertTrue(all(record["source_url"].startswith("https://docs.spring.io/") for record in records))


if __name__ == "__main__":
    unittest.main()
