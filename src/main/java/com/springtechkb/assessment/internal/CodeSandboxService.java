package com.springtechkb.assessment.internal;

import java.util.List;

interface CodeSandboxService {
    SandboxResult evaluateJava(String sourceCode, List<TestCase> testCases);

    record TestCase(String input, String expectedOutput, boolean hidden, double weight) {}

    record SandboxResult(
            boolean accepted,
            boolean compileSuccess,
            boolean runtimeSuccess,
            String compileOutput,
            List<String> policyViolations,
            List<TestResult> tests) {}

    record TestResult(int index, boolean passed, String actualOutput, String expectedOutput, String error) {}
}
