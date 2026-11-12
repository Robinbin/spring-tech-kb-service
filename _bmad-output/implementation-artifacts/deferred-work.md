## Deferred from: code review of dev-baseline-wbs.md (2026-10-30)

- 异步超时/错误回调路径的 in-flight 指标泄漏风险待证实（`src/main/java/com/springtechkb/ApiGatewayFilter.java:92-104`）：需补充容器级 async timeout/error 集成测试，验证 `onTimeout/onError` 后是否仍触发 `onComplete`；当前证据不足以下结论。
