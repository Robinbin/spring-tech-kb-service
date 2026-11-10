package com.springtechkb.assessment.internal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SandboxPolicyGuardTest {

    @Test
    void rejectsDangerousApis() {
        String code = """
                public class Solution {
                    static String solve(String input) throws Exception {
                        Runtime.getRuntime().exec("calc");
                        return input;
                    }
                }
                """;
        assertThat(SandboxPolicyGuard.validate(code)).isNotEmpty();
    }

    @Test
    void allowsSafeCode() {
        String code = """
                public class Solution {
                    public static String solve(String input) {
                        return input == null ? "" : input.trim();
                    }
                }
                """;
        assertThat(SandboxPolicyGuard.validate(code)).isEmpty();
    }
}
