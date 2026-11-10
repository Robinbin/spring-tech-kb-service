package com.springtechkb.assessment.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;

import com.springtechkb.HashUtils;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.springtechkb.ai.StructuredGenerationGateway;
import com.springtechkb.user.UserSkillProfile;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
class AssessmentPhase3Service {
    private static final Set<String> QUESTION_TYPES = Set.of("MULTIPLE_CHOICE", "CODE_COMPLETION", "OPEN_ENDED");

    private final JdbcTemplate jdbc;
    private final StructuredGenerationGateway gateway;
    private final UserSkillProfile skills;
    private final CodeSandboxService sandbox;
    private final JsonMapper objectMapper;
    private final AssessmentPhase3Properties properties;

    AssessmentPhase3Service(
            JdbcTemplate jdbc,
            StructuredGenerationGateway gateway,
            UserSkillProfile skills,
            CodeSandboxService sandbox,
            JsonMapper objectMapper,
            AssessmentPhase3Properties properties) {
        this.jdbc = jdbc;
        this.gateway = gateway;
        this.skills = skills;
        this.sandbox = sandbox;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Transactional
    BatchResult generateBatch(UUID adminId, BatchGenerateRequest request) {
        if (request == null || request.countPerTopic() < 1 || request.countPerTopic() > 10
                || request.topicIds() == null || request.topicIds().isEmpty() || request.topicIds().size() > 20
                || request.types() == null || request.types().isEmpty()) {
            throw badRequest("Provide 1-20 topics, 1-10 questions per topic, and at least one type");
        }
        if (request.topicIds().stream().anyMatch(Objects::isNull) || request.types().stream().anyMatch(Objects::isNull)
                || request.types().stream().anyMatch(type -> !QUESTION_TYPES.contains(type))) {
            throw badRequest("topicIds and types must be non-null; types must be MULTIPLE_CHOICE/CODE_COMPLETION/OPEN_ENDED");
        }
        for (UUID topicId : request.topicIds()) {
            if (!Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM knowledge_topic WHERE id = ?)", Boolean.class, topicId))) {
                throw notFound("Topic not found: " + topicId);
            }
        }

        UUID batchId = jdbc.queryForObject("""
                INSERT INTO question_generation_batch (created_by, topic_ids, requested_types, requested_count, status)
                VALUES (?, ?::jsonb, ?::jsonb, ?, 'COMPLETED')
                RETURNING id
                """, UUID.class, adminId, writeJson(request.topicIds()), writeJson(request.types()), request.countPerTopic());

        List<BatchItemResult> items = new ArrayList<>();
        String modelId = null;
        String promptVersion = null;
        for (UUID topicId : request.topicIds()) {
            String context = buildGenerationContext(topicId, request.countPerTopic(), request.types());
            var generated = gateway.generate("assessment-question-batch", adminId.toString(), context, GeneratedDraftBatch.class);
            modelId = generated.modelId();
            promptVersion = generated.promptVersion();
            List<GeneratedDraftQuestion> drafts = generated.value() == null ? List.of() : generated.value().questions();
            for (GeneratedDraftQuestion draft : drafts) {
                List<String> rawErrors = new ArrayList<>(qualityErrors(topicId, draft));
                BigDecimal score = qualityScore(rawErrors);
                if (score.compareTo(BigDecimal.valueOf(properties.quality().minimumScore().draft())) < 0) {
                    rawErrors.add("Quality score is below draft threshold");
                }
                List<String> errors = List.copyOf(rawErrors);
                UUID questionId = null;
                if (!hasBlockingErrors(errors)) {
                    questionId = persistDraft(batchId, topicId, draft, score, errors);
                    jdbc.update("""
                            INSERT INTO question_generation_batch_item (batch_id, question_id) VALUES (?, ?)
                            """, batchId, questionId);
                }
                items.add(new BatchItemResult(questionId, topicId, draft == null ? null : draft.type(), score, errors));
            }
        }
        jdbc.update("""
                UPDATE question_generation_batch SET model_id = ?, prompt_version = ?
                WHERE id = ?
                """, modelId, promptVersion, batchId);
        return new BatchResult(batchId, modelId, promptVersion, List.copyOf(items));
    }

    @Transactional
    ReviewResult reviewQuestion(UUID reviewerId, UUID questionId, ReviewRequest request) {
        QuestionMeta question = jdbc.query("""
                SELECT id, review_status, quality_score
                FROM question WHERE id = ?
                """, (rs, row) -> new QuestionMeta(
                rs.getObject("id", UUID.class),
                rs.getString("review_status"),
                rs.getBigDecimal("quality_score")), questionId).stream().findFirst()
                .orElseThrow(() -> notFound("Question was not found"));
        boolean approve = request != null && request.approve();
        if (approve && (question.qualityScore() == null
                || question.qualityScore().compareTo(BigDecimal.valueOf(properties.quality().minimumScore().approval())) < 0)) {
            throw badRequest("Question quality score is below approval threshold");
        }
        String status = approve ? "APPROVED" : "REJECTED";
        jdbc.update("""
                UPDATE question
                SET is_reviewed = ?, review_status = ?, reviewed_by = ?, reviewed_at = now()
                WHERE id = ?
                """, approve, status, reviewerId, questionId);
        List<String> errors = request == null || request.validationErrors() == null ? List.of() : request.validationErrors();
        jdbc.update("""
                INSERT INTO question_quality_audit
                    (question_id, quality_score, review_status, validation_errors, reviewer_user_id, note)
                VALUES (?, ?, ?, ?::jsonb, ?, ?)
                """, questionId, question.qualityScore(), status, writeJson(errors), reviewerId,
                request == null ? null : request.note());
        return new ReviewResult(questionId, approve, status, question.qualityScore(), errors);
    }

    @Transactional
    CodingSubmissionResult submitCoding(UUID userId, CodingSubmissionRequest request) {
        if (request == null || request.questionId() == null || request.idempotencyKey() == null
                || request.idempotencyKey().isBlank() || request.sourceCode() == null || request.sourceCode().isBlank()) {
            throw badRequest("questionId, idempotencyKey and sourceCode are required");
        }
        QuestionTypeRow question = codingQuestion(request.questionId());
        CodingSubmissionResult existing = findExistingCoding(userId, request.questionId(), request.idempotencyKey());
        if (existing != null) return existing;

        List<CodeSandboxService.TestCase> tests = jdbc.query("""
                SELECT input_text, expected_output, is_hidden, weight
                FROM question_test_case
                WHERE question_id = ?
                ORDER BY case_order
                """, (rs, row) -> new CodeSandboxService.TestCase(
                rs.getString("input_text"), rs.getString("expected_output"),
                rs.getBoolean("is_hidden"), rs.getBigDecimal("weight").doubleValue()), request.questionId());
        if (tests.isEmpty()) throw badRequest("Coding question has no executable test cases");

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
            CodingSubmissionResult retry = findExistingCoding(userId, request.questionId(), request.idempotencyKey());
            if (retry != null) return retry;
            throw conflict("Duplicate coding submission");
        }
        applyCodingBktOnce(submissionId, userId, question.topicId(), correct);
        return new CodingSubmissionResult(submissionId, request.questionId(), sandboxResult.compileSuccess(),
                sandboxResult.runtimeSuccess(), passed, sandboxResult.tests().size(), score,
                List.copyOf(hints), sandboxResult.tests(), false);
    }

    @Transactional(readOnly = true)
    FrontendCodingResult runCodingPreview(UUID userId, UUID assessmentId, FrontendCodingRequest request) {
        validateAssessmentOwnership(userId, assessmentId);
        if (request == null || request.questionId() == null || request.code() == null || request.code().isBlank()) {
            throw badRequest("questionId and code are required");
        }
        codingQuestion(request.questionId());
        List<CodeSandboxService.TestCase> tests = loadCodingTests(request.questionId());
        CodeSandboxService.SandboxResult sandboxResult = sandbox.evaluateJava(request.code(), tests);
        List<String> staticHints = staticHints(request.code(), sandboxResult.policyViolations());
        return toFrontendResult(sandboxResult, staticHints);
    }

    @Transactional
    FrontendCodingResult submitCodingFromAssessment(UUID userId, UUID assessmentId, FrontendCodingRequest request) {
        validateAssessmentOwnership(userId, assessmentId);
        if (request == null || request.questionId() == null || request.code() == null || request.code().isBlank()) {
            throw badRequest("questionId and code are required");
        }
        String key = "assessment-" + assessmentId + "-" + HashUtils.sha256Hex(request.code());
        CodingSubmissionResult stored = submitCoding(userId, new CodingSubmissionRequest(
                request.questionId(), assessmentId, key, request.code()));
        return toFrontendResult(stored);
    }

    @Transactional
    SubjectiveGradeResult gradeOpenEnded(UUID userId, SubjectiveGradeRequest request) {
        if (request == null || request.questionId() == null || request.answer() == null || request.answer().isBlank()) {
            throw badRequest("questionId and answer are required");
        }
        QuestionTypeRow question = openEndedQuestion(request.questionId());
        String answerHash = HashUtils.sha256Hex(request.answer().trim());
        SubjectiveGradeResult existing = findExistingSubjective(userId, request.questionId(), answerHash);
        if (existing != null) return existing;

        List<RubricDimension> dimensions = jdbc.query("""
                SELECT dimension_code, name, max_score, weight, description
                FROM question_rubric_dimension
                WHERE question_id = ?
                ORDER BY dimension_order
                """, (rs, row) -> new RubricDimension(
                rs.getString("dimension_code"), rs.getString("name"),
                rs.getBigDecimal("max_score"), rs.getBigDecimal("weight"),
                rs.getString("description")), request.questionId());
        if (dimensions.isEmpty()) throw badRequest("Open-ended question has no rubric dimensions");
        String gradingInput = """
                Grade the answer for an open-ended Spring question.
                QUESTION: %s
                ANSWER: %s
                RUBRIC: %s
                """.formatted(request.questionId(), request.answer(), writeJson(dimensions));
        var generated = gateway.generate("assessment-subjective-grade", userId.toString(), gradingInput, GeneratedGrade.class);
        GeneratedGrade grade = generated.value();
        if (grade == null || grade.dimensionScores() == null || grade.dimensionScores().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "AI grading output is invalid");
        }
        BigDecimal maxScore = dimensions.stream().map(RubricDimension::maxScore)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal total = grade.totalScore() == null ? BigDecimal.ZERO : grade.totalScore();
        if (total.compareTo(BigDecimal.ZERO) < 0) total = BigDecimal.ZERO;
        if (total.compareTo(maxScore) > 0) total = maxScore;
        BigDecimal normalized = maxScore.compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ZERO
                : total.divide(maxScore, 3, RoundingMode.HALF_UP);
        boolean correct = normalized.compareTo(BigDecimal.valueOf(0.70)) >= 0;

        UUID resultId;
        try {
            resultId = jdbc.queryForObject("""
                    INSERT INTO subjective_grading_result (
                        user_id, session_id, question_id, response_text, response_hash, total_score, feedback,
                        rubric_scores, model_id, prompt_version)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                    RETURNING id
                    """, UUID.class, userId, request.sessionId(), request.questionId(), request.answer(),
                    answerHash, total, grade.feedback(), writeJson(grade.dimensionScores()),
                    generated.modelId(), generated.promptVersion());
        } catch (DuplicateKeyException exception) {
            SubjectiveGradeResult retry = findExistingSubjective(userId, request.questionId(), answerHash);
            if (retry != null) return retry;
            throw conflict("Duplicate open-ended submission");
        }
        applySubjectiveBktOnce(resultId, userId, question.topicId(), correct);
        return new SubjectiveGradeResult(resultId, request.questionId(), total, maxScore, normalized,
                grade.feedback(), grade.dimensionScores(), false);
    }

    @Transactional(readOnly = true)
    AdaptiveQuestion adaptiveNext(UUID userId, UUID topicId) {
        if (topicId == null) throw badRequest("topicId is required");
        BigDecimal mastery = jdbc.query("""
                SELECT p_mastery FROM user_skill_state WHERE user_id = ? AND topic_id = ?
                """, rs -> rs.next() ? rs.getBigDecimal("p_mastery") : BigDecimal.valueOf(0.1), userId, topicId);
        double theta = (mastery.doubleValue() - 0.5d) * 6.0d;
        List<AdaptiveQuestion> questions = jdbc.query("""
                SELECT q.id, q.topic_id, q.type, q.difficulty, q.content, q.options,
                       COALESCE(irt.difficulty, 0) AS irt_difficulty,
                       COALESCE(irt.discrimination, 1) AS irt_discrimination,
                       COALESCE(irt.guessing, CASE WHEN q.type = 'MULTIPLE_CHOICE' THEN 0.25 ELSE 0.10 END) AS irt_guessing
                FROM question q
                LEFT JOIN question_irt_stats irt ON irt.question_id = q.id
                WHERE q.topic_id = ? AND q.is_reviewed = TRUE AND q.review_status = 'APPROVED'
                ORDER BY ABS(COALESCE(irt.difficulty, 0) - ?) ASC, q.created_at DESC
                LIMIT 1
                """, (rs, row) -> new AdaptiveQuestion(
                rs.getObject("id", UUID.class), rs.getObject("topic_id", UUID.class), rs.getString("type"),
                rs.getString("difficulty"), rs.getString("content"), safeOptions(rs.getString("options")),
                BigDecimal.valueOf(theta).setScale(3, RoundingMode.HALF_UP),
                rs.getBigDecimal("irt_difficulty"), rs.getBigDecimal("irt_discrimination"), rs.getBigDecimal("irt_guessing")),
                topicId, theta);
        if (questions.isEmpty()) throw notFound("No approved question is available for this topic");
        return questions.getFirst();
    }

    @Transactional(readOnly = true)
    AbilityReport abilityReport(UUID userId) {
        List<RadarDimension> dimensions = jdbc.query("""
                SELECT s.topic_id, t.name, s.p_mastery, s.response_count, s.last_updated
                FROM user_skill_state s
                JOIN knowledge_topic t ON t.id = s.topic_id
                WHERE s.user_id = ?
                ORDER BY s.p_mastery DESC, t.name
                """, (rs, row) -> new RadarDimension(
                rs.getObject("topic_id", UUID.class), rs.getString("name"),
                rs.getBigDecimal("p_mastery"),
                BigDecimal.valueOf((rs.getBigDecimal("p_mastery").doubleValue() - 0.5d) * 6d)
                        .setScale(3, RoundingMode.HALF_UP),
                rs.getInt("response_count"), rs.getTimestamp("last_updated").toInstant()), userId);
        AssessmentStats assessment = jdbc.query("""
                SELECT COUNT(*) AS sessions, COALESCE(AVG(score), 0) AS avg_score
                FROM assessment_session
                WHERE user_id = ? AND status = 'COMPLETED' AND submitted_at >= ?
                """, rs -> {
            rs.next();
            return new AssessmentStats(rs.getInt("sessions"), rs.getBigDecimal("avg_score"));
        }, userId, java.sql.Timestamp.from(Instant.now().minus(30, ChronoUnit.DAYS)));
        ActivityStats coding = jdbc.query("""
                SELECT COUNT(*) AS submissions, COALESCE(AVG(score), 0) AS avg_score
                FROM coding_submission
                WHERE user_id = ? AND created_at >= ?
                """, rs -> {
            rs.next();
            return new ActivityStats(rs.getInt("submissions"), rs.getBigDecimal("avg_score"));
        }, userId, java.sql.Timestamp.from(Instant.now().minus(30, ChronoUnit.DAYS)));
        ActivityStats subjective = jdbc.query("""
                SELECT COUNT(*) AS submissions, COALESCE(AVG(total_score), 0) AS avg_score
                FROM subjective_grading_result
                WHERE user_id = ? AND created_at >= ?
                """, rs -> {
            rs.next();
            return new ActivityStats(rs.getInt("submissions"), rs.getBigDecimal("avg_score"));
        }, userId, java.sql.Timestamp.from(Instant.now().minus(30, ChronoUnit.DAYS)));

        List<String> strengths = dimensions.stream()
                .filter(d -> d.mastery().compareTo(BigDecimal.valueOf(0.75)) >= 0)
                .map(RadarDimension::topicName).limit(3).toList();
        List<String> weaknesses = dimensions.stream()
                .filter(d -> d.mastery().compareTo(BigDecimal.valueOf(0.45)) <= 0)
                .sorted((a, b) -> a.mastery().compareTo(b.mastery()))
                .map(RadarDimension::topicName).limit(3).toList();
        List<String> recommendations = weaknesses.stream()
                .map(w -> "Focus review and coding drills on " + w)
                .toList();
        BigDecimal mastery = dimensions.isEmpty() ? BigDecimal.ZERO :
                dimensions.stream().map(RadarDimension::mastery).reduce(BigDecimal.ZERO, BigDecimal::add)
                        .divide(BigDecimal.valueOf(dimensions.size()), 3, RoundingMode.HALF_UP);
        return new AbilityReport(mastery, assessment, coding, subjective, dimensions, strengths, weaknesses, recommendations);
    }

    @Transactional(readOnly = true)
    FrontendAbilityReport assessmentReport(UUID userId, UUID assessmentId) {
        AssessmentScoreRow row = jdbc.query("""
                SELECT score FROM assessment_session
                WHERE id = ? AND user_id = ?
                """, (rs, rowNum) -> new AssessmentScoreRow(rs.getBigDecimal("score")), assessmentId, userId)
                .stream().findFirst().orElseThrow(() -> notFound("Assessment was not found"));
        AbilityReport report = abilityReport(userId);
        List<FrontendAbilityLevel> levels = report.radar().stream()
                .limit(5)
                .map(r -> new FrontendAbilityLevel(
                        r.topicName(),
                        r.mastery().multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP).intValue()))
                .toList();
        int overall = row.score() == null
                ? report.assessment().averageScore().setScale(0, RoundingMode.HALF_UP).intValue()
                : row.score().setScale(0, RoundingMode.HALF_UP).intValue();
        return new FrontendAbilityReport(
                assessmentId,
                Instant.now(),
                Math.max(0, Math.min(100, overall)),
                levels,
                report.strengths(),
                report.recommendations());
    }

    @Scheduled(cron = "${springtechkb.assessment.irt.cron:0 */30 * * * *}")
    @Transactional
    void calibrateIrtScheduled() {
        calibrateIrt();
    }

    @Transactional
    IrtCalibrationResult calibrateIrt() {
        List<IrtStat> stats = jdbc.query("""
                WITH objective AS (
                    SELECT ua.question_id, CASE WHEN ua.is_correct THEN 1.0 ELSE 0.0 END AS score
                    FROM user_answer ua
                ), coding AS (
                    SELECT cs.question_id, CASE WHEN cs.score >= 0.70 THEN 1.0 ELSE 0.0 END AS score
                    FROM coding_submission cs
                ), subjective AS (
                    SELECT sg.question_id,
                           CASE WHEN sumdim.max_score > 0 AND (sg.total_score / sumdim.max_score) >= 0.70
                                THEN 1.0 ELSE 0.0 END AS score
                    FROM subjective_grading_result sg
                    JOIN (
                        SELECT question_id, SUM(max_score) AS max_score
                        FROM question_rubric_dimension
                        GROUP BY question_id
                    ) sumdim ON sumdim.question_id = sg.question_id
                ), merged AS (
                    SELECT * FROM objective
                    UNION ALL
                    SELECT * FROM coding
                    UNION ALL
                    SELECT * FROM subjective
                )
                SELECT q.id AS question_id, q.type, COUNT(m.score) AS sample_size, COALESCE(AVG(m.score), 0) AS success_rate
                FROM question q
                LEFT JOIN merged m ON m.question_id = q.id
                GROUP BY q.id, q.type
                HAVING COUNT(m.score) > 0
                """, (rs, row) -> new IrtStat(
                rs.getObject("question_id", UUID.class), rs.getString("type"),
                rs.getInt("sample_size"), rs.getDouble("success_rate")));

        int updated = 0;
        for (IrtStat stat : stats) {
            double c = "MULTIPLE_CHOICE".equals(stat.type()) ? 0.25d : 0.10d;
            double p = Math.max(c + 0.01d, Math.min(0.99d, stat.successRate()));
            double adjusted = (p - c) / (1d - c);
            adjusted = Math.max(0.01d, Math.min(0.99d, adjusted));
            double b = Math.log((1d - adjusted) / adjusted);
            double a = 0.8d + Math.min(2.0d, Math.max(0d, stat.sampleSize() / 40d));
            jdbc.update("""
                    INSERT INTO question_irt_stats(question_id, discrimination, difficulty, guessing, sample_size, updated_at)
                    VALUES (?, ?, ?, ?, ?, now())
                    ON CONFLICT (question_id) DO UPDATE
                    SET discrimination = EXCLUDED.discrimination,
                        difficulty = EXCLUDED.difficulty,
                        guessing = EXCLUDED.guessing,
                        sample_size = EXCLUDED.sample_size,
                        updated_at = now()
                    """, stat.questionId(), BigDecimal.valueOf(a).setScale(5, RoundingMode.HALF_UP),
                    BigDecimal.valueOf(b).setScale(5, RoundingMode.HALF_UP),
                    BigDecimal.valueOf(c).setScale(5, RoundingMode.HALF_UP), stat.sampleSize());
            updated++;
        }
        return new IrtCalibrationResult(updated);
    }

    private UUID persistDraft(UUID batchId, UUID topicId, GeneratedDraftQuestion draft, BigDecimal qualityScore, List<String> errors) {
        String type = normalizeType(draft.type());
        String content = blankToNull(draft.content());
        UUID sourceChunkId = draft.sourceChunkId();
        String difficulty = draft.difficulty() == null ? "MEDIUM" : draft.difficulty().toUpperCase(Locale.ROOT);
        String correctAnswer = blankToNull(draft.correctAnswer());
        String explanation = blankToNull(draft.explanation());
        String optionsJson = null;
        if ("MULTIPLE_CHOICE".equals(type)) {
            List<Map<String, Object>> options = new ArrayList<>();
            for (GeneratedOption option : draft.options()) {
                options.add(Map.of(
                        "label", option.label(),
                        "text", option.text(),
                        "is_correct", Objects.equals(option.label(), correctAnswer)));
            }
            optionsJson = writeJson(options);
        }
        UUID questionId = jdbc.queryForObject("""
                INSERT INTO question (topic_id, source_chunk_id, type, difficulty, content, options, correct_answer,
                                      explanation, spring_module, spring_version, is_reviewed, generated_batch_id,
                                      quality_score, review_status, last_quality_checked_at)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, FALSE, ?, ?, 'DRAFT', now())
                RETURNING id
                """, UUID.class, topicId, sourceChunkId, type, difficulty, content, optionsJson, correctAnswer,
                explanation, draft.springModule(), draft.springVersion(), batchId, qualityScore);
        if ("CODE_COMPLETION".equals(type)) {
            int order = 0;
            for (GeneratedTestCase testCase : draft.testCases()) {
                jdbc.update("""
                        INSERT INTO question_test_case(question_id, case_order, input_text, expected_output, is_hidden, weight)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """, questionId, order++, testCase.input(), testCase.expectedOutput(),
                        Boolean.TRUE.equals(testCase.hidden()), BigDecimal.valueOf(Math.max(0.1d, testCase.weight())));
            }
        }
        if ("OPEN_ENDED".equals(type)) {
            int order = 0;
            for (GeneratedRubricDimension dimension : draft.rubricDimensions()) {
                jdbc.update("""
                        INSERT INTO question_rubric_dimension
                            (question_id, dimension_code, name, description, max_score, weight, dimension_order)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """, questionId, dimension.code(), dimension.name(), dimension.description(),
                        dimension.maxScore(), dimension.weight(), order++);
            }
        }
        jdbc.update("""
                INSERT INTO question_quality_audit
                    (question_id, quality_score, review_status, validation_errors, reviewer_user_id, note)
                VALUES (?, ?, 'DRAFT', ?::jsonb, null, 'auto quality gate')
                """, questionId, qualityScore, writeJson(errors));
        return questionId;
    }

    private List<String> qualityErrors(UUID topicId, GeneratedDraftQuestion draft) {
        List<String> errors = new ArrayList<>();
        if (draft == null) {
            errors.add("Generated question is null");
            return errors;
        }
        String type = normalizeType(draft.type());
        if (!QUESTION_TYPES.contains(type)) errors.add("Unsupported type: " + draft.type());
        if (blankToNull(draft.content()) == null) errors.add("Question content is blank");
        if (draft.sourceChunkId() == null) {
            errors.add("sourceChunkId is missing");
        } else {
            Boolean sourceApproved = jdbc.queryForObject("""
                    SELECT EXISTS (
                        SELECT 1
                        FROM knowledge_chunk kc
                        JOIN knowledge_source ks ON ks.id = kc.source_id
                        WHERE kc.id = ? AND kc.topic_id = ? AND ks.is_approved = TRUE)
                    """, Boolean.class, draft.sourceChunkId(), topicId);
            if (!Boolean.TRUE.equals(sourceApproved)) errors.add("sourceChunkId is not approved evidence for the topic");
            Instant crawledAt = jdbc.query("""
                    SELECT ks.crawled_at
                    FROM knowledge_chunk kc
                    JOIN knowledge_source ks ON ks.id = kc.source_id
                    WHERE kc.id = ?
                    """, rs -> rs.next() && rs.getTimestamp("crawled_at") != null
                    ? rs.getTimestamp("crawled_at").toInstant() : null, draft.sourceChunkId());
            if (crawledAt != null && crawledAt.isBefore(Instant.now().minus(properties.quality().staleSourceDays(), ChronoUnit.DAYS))) {
                errors.add("Evidence source is stale");
            }
        }
        Boolean duplicate = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM question
                    WHERE topic_id = ? AND type = ? AND lower(content) = lower(?))
                """, Boolean.class, topicId, type, blankToNull(draft.content()));
        if (Boolean.TRUE.equals(duplicate)) errors.add("Possible duplicate question content");

        if ("MULTIPLE_CHOICE".equals(type)) {
            if (draft.options() == null || draft.options().size() < 2 || draft.options().size() > 6) {
                errors.add("MULTIPLE_CHOICE requires 2-6 options");
            } else {
                long valid = draft.options().stream()
                        .filter(option -> option != null && blankToNull(option.label()) != null && blankToNull(option.text()) != null)
                        .count();
                if (valid != draft.options().size()) errors.add("Option label/text cannot be blank");
            }
            if (blankToNull(draft.correctAnswer()) == null) errors.add("correctAnswer is required");
            if (draft.options() != null && draft.correctAnswer() != null
                    && draft.options().stream().noneMatch(option -> draft.correctAnswer().equals(option.label()))) {
                errors.add("correctAnswer must match one option label");
            }
        }
        if ("CODE_COMPLETION".equals(type)) {
            if (blankToNull(draft.expectedSignature()) == null) errors.add("expectedSignature is required");
            if (draft.testCases() == null || draft.testCases().isEmpty()) errors.add("CODE_COMPLETION requires testCases");
        }
        if ("OPEN_ENDED".equals(type)) {
            if (draft.rubricDimensions() == null || draft.rubricDimensions().size() < 2) {
                errors.add("OPEN_ENDED requires at least two rubric dimensions");
            }
        }
        return List.copyOf(errors);
    }

    private static boolean hasBlockingErrors(List<String> errors) {
        return errors.stream().anyMatch(error -> !error.startsWith("Possible duplicate")
                && !error.startsWith("Evidence source is stale"));
    }

    private BigDecimal qualityScore(List<String> errors) {
        int score = 100;
        for (String error : errors) {
            if (error.startsWith("Possible duplicate")) score -= 25;
            else if (error.startsWith("Evidence source is stale")) score -= 15;
            else score -= 20;
        }
        return BigDecimal.valueOf(Math.max(0, score)).setScale(2, RoundingMode.HALF_UP);
    }

    private String buildGenerationContext(UUID topicId, int count, List<String> types) {
        List<Map<String, Object>> chunks = jdbc.query("""
                SELECT kc.id, kc.content, ks.title, ks.url
                FROM knowledge_chunk kc
                JOIN knowledge_source ks ON ks.id = kc.source_id
                WHERE kc.topic_id = ? AND ks.is_approved = TRUE
                ORDER BY kc.created_at DESC
                LIMIT 8
                """, (rs, row) -> Map.of(
                "chunkId", rs.getObject("id", UUID.class).toString(),
                "content", rs.getString("content"),
                "title", rs.getString("title"),
                "url", rs.getString("url")), topicId);
        if (chunks.isEmpty()) throw conflict("No approved knowledge chunks available for topic " + topicId);
        return """
                topicId=%s
                count=%s
                types=%s
                chunks=%s
                """.formatted(topicId, count, types, writeJson(chunks));
    }

    private CodingSubmissionResult findExistingCoding(UUID userId, UUID questionId, String key) {
        return jdbc.query("""
                SELECT id, compile_success, runtime_success, passed_tests, total_tests, score, static_hints, result_payload
                FROM coding_submission
                WHERE user_id = ? AND question_id = ? AND idempotency_key = ?
                """, rs -> {
            if (!rs.next()) return null;
            return new CodingSubmissionResult(
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
        if (tests.isEmpty()) throw badRequest("Coding question has no executable test cases");
        return tests;
    }

    private SubjectiveGradeResult findExistingSubjective(UUID userId, UUID questionId, String responseHash) {
        return jdbc.query("""
                SELECT id, total_score, feedback, rubric_scores
                FROM subjective_grading_result
                WHERE user_id = ? AND question_id = ? AND response_hash = ?
                """, rs -> {
            if (!rs.next()) return null;
            List<DimensionScore> dimensions = readDimensionScores(rs.getString("rubric_scores"));
            BigDecimal max = dimensions.stream().map(DimensionScore::maxScore).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal total = rs.getBigDecimal("total_score");
            BigDecimal normalized = max.compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ZERO
                    : total.divide(max, 3, RoundingMode.HALF_UP);
            return new SubjectiveGradeResult(
                    rs.getObject("id", UUID.class), questionId, total, max, normalized,
                    rs.getString("feedback"), dimensions, true);
        }, userId, questionId, responseHash);
    }

    private void applyCodingBktOnce(UUID submissionId, UUID userId, UUID topicId, boolean correct) {
        int updated = jdbc.update("""
                UPDATE coding_submission
                SET bkt_applied = TRUE
                WHERE id = ? AND bkt_applied = FALSE
                """, submissionId);
        if (updated == 1) skills.recordResponse(userId, topicId, correct);
    }

    private void applySubjectiveBktOnce(UUID gradingId, UUID userId, UUID topicId, boolean correct) {
        int updated = jdbc.update("""
                UPDATE subjective_grading_result
                SET bkt_applied = TRUE
                WHERE id = ? AND bkt_applied = FALSE
                """, gradingId);
        if (updated == 1) skills.recordResponse(userId, topicId, correct);
    }

    private QuestionTypeRow codingQuestion(UUID questionId) {
        return typedQuestion(questionId, "CODE_COMPLETION");
    }

    private QuestionTypeRow openEndedQuestion(UUID questionId) {
        return typedQuestion(questionId, "OPEN_ENDED");
    }

    private QuestionTypeRow typedQuestion(UUID questionId, String type) {
        return jdbc.query("""
                SELECT id, topic_id, type
                FROM question WHERE id = ?
                """, (rs, row) -> new QuestionTypeRow(
                rs.getObject("id", UUID.class), rs.getObject("topic_id", UUID.class),
                rs.getString("type")), questionId).stream().findFirst()
                .map(row -> {
                    if (!type.equals(row.type())) throw badRequest("Question type is not " + type);
                    return row;
                })
                .orElseThrow(() -> notFound("Question was not found"));
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

    private List<DimensionScore> readDimensionScores(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node == null || !node.isArray()) return List.of();
            List<DimensionScore> values = new ArrayList<>();
            for (JsonNode item : node) {
                values.add(new DimensionScore(
                        item.path("code").asText(),
                        item.path("score").decimalValue(),
                        item.path("maxScore").decimalValue(),
                        item.path("feedback").asText("")));
            }
            return List.copyOf(values);
        } catch (Exception exception) {
            return List.of();
        }
    }

    private static String normalizeType(String type) {
        return type == null ? "" : type.trim().toUpperCase(Locale.ROOT);
    }

    private static String blankToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isBlank() ? null : trimmed;
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

    private void validateAssessmentOwnership(UUID userId, UUID assessmentId) {
        if (assessmentId == null) throw badRequest("assessmentId is required");
        Boolean exists = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM assessment_session WHERE id = ? AND user_id = ?)
                """, Boolean.class, assessmentId, userId);
        if (!Boolean.TRUE.equals(exists)) throw notFound("Assessment was not found");
    }

    private FrontendCodingResult toFrontendResult(CodingSubmissionResult stored) {
        List<String> tests = stored.tests().stream()
                .map(test -> test.passed()
                        ? "测试 #" + (test.index() + 1) + " 通过。"
                        : "测试 #" + (test.index() + 1) + " 未通过："
                        + (test.error() == null ? ("期望[" + test.expectedOutput() + "]，实际[" + test.actualOutput() + "]")
                        : test.error()))
                .toList();
        String status = stored.compileSuccess() && stored.runtimeSuccess() && stored.passedTests() == stored.totalTests()
                ? "SUCCESS" : "FAILED";
        return new FrontendCodingResult(
                status,
                stored.compileSuccess() ? List.of() : List.of("编译失败。请检查语法。"),
                stored.runtimeSuccess() ? List.of() : List.of("运行失败。"),
                tests,
                stored.staticHints(),
                "");
    }

    private FrontendCodingResult toFrontendResult(CodeSandboxService.SandboxResult sandboxResult, List<String> staticHints) {
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
        return new FrontendCodingResult(status, compile, runtime, tests, staticHints, output);
    }


    private List<Map<String, Object>> safeOptions(String optionsJson) {
        try {
            JsonNode root = objectMapper.readTree(optionsJson);
            if (root == null || !root.isArray()) return List.of();
            List<Map<String, Object>> options = new ArrayList<>();
            for (JsonNode option : root) {
                options.add(Map.of(
                        "label", option.path("label").asText(""),
                        "text", option.path("text").asText("")));
            }
            return List.copyOf(options);
        } catch (Exception exception) {
            return List.of();
        }
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

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
    private record AssessmentScoreRow(BigDecimal score) {}

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

    private record QuestionMeta(UUID id, String reviewStatus, BigDecimal qualityScore) {}
    private record QuestionTypeRow(UUID id, UUID topicId, String type) {}
    private record RubricDimension(String code, String name, BigDecimal maxScore, BigDecimal weight, String description) {}
    private record IrtStat(UUID questionId, String type, int sampleSize, double successRate) {}
}
