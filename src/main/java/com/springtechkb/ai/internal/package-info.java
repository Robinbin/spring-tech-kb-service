/**
 * AI Gateway 模块内部实现（模块私有，禁止外部模块直接依赖）。
 *
 * <p>包含显式配置的 LITE/STANDARD/EXPERT Anthropic 模型与按 tier 路由、
 * TTFT 熔断及整体流超时、Redis token 配额和 LLM 调用计量。Spring AI 的
 * {@code spring.ai.model.chat} 是单选开关，故多档模型由 {@code AiModelConfiguration}
 * 显式构造，而非依赖模型自动配置。
 */
package com.springtechkb.ai.internal;
