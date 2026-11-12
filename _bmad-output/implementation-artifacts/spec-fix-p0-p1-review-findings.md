---
title: '修复代码审查中的 P0/P1 问题'
type: 'bugfix'
created: '2026-11-12'
status: 'done'
route: 'oneshot'
review_loop_iteration: 0
context:
  - '{project-root}/_bmad-output/planning-artifacts/architecture/architecture-spring-tech-kb-service-2026-10-30/ARCHITECTURE-SPINE.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**问题：** 最近的代码审查发现 Jackson mapper 类型可能导致服务无法启动、assessment/learning 越过 knowledge 模块内部边界依赖，以及未验证的转发头允许客户端规避限流。

**方案：** 使用 Spring Boot 4 配置的 Jackson 3 mapper；将共享闭包校验器放入 knowledge 的公开根包；限流默认基于 socket peer 地址，避免信任未经验证的客户端转发头。

</frozen-after-approval>

## Implementation Notes

- `KnowledgeIngestionService` 与 `SemanticAnswerCache` 改为注入 Boot 4 自动配置的 Jackson 3 `JsonMapper`，并统一使用 Jackson 3 类型。
- 将 `ClosureValidator` 移至 `knowledge` 根包，assessment 与 learning 改为依赖公开类型。
- 网关限流改用 socket peer 地址，忽略未经验证的 `X-Forwarded-For` 和 `X-Real-IP`。
- `mvn -q '-Dtest=ApiGatewayFilterTest,EpicECoreTest,ModularityTests' test` 通过。
- 代码盲审未报告显著问题。完整上下文启动检查仍被未改动的 `ApiRateLimiter` 无默认构造器问题阻断；这不属于本次审查项，未扩展修复范围。
