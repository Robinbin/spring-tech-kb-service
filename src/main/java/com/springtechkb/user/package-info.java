/**
 * 用户与认证模块 — 注册登录、JWT / OAuth2、技能档案管理。
 *
 * <p>本模块是用户身份与技能状态的唯一权威来源。跨模块仅通过公开的
 * {@link com.springtechkb.user.UserSkillProfile} 读取或记录技能状态。
 * 技能状态按 (user_id, knowledge_point_id) 行级更新，无并发覆盖（使用乐观锁或 UPSERT）。
 *
 * <h2>硬约束</h2>
 * <ul>
 *   <li>H-5: 用户身份不用于语义缓存 key（个性化输出绕过缓存层）</li>
 * </ul>
 */
@ApplicationModule(displayName = "User", allowedDependencies = {})
package com.springtechkb.user;

import org.springframework.modulith.ApplicationModule;
