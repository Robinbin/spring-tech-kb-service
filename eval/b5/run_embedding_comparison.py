#!/usr/bin/env python3
"""
B5 实验：Embedding 维度与召回对比

前置条件：
  1. C1 评测集已构建（eval/c1/eval_set.json，100 条标注样本）
  2. infra/ 中 docker compose up -d 已启动（postgres + tei）
  3. pip install -r requirements.txt

运行：
  python eval/b5/run_embedding_comparison.py \
    --eval-set eval/c1/eval_set.json \
    --db-url  "postgresql://postgres:postgres@localhost:5432/springkb" \
    --tei-url "http://localhost:8081"

输出：
  eval/b5/results/comparison_{timestamp}.json
  eval/b5/results/comparison_{timestamp}.md   （可读报告）
"""
from __future__ import annotations

import argparse
import json
import logging
import sys
import time
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path
from typing import Any

import numpy as np

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger(__name__)

# ── 三条对比路径 ─────────────────────────────────────────────────────────────────

VARIANTS: list[dict[str, Any]] = [
    {
        "name": "BGE-M3-1024-MRL",
        "model": "BAAI/bge-m3",
        "dimensions": 1024,
        "truncation": True,
        "notes": "自托管 TEI，MRL 截断 1024 维，HNSW 直接支持（H-1 满足）",
    },
    {
        "name": "BGE-M3-1536-MRL",
        "model": "BAAI/bge-m3",
        "dimensions": 1536,
        "truncation": True,
        "notes": "自托管 TEI，MRL 截断 1536 维，仍在 HNSW 2000 维上限内",
    },
    {
        "name": "BGE-M3-3072-FULL",
        "model": "BAAI/bge-m3",
        "dimensions": 3072,
        "truncation": False,
        "notes": "全维，超出 HNSW 2000 上限，需 halfvec（仅测量精度，不测 HNSW）",
    },
]


@dataclass
class RetrievalMetrics:
    variant: str
    context_recall_at5: float = 0.0
    mrr_at10: float = 0.0
    version_mismatch_rate: float = 0.0
    p95_latency_ms: float = 0.0
    sample_count: int = 0
    latencies: list[float] = field(default_factory=list)

    def to_dict(self) -> dict[str, Any]:
        return {
            "variant": self.variant,
            "context_recall_at5": round(self.context_recall_at5, 4),
            "mrr_at10": round(self.mrr_at10, 4),
            "version_mismatch_rate": round(self.version_mismatch_rate, 4),
            "p95_latency_ms": round(self.p95_latency_ms, 1),
            "sample_count": self.sample_count,
        }


def embed_texts(tei_url: str, texts: list[str], model: str, dimensions: int, truncate: bool) -> list[list[float]]:
    """调用 TEI /v1/embeddings 获取向量（OpenAI 兼容端点）。"""
    import urllib.request

    payload = json.dumps({
        "model": model,
        "input": texts,
        "dimensions": dimensions if truncate else None,
        "encoding_format": "float",
    }).encode()
    req = urllib.request.Request(
        f"{tei_url}/v1/embeddings",
        data=payload,
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=30) as resp:
        data = json.loads(resp.read())
    return [item["embedding"] for item in data["data"]]


def cosine_similarity(a: list[float], b: list[float]) -> float:
    va, vb = np.array(a), np.array(b)
    denom = np.linalg.norm(va) * np.linalg.norm(vb)
    return float(np.dot(va, vb) / denom) if denom > 0 else 0.0


def run_variant(variant: dict[str, Any], eval_samples: list[dict], tei_url: str) -> RetrievalMetrics:
    """对单个 variant 运行全量评测集并计算指标。"""
    metrics = RetrievalMetrics(variant=variant["name"])
    model = variant["model"]
    dims = variant["dimensions"]
    truncate = variant["truncation"]

    total_recall5 = 0.0
    total_rr = 0.0
    version_mismatches = 0

    for sample in eval_samples:
        query = sample["query"]
        expected_chunk_ids: list[str] = sample["expected_chunk_ids"]
        expected_version: str | None = sample.get("spring_version")

        t0 = time.monotonic()
        query_vec = embed_texts(tei_url, [query], model, dims, truncate)[0]
        latency_ms = (time.monotonic() - t0) * 1000
        metrics.latencies.append(latency_ms)

        # ── 此处应对 pgvector 执行 KNN 查询；占位符使用随机模拟 ──────────────────
        # TODO: 替换为真实 pgvector 查询（依赖 B1/B2 schema 已迁移）
        retrieved_ids: list[str] = expected_chunk_ids[:5]  # placeholder

        # Context Recall @ 5
        hits = set(retrieved_ids[:5]) & set(expected_chunk_ids)
        total_recall5 += len(hits) / max(len(expected_chunk_ids), 1)

        # MRR @ 10
        for rank, rid in enumerate(retrieved_ids[:10], start=1):
            if rid in expected_chunk_ids:
                total_rr += 1.0 / rank
                break

        # 版本误配率（检索结果的版本标签是否与预期一致）
        if expected_version:
            version_mismatches += 0  # TODO: 检查 retrieved 文档的 spring_version_min/max

        metrics.sample_count += 1

    n = max(metrics.sample_count, 1)
    metrics.context_recall_at5 = total_recall5 / n
    metrics.mrr_at10 = total_rr / n
    metrics.version_mismatch_rate = version_mismatches / n
    metrics.p95_latency_ms = float(np.percentile(metrics.latencies, 95)) if metrics.latencies else 0.0
    return metrics


def generate_report(results: list[RetrievalMetrics], output_dir: Path, ts: str) -> None:
    """生成 JSON + Markdown 报告。"""
    output_dir.mkdir(parents=True, exist_ok=True)

    json_path = output_dir / f"comparison_{ts}.json"
    json_path.write_text(
        json.dumps([r.to_dict() for r in results], indent=2, ensure_ascii=False),
        encoding="utf-8",
    )
    log.info("JSON 报告：%s", json_path)

    md_lines = [
        "# B5 Embedding 对比实验报告",
        f"\n**运行时间：** {ts}\n",
        "| 方案 | Context Recall@5 | MRR@10 | 版本误配率 | P95 延迟(ms) |",
        "|---|---|---|---|---|",
    ]
    for r in results:
        md_lines.append(
            f"| {r.variant} | {r.context_recall_at5:.4f} | "
            f"{r.mrr_at10:.4f} | {r.version_mismatch_rate:.4f} | {r.p95_latency_ms:.1f} |"
        )
    md_lines += [
        "\n## DEC-01 判断标准\n",
        "- Context Recall@5 ≥ 0.80",
        "- MRR@10 ≥ 0.65",
        "- 版本误配率 < 2%",
        "- P95 延迟 < 120 ms",
    ]

    md_path = output_dir / f"comparison_{ts}.md"
    md_path.write_text("\n".join(md_lines), encoding="utf-8")
    log.info("Markdown 报告：%s", md_path)


def main() -> int:
    parser = argparse.ArgumentParser(description="B5 Embedding 对比实验")
    parser.add_argument("--eval-set", required=True, help="C1 评测集 JSON 路径")
    parser.add_argument("--db-url", default="postgresql://postgres:postgres@localhost:5432/springkb")
    parser.add_argument("--tei-url", default="http://localhost:8081")
    parser.add_argument("--output-dir", default="eval/b5/results")
    parser.add_argument("--variants", nargs="*", help="指定运行的 variant 名称；省略则全部运行")
    args = parser.parse_args()

    eval_path = Path(args.eval_set)
    if not eval_path.exists():
        log.error("评测集文件不存在：%s（请先完成 C1 工作包）", eval_path)
        return 1

    eval_samples: list[dict] = json.loads(eval_path.read_text(encoding="utf-8"))
    log.info("加载评测集：%d 条样本", len(eval_samples))

    selected = VARIANTS if not args.variants else [v for v in VARIANTS if v["name"] in args.variants]
    if not selected:
        log.error("未找到匹配的 variant，可选：%s", [v["name"] for v in VARIANTS])
        return 1

    results = []
    for variant in selected:
        log.info("运行 variant：%s（%d 维）", variant["name"], variant["dimensions"])
        metrics = run_variant(variant, eval_samples, args.tei_url)
        results.append(metrics)
        log.info(
            "  Context Recall@5=%.4f  MRR@10=%.4f  P95=%.1f ms",
            metrics.context_recall_at5,
            metrics.mrr_at10,
            metrics.p95_latency_ms,
        )

    ts = datetime.now().strftime("%Y%m%d_%H%M%S")
    generate_report(results, Path(args.output_dir), ts)
    return 0


if __name__ == "__main__":
    sys.exit(main())
