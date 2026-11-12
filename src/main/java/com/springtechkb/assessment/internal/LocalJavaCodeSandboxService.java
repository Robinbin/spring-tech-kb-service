package com.springtechkb.assessment.internal;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
class LocalJavaCodeSandboxService implements CodeSandboxService {
    private final AssessmentPhase3Properties properties;

    LocalJavaCodeSandboxService(AssessmentPhase3Properties properties) {
        this.properties = properties;
    }

    @Override
    public SandboxResult evaluateJava(String sourceCode, List<TestCase> testCases) {
        if (!"local".equalsIgnoreCase(properties.sandbox().strategy())) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Code sandbox strategy is disabled");
        }
        if (sourceCode == null || sourceCode.isBlank() || sourceCode.length() > properties.sandbox().maxSourceChars()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sourceCode is blank or exceeds configured limit");
        }
        if (testCases == null || testCases.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "At least one test case is required");
        }
        List<String> violations = SandboxPolicyGuard.validate(sourceCode);
        if (!violations.isEmpty()) {
            return new SandboxResult(false, false, false, "", violations, List.of());
        }

        Path root = null;
        try {
            root = Files.createTempDirectory("springkb-sandbox-");
            Path solution = root.resolve("Solution.java");
            Files.writeString(solution, sourceCode, StandardCharsets.UTF_8);
            for (int i = 0; i < testCases.size(); i++) {
                Path runner = root.resolve("Runner" + i + ".java");
                Files.writeString(runner, runnerSource(i, testCases.get(i).input()), StandardCharsets.UTF_8);
            }
            Process compile = new ProcessBuilder(buildCompileCommand(testCases.size()))
                    .directory(root.toFile())
                    .redirectErrorStream(true)
                    .start();
            boolean compileDone = compile.waitFor(timeout(properties.sandbox().compileTimeout()), TimeUnit.MILLISECONDS);
            if (!compileDone) {
                compile.destroyForcibly();
                return new SandboxResult(true, false, false, "Compilation timeout", List.of(), List.of());
            }
            String compileOutput = new String(compile.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (compile.exitValue() != 0) {
                return new SandboxResult(true, false, false, compileOutput, List.of(), List.of());
            }

            List<TestResult> results = new ArrayList<>();
            boolean allRuntimeOk = true;
            for (int i = 0; i < testCases.size(); i++) {
                Process run = new ProcessBuilder(buildRunCommand(root, i))
                        .directory(root.toFile())
                        .redirectErrorStream(true)
                        .start();
                boolean runDone = run.waitFor(timeout(properties.sandbox().runTimeout()), TimeUnit.MILLISECONDS);
                if (!runDone) {
                    run.destroyForcibly();
                    results.add(new TestResult(i, false, "", testCases.get(i).expectedOutput(), "Runtime timeout"));
                    allRuntimeOk = false;
                    continue;
                }
                String output = new String(run.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                if (run.exitValue() != 0) {
                    results.add(new TestResult(i, false, output, testCases.get(i).expectedOutput(), "Runtime error"));
                    allRuntimeOk = false;
                    continue;
                }
                boolean passed = output.equals(normalize(testCases.get(i).expectedOutput()));
                results.add(new TestResult(i, passed, output, testCases.get(i).expectedOutput(), null));
            }
            return new SandboxResult(true, true, allRuntimeOk, compileOutput, List.of(), List.copyOf(results));
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Sandbox execution failed", exception);
        } finally {
            if (root != null) deleteQuietly(root);
        }
    }

    private List<String> buildCompileCommand(int caseCount) {
        List<String> cmd = new ArrayList<>();
        cmd.add("javac");
        cmd.add("-J-Xmx" + properties.sandbox().maxMemoryMb() + "m");
        cmd.add("Solution.java");
        for (int i = 0; i < caseCount; i++) cmd.add("Runner" + i + ".java");
        return cmd;
    }

    private List<String> buildRunCommand(Path root, int caseIndex) {
        return List.of(
                "java",
                "-Xms32m",
                "-Xmx" + properties.sandbox().maxMemoryMb() + "m",
                "-cp",
                root.toAbsolutePath().toString(),
                "Runner" + caseIndex);
    }

    private static String runnerSource(int index, String input) {
        return """
                public class Runner%s {
                    public static void main(String[] args) {
                        String result = Solution.solve(\"%s\");
                        System.out.print(result == null ? \"\" : result.trim());
                    }
                }
                """.formatted(index, escapeJava(input == null ? "" : input));
    }

    private static String escapeJava(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n");
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static long timeout(Duration duration) {
        if (duration == null || duration.isNegative() || duration.isZero()) return 1000L;
        return Math.max(200L, duration.toMillis());
    }

    private static void deleteQuietly(Path path) {
        try {
            if (!Files.exists(path)) return;
            try (var stream = Files.walk(path)) {
                List<Path> toDelete = stream.sorted((a, b) -> b.compareTo(a)).toList();
                for (Path candidate : toDelete) Files.deleteIfExists(candidate);
            }
        } catch (Exception ignored) {
        }
    }
}
