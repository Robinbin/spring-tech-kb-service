/**
 * 学习规划模块 — 结构化学习计划生成、任务看板、自适应进度调整。（Phase 2）
 *
 * <p>基于知识图谱 DAG 与用户技能状态，由结构化 AI 输出生成并持久化拓扑合法的学习计划；
 * 提供任务进度更新与带审计记录的自适应复习任务。
 *
 * <h2>硬约束</h2>
 * <ul>
 *   <li>H-6: 学习计划禁止进语义缓存（个性化内容不可跨用户复用）</li>
 * </ul>
 */
@ApplicationModule(displayName = "Learning", allowedDependencies = {"user", "knowledge", "ai", "search"})
package com.springtechkb.learning;

import org.springframework.modulith.ApplicationModule;
