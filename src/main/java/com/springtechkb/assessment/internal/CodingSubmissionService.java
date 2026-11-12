package com.springtechkb.assessment.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import com.springtechkb.HashUtils;
import com.springtechkb.HttpErrors;
import com.springtechkb.user.UserSkillProfile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Handles coding-question sandbox submission, preview execution, and BKT skill updates. */
@Service
class CodingSubmissionService {

    private final JdbcTemplate jdbc;
    private final CodeSandboxService sandbox;
    private final UserSkillProfile skills;
    private final JsonMapper objectMapper;

    CodingSubmissionService(
            JdbcTemplate jdbc,
            CodeSandboxService sandbox,
            UserSkillProfile skills,
            JsonMapper objectMapper) {
        this.jdbc = jdbc;
        this.sandbox = sandbox;
        this.skills = skills;
        this.objectMapper = objectMapper;
    }

    @Transactional
    AssessmentPhase3Service.CodingSubmissionResult submitCoding(
            UUID userId, AssessmentPhase3Service.CodingSubmissionRequest request) {
        if (request == null || request.questionId() == null || request.idempotencyKey() == null
                || request.idempotencyKey().isBlank() || request.sourceCode() == null || request.sourceCode().isBlank()) {
            throw HttpErrors.badRequest("questionId, idempotencyKey and sourceCode are required");
        }
        QuestionTypeRow question = codingQuestion(request.questionId());
        AssessmentPhase3Service.CodingSubmissionResult existing =
                findExistingCoding(userId, request.questionId(), request.idempotencyKey());
        if (existing != null) return existing;

        List<CodeSandboxService.TestCase> tests = jdbc.query("""
                SELECT input_text, expected_output, is_hidden, weight
                FROM question_test_case
                WHERE question_id = ?
                ORDER BY case_order
                """, (rs, row) -> new CodeSandboxService.TestCase(
                rs.getString("input_text"), rs.getString("expected_output"),
                rs.getBoolean("is_hidden"), rs.getBigDecimal("weight").doubleValue()), request.questionId());
        if (tests.isEmpty()) throw HttpErrors.badRequest("Coding question has no executable test cases");

        CodeSandboxService.SandboxResult sandboxResult = sandbox.evaluateJava(request.sourceCode(), tests);
        List<String> hints = staticHints(request.sourceCode(), sandboxResult.policyViolations());
        int passed = (int) sandboxResult.tests().stream().filter(CodeSandboxService.TestResult::passed).count();
        BigDecimal score = sandboxResult.tests().isEmpty()
                ? BigDecimal.ZERO
                : BigDecimal.valueOf(passed).divide(BigDecimal.valueOf(sandboxResult.tests().size()), 3, RoundingMode.HALF_UP);
        boolean correct = score.compareTo(BigDecimal.valueOf(0.70)) >= 0;

        UUID submissionId;
        try {
            submissionId = jdbc.queryForObject("""
                    INSERT INTO coding_submission (
                        user_id, session_id, question_id, idempotency_key, language, source_code, code_hash,
                        compile_success, runtime_success, total_tests, passed_tests, score, static_hints, result_payload)
                    VALUES (?, ?, ?, ?, 'JAVA', ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)
                    RETURNING id
                    """, UUID.class, userId, request.sessionId(), request.questionId(), request.idempotencyKey(),
                    request.sourceCode(), HashUtils.sha256Hex(request.sourceCode()), sandboxResult.compileSuccess(),
                    sandboxResult.runtimeSuccess(), sandboxResult.tests().size(), passed, score,
                    writeJson(hints), writeJson(sandboxResult));
        } catch (DuplicateKeyException exception) {
            AssessmentPhase3Service.CodingSubmissionResult retry =
                    findExistingCoding(userId, request.questionId(), request.idempotencyKey());
            if (retry != null) return retry;
            throw HttpErrors.conflict("Duplicate coding submission");
        }
        applyCodingBktOnce(submissionId, userId, question.topicId(), correct);
        return new AssessmentPhase3Service.CodingSubmissionResult(submissionId, request.questionId(),
                sandboxResult.compileSuccess(), sandboxResult.runtimeSuccess(), passed,
                sandboxResult.tests().size(), score, List.copyOf(hints), sandboxResult.tests(), false);
    }

    @Transactional(readOnly = true)
    AssessmentPhase3Service.FrontendCodingResult runCodingPreview(
            UUID userId, UUID assessmentId, AssessmentPhase3Service.FrontendCodingRequest request) {
        validateAssessmentOwnership(userId, assessmentId);
        if (request == null || request.questionId() == null || request.code() == null || request.code().isBlank()) {
            throw HttpErrors.badRequest("questionId and code are required");
        }
        codingQuestion(request.questionId());
        List<CodeSandboxService.TestCase> tests = loadCodingTests(request.questionId());
        CodeSandboxService.SandboxResult sandboxResult = sandbox.evaluateJava(request.code(), tests);
        List<String> staticHints = staticHints(request.code(), sandboxResult.policyViolations());
        return toFrontendResult(sandboxResult, staticHints);
    }

    @Transactional
    AssessmentPhase3Service.FrontendCodingResult submitCodingFromAssessment(
            UUID userId, UUID assessmentId, AssessmentPhase3Service.FrontendCodingRequest request) {
        validateAssessmentOwnership(userId, assessmentId);
        if (request == null || request.questionId() == null || request.code() == null || request.code().isBlank()) {
            throw HttpErrors.badRequest("questionId and code are required");
        }
        String key = "assessment-" + assessmentId + "-" + HashUtils.sha256Hex(request.code());
        AssessmentPhase3Service.CodingSubmissionResult stored = submitCoding(userId,
                new AssessmentPhase3Service.CodingSubmissionRequest(
                        request.questionId(), assessmentId, key, request.code()));
        return toFrontendResult(stored);
    }

    private AssessmentPhase3Service.CodingSubmissionResult findExistingCoding(
            UUID userId, UUID questionId, String key) {
        return jdbc.query("""
                SELECT id, compile_success, runtime_success, passed_tests, total_tests, score, static_hints, result_payload
                FROM coding_submission
                WHERE user_id = ? AND question_id = ? AND idempotency_key = ?
                """, rs -> {
            if (!rs.next()) return null;
            return new AssessmentPhase3Service.CodingSubmissionResult(
                    rs.getObject("id", UUID.class), questionId,
                    rs.getBoolean("compile_success"), rs.getBoolean("runtime_success"),
                    rs.getInt("passed_tests"), rs.getInt("total_tests"), rs.getBigDecimal("score"),
                    readStringArray(rs.getString("static_hints")),
                    readTestResultsFromPayload(rs.getString("result_payload")),
                    true);
        }, userId, questionId, key);
    }

    private List<CodeSandboxService.TestCase> loadCodingTests(UUID questionId) {
        List<CodeSandboxService.TestCase> tests = jdbc.query("""
                SELECT input_text, expected_output, is_hidden, weight
                FROM question_test_case
                WHERE question_id = ?
                ORDER BY case_order
                """, (rs, row) -> new CodeSandboxService.TestCase(
                rs.getString("input_text"), rs.getString("expected_output"),
                rs.getBoolean("is_hidden"), rs.getBigDecimal("weight").doubleValue()), questionId);
        if (tests.isEmpty()) throw HttpErrors.badRequest("Coding question has no executable test cases");
        return tests;
    }

    private void applyCodingBktOnce(UUID submissionId, UUID userId, UUID topicId, boolean correct) {
        int updated = jdbc.update("""
                UPDATE coding_submission
                SET bkt_applied = TRUE
                WHERE id = ? AND bkt_applied = FALSE
                """, submissionId);
        if (updated == 1) skills.recordResponse(userId, topicId, correct);
    }

    private QuestionTypeRow codingQuestion(UUID questionId) {
        return typedQuestion(questionId, "CODE_COMPLETION");
    }

    private QuestionTypeRow typedQuestion(UUID questionId, String type) {
        return jdbc.query("""
                SELECT id, topic_id, type
                FROM question WHERE id = ?
                """, (rs, row) -> new QuestionTypeRow(
                rs.getObject("id", UUID.class), rs.getObject("topic_id", UUID.class),
                rs.getString("type")), questionId).stream().findFirst()
                .map(row -> {
                    if (!type.equals(row.type())) throw HttpErrors.badRequest("Question type is not " + type);
                    return row;
                })
                .orElseThrow(() -> HttpErrors.notFound("Question was not found"));
    }

    private void validateAssessmentOwnership(UUID userId, UUID assessmentId) {
        if (assessmentId == null) throw HttpErrors.badRequest("assessmentId is required");
        Boolean exists = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM assessment_session WHERE id = ? AND user_id = ?)
                """, Boolean.class, assessmentId, userId);
        if (!Boolean.TRUE.equals(exists)) throw HttpErrors.notFound("Assessment was not found");
    }

    private AssessmentPhase3Service.FrontendCodingResult toFrontendResult(
            AssessmentPhase3Service.CodingSubmissionResult stored) {
        List<String> tests = stored.tests().stream()
                .map(test -> test.passed()
                        ? "测试 #" + (test.index() + 1) + " 通过。"
                        : "测试 #" + (test.index() + 1) + " 未通过："
                        + (test.error() == null ? ("期望[" + test.expectedOutput() + "]，实际[" + test.actualOutput() + "]")
                        : test.error()))
                .toList();
        String status = stored.compileSuccess() && stored.runtimeSuccess() && stored.passedTests() == stored.totalTests()
                ? "SUCCESS" : "FAILED";
        return new AssessmentPhase3Service.FrontendCodingResult(
                status,
                stored.compileSuccess() ? List.of() : List.of("编译失败。请检查语法。"),
                stored.runtimeSuccess() ? List.of() : List.of("运行失败。"),
                tests,
                stored.staticHints(),
                "");
    }

    private AssessmentPhase3Service.FrontendCodingResult toFrontendResult(
            CodeSandboxService.SandboxResult sandboxResult, List<String> staticHints) {
        List<String> compile = sandboxResult.compileSuccess()
                ? List.of()
                : List.of(blankToNull(sandboxResult.compileOutput()) == null ? "编译失败。" : sandboxResult.compileOutput());
        List<String> runtime = sandboxResult.runtimeSuccess()
                ? List.of()
                : List.of("运行失败或超时。");
        List<String> tests = sandboxResult.tests().stream()
                .map(test -> test.passed()
                        ? "测试 #" + (test.index() + 1) + " 通过。"
                        : "测试 #" + (test.index() + 1) + " 未通过："
                        + (test.error() == null ? ("期望[" + test.expectedOutput() + "]，实际[" + test.actualOutput() + "]")
                        : test.error()))
                .toList();
        String status = sandboxResult.compileSuccess() && sandboxResult.runtimeSuccess()
                && sandboxResult.tests().stream().allMatch(CodeSandboxService.TestResult::passed)
                ? "SUCCESS" : "FAILED";
        String output = sandboxResult.tests().stream()
                .map(CodeSandboxService.TestResult::actualOutput)
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse("");
        return new AssessmentPhase3Service.FrontendCodingResult(status, compile, runtime, tests, staticHints, output);
    }

    private static List<String> staticHints(String sourceCode, List<String> policyViolations) {
        List<String> hints = new ArrayList<>();
        hints.addAll(policyViolations);
        String safe = sourceCode == null ? "" : sourceCode;
        if (!safe.contains("class Solution")) hints.add("Implement class Solution");
        if (!safe.contains("static String solve")) hints.add("Expose static String solve(String input)");
        if (safe.contains("System.out.println")) hints.add("Prefer returning value from solve(...) over printing");
        if (safe.contains("catch (Exception")) hints.add("Avoid overly broad exception swallowing");
        return hints.stream().distinct().collect(Collectors.toUnmodifiableList());
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not serialize JSON", exception);
        }
    }

    private List<String> readStringArray(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node == null || !node.isArray()) return List.of();
            List<String> values = new ArrayList<>();
            for (JsonNode item : node) values.add(item.asText());
            return List.copyOf(values);
        } catch (Exception exception) {
            return List.of();
        }
    }

    private List<CodeSandboxService.TestResult> readTestResultsFromPayload(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode tests = root.path("tests");
            if (!tests.isArray()) return List.of();
            List<CodeSandboxService.TestResult> values = new ArrayList<>();
            for (JsonNode test : tests) {
                values.add(new CodeSandboxService.TestResult(
                        test.path("index").asInt(),
                        test.path("passed").asBoolean(),
                        test.path("actualOutput").asText(""),
                        test.path("expectedOutput").asText(""),
                        test.path("error").isNull() ? null : test.path("error").asText()));
            }
            return List.copyOf(values);
        } catch (Exception exception) {
            return List.of();
        }
    }

    private static String blankToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    private record QuestionTypeRow(UUID id, UUID topicId, String type) {}
}
