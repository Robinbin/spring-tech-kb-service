"""C2 retrieval and answer-quality metrics."""
from __future__ import annotations

import math
import re
from dataclasses import dataclass, field
from typing import Any


@dataclass
class SampleResult:
    sample_id: str
    query: str
    intent: str
    spring_module: str
    version_sensitive: bool
    retrieved_chunk_ids: list[str] = field(default_factory=list)
    retrieved_contents: list[str] = field(default_factory=list)
    retrieved_versions: list[str] = field(default_factory=list)
    retrieved_sources: list[str] = field(default_factory=list)
    citations: list[str] = field(default_factory=list)
    expected_chunk_ids: list[str] = field(default_factory=list)
    expected_keywords: list[str] = field(default_factory=list)
    spring_version: str | None = None
    generated_answer: str = ""
    faithfulness_score: float | None = None
    answer_relevance: float | None = None
    context_recall: float = 0.0
    context_precision: float = 0.0
    reciprocal_rank: float = 0.0
    keyword_recall: float = 0.0
    version_mismatch: bool = False
    citation_correctness: float = 0.0


def compute_context_recall(retrieved_ids: list[str], expected_ids: list[str], k: int) -> float:
    """Fraction of expected chunks present in the top-k retrieved chunks."""
    if not expected_ids:
        return 0.0
    top_k = set(retrieved_ids[:k])
    return sum(1 for expected_id in expected_ids if expected_id in top_k) / len(expected_ids)


def compute_context_precision(retrieved_ids: list[str], expected_ids: list[str], k: int) -> float:
    """Fraction of top-k results that are annotated as relevant."""
    top_k = retrieved_ids[:k]
    if not top_k:
        return 0.0
    expected = set(expected_ids)
    return sum(1 for result_id in top_k if result_id in expected) / len(top_k)


def compute_mrr(retrieved_ids: list[str], expected_ids: list[str], k: int) -> float:
    """Reciprocal rank of the first relevant chunk."""
    expected = set(expected_ids)
    for rank, result_id in enumerate(retrieved_ids[:k], start=1):
        if result_id in expected:
            return 1.0 / rank
    return 0.0


def compute_keyword_recall(retrieved_contents: list[str], expected_keywords: list[str]) -> float:
    """Fraction of expected keywords found in the actual retrieved content."""
    if not expected_keywords:
        return 0.0
    combined = " ".join(retrieved_contents).casefold()
    return sum(1 for keyword in expected_keywords if keyword.casefold() in combined) / len(expected_keywords)


def _version_parts(version: str) -> list[str]:
    normalized = version.casefold().replace("spring boot", "").replace("spring", "").replace("boot", "")
    return re.findall(r"\d+|x", normalized)


def _version_matches(expected: str, actual: str) -> bool:
    expected_parts = _version_parts(expected)
    actual_parts = _version_parts(actual)
    if not expected_parts or not actual_parts or len(actual_parts) < len(expected_parts):
        return False
    return all(wanted == "x" or wanted == actual_parts[index]
               for index, wanted in enumerate(expected_parts))


def compute_version_mismatch(retrieved_versions: list[str], expected_version: str | None) -> bool:
    """Flag version-sensitive results that lack or violate the expected version label."""
    if not expected_version:
        return False
    if not retrieved_versions:
        return True
    return any(not version or not _version_matches(expected_version, version)
               for version in retrieved_versions)


def compute_citation_correctness(citations: list[str], retrieved_ids: list[str],
                                 retrieved_sources: list[str]) -> float:
    """Return 1 only when every emitted citation points to a retrieved result/source."""
    if not citations:
        return 0.0
    valid_references = set(retrieved_ids) | set(retrieved_sources)
    if not valid_references:
        return 0.0
    return float(all(citation in valid_references for citation in citations))


def aggregate_metrics(results: list[SampleResult], top_k: int = 5, mrr_k: int = 10) -> dict[str, Any]:
    if not results:
        raise ValueError("Cannot aggregate an empty evaluation result set")

    def mean(values: list[float]) -> float:
        return sum(values) / len(values) if values else 0.0

    annotated = [result for result in results if result.expected_chunk_ids]
    version_sensitive = [result for result in results if result.version_sensitive]
    judged = [result for result in results if result.faithfulness_score is not None]

    metrics: dict[str, Any] = {
        "sample_count": len(results),
        "version_sensitive_count": len(version_sensitive),
        "chunk_id_sample_count": len(annotated),
        "eval_mode": "chunk_id" if annotated else "keyword_fallback",
        "keyword_recall": mean([result.keyword_recall for result in results]),
        "version_mismatch_rate": mean([float(result.version_mismatch) for result in version_sensitive]),
        "citation_correctness": mean([result.citation_correctness for result in results]),
    }
    if annotated:
        metrics.update({
            "context_recall_at5": mean([result.context_recall for result in annotated]),
            "context_precision_at5": mean([result.context_precision for result in annotated]),
            "mrr_at10": mean([result.reciprocal_rank for result in annotated]),
        })
    if judged:
        metrics["faithfulness"] = mean([float(result.faithfulness_score) for result in judged])
        metrics["answer_relevance"] = mean([float(result.answer_relevance) for result in judged])
        metrics["judged_count"] = len(judged)

    metrics["by_intent"] = {
        intent: {
            "count": len(group),
            "keyword_recall": mean([result.keyword_recall for result in group]),
            "context_recall_at5": (
                mean([result.context_recall for result in group if result.expected_chunk_ids])
                if any(result.expected_chunk_ids for result in group) else None
            ),
        }
        for intent in sorted({result.intent for result in results})
        if (group := [result for result in results if result.intent == intent])
    }
    return metrics


def check_regression(current: dict[str, Any], baseline: dict[str, Any],
                     thresholds: dict[str, float]) -> list[str]:
    """Check hard thresholds and baseline regressions; absent metrics fail closed."""
    failures: list[str] = []
    lower_is_better = {"version_mismatch_rate"}
    for metric, threshold in thresholds.items():
        current_value = current.get(metric)
        if not isinstance(current_value, (int, float)):
            failures.append(f"{metric}: required metric missing")
            continue
        if not math.isfinite(float(current_value)) or not 0 <= current_value <= 1:
            failures.append(f"{metric}: value must be finite and between 0 and 1")
            continue
        if metric in lower_is_better:
            if current_value > threshold:
                failures.append(f"{metric}: {current_value:.4f} > threshold {threshold}")
            baseline_value = baseline.get(metric)
            if isinstance(baseline_value, (int, float)) and current_value > max(
                threshold, baseline_value + 0.02
            ):
                failures.append(
                    f"{metric}: {current_value:.4f} regressed from baseline "
                    f"{baseline_value:.4f} by more than 2 percentage points"
                )
        else:
            if current_value < threshold:
                failures.append(f"{metric}: {current_value:.4f} < threshold {threshold}")
            baseline_value = baseline.get(metric)
            if isinstance(baseline_value, (int, float)) and baseline_value > 0 and (
                current_value < baseline_value * 0.98
            ):
                failures.append(
                    f"{metric}: {current_value:.4f} dropped from baseline "
                    f"{baseline_value:.4f} by more than 2%"
                )
    return failures
