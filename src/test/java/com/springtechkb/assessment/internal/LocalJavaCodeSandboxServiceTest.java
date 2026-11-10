package com.springtechkb.assessment.internal;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LocalJavaCodeSandboxServiceTest {

    @Test
    void executesPassingCodeAgainstTests() {
        AssessmentPhase3Properties properties = new AssessmentPhase3Properties();
        LocalJavaCodeSandboxService sandbox = new LocalJavaCodeSandboxService(properties);
        String source = """
                public class Solution {
                    public static String solve(String input) {
                        return Integer.toString(Integer.parseInt(input.trim()) * 2);
                    }
                }
                """;
        CodeSandboxService.SandboxResult result = sandbox.evaluateJava(source, List.of(
                new CodeSandboxService.TestCase("2", "4", false, 1.0),
                new CodeSandboxService.TestCase("5", "10", true, 1.0)));
        assertThat(result.accepted()).isTrue();
        assertThat(result.compileSuccess()).isTrue();
        assertThat(result.tests()).hasSize(2);
        assertThat(result.tests()).allMatch(CodeSandboxService.TestResult::passed);
    }

    @Test
    void returnsPolicyViolationsWithoutExecution() {
        AssessmentPhase3Properties properties = new AssessmentPhase3Properties();
        LocalJavaCodeSandboxService sandbox = new LocalJavaCodeSandboxService(properties);
        String source = """
                public class Solution {
                    public static String solve(String input) throws Exception {
                        return Runtime.getRuntime().exec("whoami").toString();
                    }
                }
                """;
        CodeSandboxService.SandboxResult result = sandbox.evaluateJava(source, List.of(
                new CodeSandboxService.TestCase("", "", false, 1.0)));
        assertThat(result.accepted()).isFalse();
        assertThat(result.policyViolations()).isNotEmpty();
        assertThat(result.tests()).isEmpty();
    }
}
