#!/usr/bin/env python3
"""Run C2 against live search/RAG endpoints or a captured result snapshot.

The live API contract is GET /api/search?q=...&topK=... returning
{"results":[{"id","content","springVersion","source":{"id","url"}}]} and
POST /api/chat/rag with {"query":...} returning
{"answer":"...","citations":[{"id":"..."}]} or an SSE stream with token,
citation, done, and error events.

Snapshot format: {"schema_version":"c2-snapshot-v1","results":{
  "c1-001":{"retrieved":[...],"answer":"...","citations":[...]}}}.
"""
from __future__ import annotations

import argparse
import json
import logging
import math
import sys
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).parent))
sys.path.insert(0, str(Path(__file__).parent.parent / "c1"))

from config import EvalConfig
from metrics import (
    SampleResult,
    aggregate_metrics,
    compute_citation_correctness,
    compute_context_precision,
    compute_context_recall,
    compute_keyword_recall,
    compute_mrr,
    compute_version_mismatch,
)
from llm_judge import judge
from validate_eval_set import DEFAULT_SCHEMA, validate_dataset

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger(__name__)


def load_eval_set(path: Path) -> list[dict[str, Any]]:
    validation_errors = validate_dataset(path, DEFAULT_SCHEMA)
    if validation_errors:
        raise ValueError("Invalid C1 eval set: " + "; ".join(validation_errors))
    data = json.loads(path.read_text(encoding="utf-8"))
    samples = data.get("samples")
    if not isinstance(samples, list) or not samples:
        raise ValueError(f"Eval set must contain a non-empty samples array: {path}")
    return samples


def _request_json(request: urllib.request.Request, timeout: int) -> dict[str, Any]:
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            data = json.loads(response.read())
    except urllib.error.URLError as exc:
        raise RuntimeError(f"Evaluation endpoint request failed: {exc}") from exc
    except json.JSONDecodeError as exc:
        raise RuntimeError("Evaluation endpoint returned invalid JSON") from exc
    if not isinstance(data, dict):
        raise RuntimeError("Evaluation endpoint must return a JSON object")
    return data


def search_online(query: str, config: EvalConfig) -> list[dict[str, Any]]:
    service = urllib.parse.urlparse(config.service_base_url)
    if service.scheme not in ("http", "https") or not service.netloc:
        raise RuntimeError("EVAL_SERVICE_URL must be an absolute HTTP(S) URL")
    query_string = urllib.parse.urlencode({"q": query, "topK": config.top_k})
    url = f"{config.service_base_url.rstrip('/')}{config.search_endpoint}?{query_string}"
    data = _request_json(urllib.request.Request(url), config.request_timeout_seconds)
    results = data.get("results")
    if not isinstance(results, list):
        raise RuntimeError("Search endpoint response must contain a results array")
    if any(not isinstance(result, dict) for result in results):
        raise RuntimeError("Each search result must be a JSON object")
    return results


def rag_answer_online(query: str, config: EvalConfig) -> tuple[str, list[Any]]:
    service = urllib.parse.urlparse(config.service_base_url)
    if service.scheme not in ("http", "https") or not service.netloc:
        raise RuntimeError("EVAL_SERVICE_URL must be an absolute HTTP(S) URL")
    url = f"{config.service_base_url.rstrip('/')}{config.rag_endpoint}"
    payload = json.dumps({"query": query}).encode("utf-8")
    request = urllib.request.Request(
        url,
        data=payload,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=config.request_timeout_seconds) as response:
            content_type = response.headers.get_content_type()
            body = response.read()
    except urllib.error.URLError as exc:
        raise RuntimeError(f"Evaluation endpoint request failed: {exc}") from exc
    if content_type == "text/event-stream":
        answer, citations = _parse_rag_sse(body.decode("utf-8"))
    else:
        try:
            data = json.loads(body)
        except json.JSONDecodeError as exc:
            raise RuntimeError("RAG endpoint returned invalid JSON") from exc
        if not isinstance(data, dict):
            raise RuntimeError("RAG endpoint must return a JSON object or an SSE stream")
        answer = data.get("answer", data.get("content"))
        citations = data.get("citations", [])
    if not isinstance(answer, str) or not answer.strip():
        raise RuntimeError("RAG endpoint response must contain a non-empty answer")
    if not isinstance(citations, list):
        raise RuntimeError("RAG endpoint citations must be an array")
    return answer, citations


def _parse_rag_sse(payload: str) -> tuple[str, list[Any]]:
    answer_parts: list[str] = []
    citations: list[Any] = []
    event_name = "message"
    data_lines: list[str] = []

    def consume_event() -> None:
        nonlocal event_name, data_lines
        if not data_lines:
            event_name = "message"
            return
        raw_data = "\n".join(data_lines)
        try:
            event_data: Any = json.loads(raw_data)
        except json.JSONDecodeError:
            event_data = raw_data
        if event_name == "error":
            raise RuntimeError(f"RAG stream returned an error event: {event_data}")
        if event_name == "citation":
            citations.extend(event_data if isinstance(event_data, list) else [event_data])
        elif event_name == "done":
            if isinstance(event_data, dict):
                if not answer_parts and isinstance(event_data.get("answer"), str):
                    answer_parts.append(event_data["answer"])
                if isinstance(event_data.get("citations"), list):
                    citations.extend(event_data["citations"])
        elif isinstance(event_data, str):
            answer_parts.append(event_data)
        elif isinstance(event_data, dict):
            token = event_data.get("token", event_data.get("text", event_data.get("content")))
            if isinstance(token, str):
                answer_parts.append(token)
            if isinstance(event_data.get("citations"), list):
                citations.extend(event_data["citations"])
        event_name = "message"
        data_lines = []

    for line in payload.splitlines():
        if not line:
            consume_event()
        elif line.startswith(":"):
            continue
        elif line.startswith("event:"):
            event_name = line[6:].strip()
        elif line.startswith("data:"):
            data_lines.append(line[5:].lstrip())
    consume_event()
    return "".join(answer_parts), citations


def _text(value: Any) -> str:
    return str(value) if value is not None else ""


def _citation_reference(citation: Any) -> str:
    if isinstance(citation, str):
        return citation
    if isinstance(citation, dict):
        for key in ("id", "chunkId", "sourceId", "url", "sourceUrl"):
            if citation.get(key):
                return str(citation[key])
    raise RuntimeError("Each citation must be a string or contain an id/source URL")


def _result_values(results: list[dict[str, Any]]) -> tuple[list[str], list[str], list[str], list[str]]:
    ids, contents, versions, sources = [], [], [], []
    for item in results:
        if not isinstance(item, dict):
            raise RuntimeError("Each search result must be a JSON object")
        result_id = item.get("chunkId", item.get("id"))
        content = item.get("content")
        if not isinstance(result_id, str) or not result_id:
            raise RuntimeError("Each search result must contain a non-empty id or chunkId")
        if not isinstance(content, str):
            raise RuntimeError(f"Search result {result_id} must contain string content")
        source = item.get("source") or {}
        if not isinstance(source, dict):
            source = {}
        ids.append(result_id)
        contents.append(content)
        versions.append(_text(
            item.get("springVersion")
            or item.get("spring_version")
            or item.get("version")
            or item.get("springVersionMin")
            or item.get("spring_version_min")
            or source.get("version")
        ))
        for value in (source.get("id"), source.get("url"), item.get("sourceUrl")):
            if value:
                sources.append(str(value))
    return ids, contents, versions, sources


def _snapshot_sample(snapshot: dict[str, Any], sample_id: str) -> dict[str, Any]:
    entries = snapshot.get("results")
    if not isinstance(entries, dict) or sample_id not in entries:
        raise ValueError(f"Snapshot is missing sample {sample_id}")
    result = entries[sample_id]
    if not isinstance(result, dict):
        raise ValueError(f"Snapshot result for {sample_id} must be an object")
    retrieved = result.get("retrieved", [])
    citations = result.get("citations", [])
    if not isinstance(retrieved, list) or not isinstance(citations, list):
        raise ValueError(f"Snapshot retrieved/citations for {sample_id} must be arrays")
    return {
        "results": retrieved,
        "answer": result.get("answer", ""),
        "citations": citations,
        "faithfulness": result.get("faithfulness"),
        "answer_relevance": result.get("answer_relevance"),
    }


def evaluate_sample(sample: dict[str, Any], config: EvalConfig,
                    snapshot: dict[str, Any] | None = None,
                    skip_judge: bool = False) -> SampleResult:
    result = SampleResult(
        sample_id=sample["id"],
        query=sample["query"],
        intent=sample["intent"],
        spring_module=sample["spring_module"],
        version_sensitive=sample.get("version_sensitive", False),
        expected_chunk_ids=sample.get("chunk_ids", []),
        expected_keywords=sample.get("expected_chunk_keywords", []),
        spring_version=sample.get("spring_version"),
    )

    if snapshot is None:
        retrieved = search_online(result.query, config)
        result.generated_answer, citations = rag_answer_online(result.query, config)
        captured_ratings = None
    else:
        captured = _snapshot_sample(snapshot, result.sample_id)
        retrieved = captured["results"]
        result.generated_answer = captured["answer"]
        citations = captured["citations"]
        captured_ratings = captured

    if not isinstance(result.generated_answer, str):
        raise RuntimeError(f"Answer for {result.sample_id} must be a string")
    ids, contents, versions, sources = _result_values(retrieved)
    result.retrieved_chunk_ids = ids
    result.retrieved_contents = contents
    result.retrieved_versions = versions
    result.retrieved_sources = sources
    result.citations = [_citation_reference(citation) for citation in citations]

    if result.expected_chunk_ids:
        result.context_recall = compute_context_recall(ids, result.expected_chunk_ids, config.top_k)
        result.context_precision = compute_context_precision(ids, result.expected_chunk_ids, config.top_k)
        result.reciprocal_rank = compute_mrr(ids, result.expected_chunk_ids, config.mrr_k)
    result.keyword_recall = compute_keyword_recall(contents, result.expected_keywords)
    result.version_mismatch = (
        result.version_sensitive
        and compute_version_mismatch(versions, result.spring_version)
    )
    result.citation_correctness = compute_citation_correctness(
        result.citations, ids, sources
    )

    if skip_judge and captured_ratings is not None:
        faithfulness = captured_ratings["faithfulness"]
        answer_relevance = captured_ratings["answer_relevance"]
        if faithfulness is not None or answer_relevance is not None:
            if not isinstance(faithfulness, (int, float)) or not isinstance(answer_relevance, (int, float)):
                raise ValueError(f"Snapshot judge scores for {result.sample_id} must both be numeric")
            if (not math.isfinite(faithfulness) or not math.isfinite(answer_relevance)
                    or not 0 <= faithfulness <= 1 or not 0 <= answer_relevance <= 1):
                raise ValueError(f"Snapshot judge scores for {result.sample_id} must be between 0 and 1")
            result.faithfulness_score = float(faithfulness)
            result.answer_relevance = float(answer_relevance)
    elif result.generated_answer and not skip_judge:
        if not config.judge_api_key:
            raise RuntimeError("ANTHROPIC_API_KEY is required for LLM judging; use --skip-judge to omit it")
        judged = judge(
            query=result.query,
            retrieved_context=contents,
            generated_answer=result.generated_answer,
            expected_answer_points=sample.get("expected_answer_points", []),
            api_key=config.judge_api_key,
            max_tokens=config.judge_max_tokens,
        )
        result.faithfulness_score = judged.faithfulness
        result.answer_relevance = judged.answer_relevance
    return result


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="C2 RAG evaluation runner")
    parser.add_argument("--eval-set", type=Path)
    parser.add_argument("--mode", choices=("online", "snapshot"), default=None)
    parser.add_argument("--snapshot", type=Path, help="Captured search/RAG responses for snapshot mode")
    parser.add_argument("--intent", nargs="*",
                        choices=("concept", "source_code", "comparison", "best_practice"))
    parser.add_argument("--skip-judge", action="store_true",
                        help="Skip Claude judging; faithfulness/relevance metrics will be absent")
    parser.add_argument("--output-dir", type=Path)
    args = parser.parse_args(argv)

    config = EvalConfig()
    if args.eval_set:
        config.eval_set_path = args.eval_set
    if args.output_dir:
        config.results_dir = args.output_dir
    mode = args.mode or "online"
    if mode == "snapshot" and not args.snapshot:
        parser.error("--snapshot is required in snapshot mode")
    if mode == "online" and args.snapshot:
        parser.error("--snapshot can only be used with --mode snapshot")

    samples = load_eval_set(config.eval_set_path)
    if args.intent:
        samples = [sample for sample in samples if sample["intent"] in args.intent]
    if not samples:
        raise ValueError("No evaluation samples selected")
    snapshot = None
    if args.snapshot:
        snapshot = json.loads(args.snapshot.read_text(encoding="utf-8"))
        if snapshot.get("schema_version") != "c2-snapshot-v1":
            raise ValueError("Snapshot schema_version must be c2-snapshot-v1")

    log.info("Evaluating %d samples in %s mode", len(samples), mode)
    results = [
        evaluate_sample(sample, config, snapshot=snapshot, skip_judge=args.skip_judge)
        for sample in samples
    ]
    metrics = aggregate_metrics(results, config.top_k, config.mrr_k)
    timestamp = datetime.now(timezone.utc).isoformat()
    config.results_dir.mkdir(parents=True, exist_ok=True)
    result_path = config.results_dir / f"eval_{datetime.now().strftime('%Y%m%d_%H%M%S')}.json"
    result_path.write_text(json.dumps({
        "timestamp": timestamp,
        "config_mode": mode,
        "metrics": metrics,
        "sample_results": [
            {
                "id": result.sample_id,
                "intent": result.intent,
                "retrieved_chunk_ids": result.retrieved_chunk_ids,
                "keyword_recall": result.keyword_recall,
                "context_recall": result.context_recall,
                "context_precision": result.context_precision,
                "mrr": result.reciprocal_rank,
                "version_mismatch": result.version_mismatch,
                "citations": result.citations,
                "citation_correctness": result.citation_correctness,
                "faithfulness": result.faithfulness_score,
                "answer_relevance": result.answer_relevance,
            }
            for result in results
        ],
    }, indent=2, ensure_ascii=False), encoding="utf-8")
    log.info("Evaluation metrics: %s", json.dumps(metrics, ensure_ascii=False))
    log.info("Results saved to %s", result_path)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError, RuntimeError) as exc:
        log.error("Evaluation failed: %s", exc)
        raise SystemExit(1) from exc
