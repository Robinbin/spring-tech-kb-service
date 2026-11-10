/**
 * 评估与能力追踪模块 — 人工审核选择题诊断与 BKT 知识追踪入口。
 *
 * <p>Phase 2/3 提供冻结题集、答案提交、技能画像更新、AI 题库生成与审核、代码判题、
 * 主观题 Rubric 评分、IRT 自适应推荐与能力报告。
 *
 * <h2>硬约束</h2>
 * <ul>
 *   <li>H-6: 能力报告禁止进语义缓存（个性化内容不可跨用户复用）</li>
 * </ul>
 */
@ApplicationModule(displayName = "Assessment", allowedDependencies = {"user", "knowledge", "ai", "search"})
package com.springtechkb.assessment;

import org.springframework.modulith.ApplicationModule;
