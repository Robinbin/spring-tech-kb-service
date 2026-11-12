/**
 * LLM 网关模块 — 封装三档模型路由、两级超时熔断、Token 计量。
 *
 * <p>本模块是 AI 能力的唯一出入口。业务模块只依赖本模块的公开接口，
 * 不直接调用任何 LLM SDK。模型 ID 与厂商绑定只存在于配置文件中。
 *
 * <h2>公开 API</h2>
 * <ul>
 *   <li>{@link com.springtechkb.ai.ChatTier} — 三档路由枚举</li>
 *   <li>{@link com.springtechkb.ai.RagChatGateway} — 搜索模块使用的流式模型抽象</li>
 *   <li>{@link com.springtechkb.ai.StructuredGenerationGateway} — 原生 JSON Schema 输出、校验与有界重试</li>
 *   <li>{@link com.springtechkb.ai.PromptCatalog} — 版本化 Prompt 的模块间契约</li>
 *   <li>{@link com.springtechkb.ai.GenerationTelemetry} — 元数据追踪与 Prompt 版本关联，不导出内容</li>
 *   <li>{@link com.springtechkb.ai.QueryClassificationGateway} — 轻量意图分类，lite 模型单次同步调用</li>
 * </ul>
 *
 * <h2>硬约束</h2>
 * <ul>
 *   <li>H-4: 超时两级 — TTFT 5s 计入熔断，整体流 90–120s 不计入</li>
 *   <li>H-7: Token 按 input / output / cache_read 三列分别计量</li>
 *   <li>H-12: Prompt 前缀顺序稳定（system → tools → 知识上下文 → 用户问题）</li>
 * </ul>
 */
@ApplicationModule(displayName = "AI Gateway", allowedDependencies = {})
package com.springtechkb.ai;

import org.springframework.modulith.ApplicationModule;
