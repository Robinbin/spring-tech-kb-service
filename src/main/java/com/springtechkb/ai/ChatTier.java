package com.springtechkb.ai;

/**
 * 三档 LLM 路由策略枚举。
 *
 * <p>业务模块通过 {@code @Qualifier} 注入对应档位的 {@code ChatModel}，
 * 不直接依赖任何 LLM SDK 或具体模型 ID。模型 ID 只存在于 application.yml 中。
 *
 * <ul>
 *   <li>{@link #LITE} — 轻量档：意图分类、改写、批量打分（Haiku 级）</li>
 *   <li>{@link #STANDARD} — 标准档：常规问答与学习计划生成（Sonnet 级）</li>
 *   <li>{@link #EXPERT} — 重推理档：源码级推理、主观题批改（Opus 级）</li>
 * </ul>
 */
public enum ChatTier {

    /** 意图分类、改写、批量打分 — Haiku 级（低延迟、低成本）。 */
    LITE,

    /** 常规问答与学习计划生成 — Sonnet 级（均衡）。 */
    STANDARD,

    /** 源码级推理与主观题批改 — Opus 级（高质量、高成本）。 */
    EXPERT
}
