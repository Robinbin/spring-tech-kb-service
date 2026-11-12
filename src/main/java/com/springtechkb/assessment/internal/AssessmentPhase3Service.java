package com.springtechkb.assessment.internal;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.stereotype.Service;

/**
 * Thin orchestration facade over the assessment sub-services.
 *
 * <p>All record/DTO types live here so {@link AssessmentController} and sub-services share a single
 * source of truth without introducing a separate DTO module. Business logic lives in:
 * <ul>
 *   <li>{@link QuestionGenerationService} — AI batch generation and admin review</li>
 *   <li>{@link CodingSubmissionService} — sandbox execution and submission</li>
 *   <li>{@link SubjectiveGradingService} — AI open-ended grading</li>
 *   <li>{@link AdaptiveTestService} — IRT-based adaptive selection and ability reporting</li>
 *   <li>{@link IrtCalibrationService} — scheduled IRT parameter calibration</li>
 * </ul>
 */
@Service
class AssessmentPhase3Service {

    private final QuestionGenerationService questionGeneration;
    private final CodingSubmissionService codingSubmissions;
    private final SubjectiveGradingService subjectiveGrading;
    private final AdaptiveTestService adaptiveTest;
    private final IrtCalibrationService irtCalibration;

    AssessmentPhase3Service(
            QuestionGenerationService questionGeneration,
            CodingSubmissionService codingSubmissions,
            SubjectiveGradingService subjectiveGrading,
            AdaptiveTestService adaptiveTest,
            IrtCalibrationService irtCalibration) {
        this.questionGeneration = questionGeneration;
        this.codingSubmissions = codingSubmissions;
        this.subjectiveGrading = subjectiveGrading;
        this.adaptiveTest = adaptiveTest;
        this.irtCalibration = irtCalibration;
    }

    BatchResult generateBatch(UUID adminId, BatchGenerateRequest request) {
        return questionGeneration.generateBatch(adminId, request);
    }

    ReviewResult reviewQuestion(UUID reviewerId, UUID questionId, ReviewRequest request) {
        return questionGeneration.reviewQuestion(reviewerId, questionId, request);
    }

    CodingSubmissionResult submitCoding(UUID userId, CodingSubmissionRequest request) {
        return codingSubmissions.submitCoding(userId, request);
    }

    FrontendCodingResult runCodingPreview(UUID userId, UUID assessmentId, FrontendCodingRequest request) {
        return codingSubmissions.runCodingPreview(userId, assessmentId, request);
    }

    FrontendCodingResult submitCodingFromAssessment(UUID userId, UUID assessmentId, FrontendCodingRequest request) {
        return codingSubmissions.submitCodingFromAssessment(userId, assessmentId, request);
    }

    SubjectiveGradeResult gradeOpenEnded(UUID userId, SubjectiveGradeRequest request) {
        return subjectiveGrading.gradeOpenEnded(userId, request);
    }

    AdaptiveQuestion adaptiveNext(UUID userId, UUID topicId) {
        return adaptiveTest.adaptiveNext(userId, topicId);
    }

    AbilityReport abilityReport(UUID userId) {
        return adaptiveTest.abilityReport(userId);
    }

    FrontendAbilityReport assessmentReport(UUID userId, UUID assessmentId) {
        return adaptiveTest.assessmentReport(userId, assessmentId);
    }

    IrtCalibrationResult calibrateIrt() {
        return irtCalibration.calibrate();
    }

    // ── DTOs (kept here so AssessmentController needs no changes) ──────────────

    record BatchGenerateRequest(
            @NotEmpty List<@NotNull UUID> topicIds,
            @NotNull @Size(min = 1, max = 3) List<@Pattern(regexp = "MULTIPLE_CHOICE|CODE_COMPLETION|OPEN_ENDED") String> types,
            int countPerTopic) {}
    record BatchResult(UUID batchId, String modelId, String promptVersion, List<BatchItemResult> items) {}
    record BatchItemResult(UUID questionId, UUID topicId, String type, BigDecimal qualityScore, List<String> validationErrors) {}

    record ReviewRequest(boolean approve, @Size(max = 1000) String note, List<String> validationErrors) {}
    record ReviewResult(UUID questionId, boolean approved, String status, BigDecimal qualityScore, List<String> validationErrors) {}

    record CodingSubmissionRequest(
            @NotNull UUID questionId,
            UUID sessionId,
            @NotBlank @Size(max = 120) String idempotencyKey,
            @NotBlank @Size(max = 20000) String sourceCode) {}
    record FrontendCodingRequest(
            @NotNull UUID questionId,
            @NotBlank @Size(max = 20000) String code,
            @Size(max = 40) String language) {}
    record FrontendCodingResult(
            String status,
            List<String> compile,
            List<String> runtime,
            List<String> tests,
            List<String> staticHints,
            String output) {}
    record CodingSubmissionResult(
            UUID submissionId,
            UUID questionId,
            boolean compileSuccess,
            boolean runtimeSuccess,
            int passedTests,
            int totalTests,
            BigDecimal score,
            List<String> staticHints,
            List<CodeSandboxService.TestResult> tests,
            boolean replayed) {}

    record SubjectiveGradeRequest(@NotNull UUID questionId, UUID sessionId, @NotBlank @Size(max = 12000) String answer) {}
    record SubjectiveGradeResult(
            UUID gradingId,
            UUID questionId,
            BigDecimal totalScore,
            BigDecimal maxScore,
            BigDecimal normalizedScore,
            String feedback,
            List<DimensionScore> dimensions,
            boolean replayed) {}

    record DimensionScore(
            @NotBlank String code,
            @NotNull BigDecimal score,
            @NotNull BigDecimal maxScore,
            @Size(max = 2000) String feedback) {}

    record AdaptiveQuestion(
            UUID questionId,
            UUID topicId,
            String type,
            String difficulty,
            String content,
            List<Map<String, Object>> options,
            BigDecimal userTheta,
            BigDecimal irtDifficulty,
            BigDecimal irtDiscrimination,
            BigDecimal irtGuessing) {}

    record AbilityReport(
            BigDecimal masteryAggregate,
            AssessmentStats assessment,
            ActivityStats coding,
            ActivityStats subjective,
            List<RadarDimension> radar,
            List<String> strengths,
            List<String> weaknesses,
            List<String> recommendations) {}
    record FrontendAbilityReport(
            UUID assessmentId,
            Instant generatedAt,
            int overallScore,
            List<FrontendAbilityLevel> levels,
            List<String> strengths,
            List<String> improvements) {}
    record FrontendAbilityLevel(String label, int value) {}

    record AssessmentStats(int completedSessions, BigDecimal averageScore) {}
    record ActivityStats(int submissions, BigDecimal averageScore) {}
    record RadarDimension(UUID topicId, String topicName, BigDecimal mastery, BigDecimal ability, int responses, Instant lastUpdated) {}
    record IrtCalibrationResult(int updatedQuestions) {}

    record GeneratedDraftBatch(@Valid List<@Valid GeneratedDraftQuestion> questions) {}
    record GeneratedDraftQuestion(
            @NotBlank String type,
            @NotBlank String content,
            @NotNull UUID sourceChunkId,
            String difficulty,
            String correctAnswer,
            String explanation,
            String springModule,
            String springVersion,
            List<@Valid GeneratedOption> options,
            String expectedSignature,
            List<@Valid GeneratedTestCase> testCases,
            List<@Valid GeneratedRubricDimension> rubricDimensions) {}
    record GeneratedOption(@NotBlank String label, @NotBlank String text) {}
    record GeneratedTestCase(@NotBlank String input, @NotBlank String expectedOutput, Boolean hidden, double weight) {}
    record GeneratedRubricDimension(@NotBlank String code, @NotBlank String name, String description,
                                    @NotNull BigDecimal maxScore, @NotNull BigDecimal weight) {}
    record GeneratedGrade(@NotNull BigDecimal totalScore, @NotBlank String feedback,
                          List<@Valid DimensionScore> dimensionScores) {}
}
