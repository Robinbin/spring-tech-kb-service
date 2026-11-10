package com.springtechkb;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/**
 * A2 验收测试 — Spring Modulith 模块边界校验。
 *
 * <p>本测试不启动 Spring 上下文，纯字节码静态分析：
 * <ol>
 *   <li>检测所有七个业务模块均被识别（含 Phase 2 内容生成）</li>
 *   <li>验证所有 {@code allowedDependencies} 约束无违反</li>
 *   <li>验证没有模块直接引用其他模块的 {@code internal} 包</li>
 * </ol>
 *
 * <p>任何跨越 {@code internal} 边界的直接依赖，或违反 {@code allowedDependencies}
 * 的模块调用，都会导致 {@link #moduleBoundariesAreValid()} 抛出异常，从而阻断 CI。
 */
class ModularityTests {

    private static final ApplicationModules MODULES =
            ApplicationModules.of(SpringTechKbApplication.class);

    @Test
    @DisplayName("七个业务模块均被 Spring Modulith 识别")
    void allSevenModulesDetected() {
        List<String> names = MODULES.stream()
                .map(m -> m.getIdentifier().toString())
                .sorted()
                .toList();
        System.out.println("### A2-PROBE detected modules = " + names);
        assertThat(names).containsExactlyInAnyOrder(
                "ai", "assessment", "generation", "knowledge", "learning", "search", "user");
    }

    @Test
    @DisplayName("模块边界合法：无 internal 越权访问，allowedDependencies 约束无违反")
    void moduleBoundariesAreValid() {
        MODULES.verify();
    }
}
