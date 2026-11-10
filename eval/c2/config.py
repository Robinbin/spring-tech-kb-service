"""C2 评测配置。"""
from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path

ROOT = Path(__file__).parent.parent.parent  # project root


@dataclass
class EvalConfig:
    # ── 数据路径 ──────────────────────────────────────────────────────────────────
    eval_set_path: Path = ROOT / "eval" / "c1" / "eval_set.json"
    baseline_dir: Path = ROOT / "eval" / "c3" / "baseline"
    results_dir: Path = ROOT / "eval" / "c2" / "results"

    # ── Spring Boot 服务端点 ──────────────────────────────────────────────────────
    # 当 RAG 服务（E3）就绪后指向：http://localhost:8080
    service_base_url: str = field(
        default_factory=lambda: os.environ.get("EVAL_SERVICE_URL", "http://localhost:8080")
    )
    search_endpoint: str = "/api/search"       # E1：混合检索
    rag_endpoint: str = "/api/chat/rag"        # E3：同步 JSON 或 token/citation/done SSE

    # ── 检索参数 ──────────────────────────────────────────────────────────────────
    top_k: int = 5           # Context Precision/Recall@k 的 k
    mrr_k: int = 10          # MRR@k 的 k
    request_timeout_seconds: int = 30

    # ── LLM Judge 参数 ────────────────────────────────────────────────────────────
    # 裁判模型固定（不可通过 CLI 参数修改），保证跨时间的评分一致性
    judge_model: str = "claude-haiku-4-5-20251001"
    judge_api_key: str = field(
        default_factory=lambda: os.environ.get("ANTHROPIC_API_KEY", "")
    )
    judge_max_tokens: int = 512
    judge_temperature: float = 0.0  # deterministic scoring

    # ── 阈值：低于此值触发 C3 回归失败 ────────────────────────────────────────────
    thresholds: dict[str, float] = field(default_factory=lambda: {
        "keyword_recall":        0.75,
        "context_recall_at5":    0.80,
        "context_precision_at5": 0.70,
        "mrr_at10":              0.65,
        "faithfulness":          0.75,
        "citation_correctness":  0.90,
        "version_mismatch_rate": 0.02,
    })

    # ── 评测模式 ──────────────────────────────────────────────────────────────────
    # online: 调用真实搜索与 RAG 服务
    # snapshot: 重放此前捕获的真实搜索与 RAG 响应
    mode: str = field(
        default_factory=lambda: os.environ.get("EVAL_MODE", "online")
    )
