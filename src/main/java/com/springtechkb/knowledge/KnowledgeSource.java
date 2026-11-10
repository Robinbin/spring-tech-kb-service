package com.springtechkb.knowledge;

/**
 * 知识内容来源枚举。
 *
 * <p>每个来源对应 D1 许可清单中的一个准入条目。
 * 不在许可清单内的来源在入库时被拒绝（H-9）。
 */
public enum KnowledgeSource {

    /** spring.io 官方文档（Apache-2.0）。 */
    SPRING_OFFICIAL_DOCS,

    /** Spring 框架 GitHub 仓库 Javadoc 与 README（Apache-2.0）。 */
    SPRING_GITHUB,

    /** Spring Boot / Cloud / Security 等子项目 Release Notes（Apache-2.0）。 */
    SPRING_RELEASE_NOTES,

    /** Baeldung 文章（CC BY-SA 4.0）—— 需逐篇确认许可。 */
    BAELDUNG,

    /** AI 生成内容（须标注来源、检索降权；H-13）。 */
    AI_GENERATED;

    /** Returns true when document body text may be ingested into the knowledge store (H-9). */
    public boolean requiresBodyIngestion() {
        return this != BAELDUNG && this != AI_GENERATED;
    }

    /** Returns true when this source type is permitted to enter the knowledge pipeline at all. */
    public boolean isAllowed() {
        return this != AI_GENERATED;
    }
}
