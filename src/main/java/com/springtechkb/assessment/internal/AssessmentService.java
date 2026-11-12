package com.springtechkb.assessment.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.springtechkb.HttpErrors;
import com.springtechkb.knowledge.KnowledgeGraph;
import com.springtechkb.knowledge.ClosureValidator;
import com.springtechkb.search.KnowledgeQueries;
import com.springtechkb.search.KnowledgeQueries.Source;
import com.springtechkb.user.UserSkillProfile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
class AssessmentService {

    static final int MAX_REQUESTED_TOPICS = 8;
    static final int MAX_QUESTIONS = 10;

    private final JdbcTemplate jdbc;
    private final KnowledgeGraph graph;
    private final UserSkillProfile skills;
    private final JsonMapper objectMapper;
    private final KnowledgeQueries knowledge;

    private static final RowMapper<SessionRow> SESSION_ROW_MAPPER = (rs, rowNum) -> new SessionRow(
            rs.getObject("id", UUID.class), rs.getString("session_type"), rs.getString("status"),
            rs.getInt("total_questions"), (Integer) rs.getObject("correct_count"),
            rs.getBigDecimal("score"), rs.getTimestamp("started_at").toInstant(),
            rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toInstant(),
            rs.getTimestamp("submitted_at") == null ? null : rs.getTimestamp("submitted_at").toInstant());

    AssessmentService(
            JdbcTemplate jdbc,
            KnowledgeGraph graph,
            UserSkillProfile skills,
            JsonMapper objectMapper,
            KnowledgeQueries knowledge) {
        this.jdbc = jdbc;
        this.graph = graph;
        this.skills = skills;
        this.objectMapper = objectMapper;
        this.knowledge = knowledge;
    }

    @Transactional
    AssessmentView start(UUID userId, InitialAssessmentRequest request) {
        List<UUID> requestedTopics = request.topicIds();
        if (requestedTopics == null || requestedTopics.isEmpty() || requestedTopics.size() > MAX_REQUESTED_TOPICS
                || requestedTopics.stream().anyMatch(Objects::isNull)
                || new HashSet<>(requestedTopics).size() != requestedTopics.size()) {
            throw HttpErrors.badRequest("Choose between 1 and 8 distinct assessment topics");
        }
        List<KnowledgeGraph.Topic> closure = graph.orderedClosure(requestedTopics);
        if (closure == null || closure.isEmpty() || closure.size() > 40) {
            throw HttpErrors.badRequest("The selected topics have no valid prerequisite closure or exceed the topic limit");
        }
        Set<UUID> closureIds = new HashSet<>();
        for (KnowledgeGraph.Topic topic : closure) {
            if (topic == null || topic.id() == null || !closureIds.add(topic.id())) {
                throw HttpErrors.badRequest("The knowledge graph returned an invalid prerequisite closure");
            }
        }
        if (!closureIds.containsAll(requestedTopics)) {
            throw HttpErrors.badRequest("Every requested topic must be present in its prerequisite closure");
        }
        validateClosureOrder(closure);
        String topicJson = writeJson(closureIds);
        List<BankQuestion> questions = jdbc.query("""
                SELECT q.id, q.topic_id, q.content, q.options, q.correct_answer, q.explanation, ks.id AS source_id
                FROM question q
                JOIN knowledge_chunk kc ON kc.id = q.source_chunk_id AND kc.topic_id = q.topic_id
                JOIN knowledge_source ks ON ks.id = kc.source_id AND ks.is_approved = TRUE
                WHERE q.is_reviewed = TRUE AND q.review_status = 'APPROVED' AND q.type = 'MULTIPLE_CHOICE'
                  AND q.topic_id IN (SELECT value::uuid FROM jsonb_array_elements_text(?::jsonb))
                ORDER BY q.id
                LIMIT 50
                """, (rs, rowNum) -> new BankQuestion(
                rs.getObject("id", UUID.class), rs.getObject("topic_id", UUID.class),
                rs.getString("content"), rs.getString("options"), rs.getString("correct_answer"),
                rs.getString("explanation"), rs.getObject("source_id", UUID.class)), topicJson);
        List<BankQuestion> usableQuestions = questions.stream().filter(this::isUsable)
                .limit(MAX_QUESTIONS).toList();
        if (usableQuestions.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "No reviewed multiple-choice questions are available for the selected topics. " +
                            "An ADMIN must add manually reviewed questions at POST /api/assessments/questions.");
        }

        UUID sessionId = jdbc.queryForObject("""
                INSERT INTO assessment_session (user_id, session_type, status, total_questions)
                VALUES (?, 'INITIAL_DIAGNOSTIC', 'IN_PROGRESS', ?)
                RETURNING id
                """, UUID.class, userId, usableQuestions.size());
        for (int index = 0; index < usableQuestions.size(); index++) {
            jdbc.update("""
                    INSERT INTO assessment_session_question (session_id, question_id, source_id, question_order)
                    VALUES (?, ?, ?, ?)
                    """, sessionId, usableQuestions.get(index).id(), usableQuestions.get(index).sourceId(), index);
        }
        return get(userId, sessionId);
    }

    AssessmentView get(UUID userId, UUID sessionId) {
        List<SessionRow> rows = jdbc.query("""
                SELECT id, session_type, status, total_questions, correct_count, score,
                       started_at, completed_at, submitted_at
                FROM assessment_session WHERE id = ? AND user_id = ?
                """, SESSION_ROW_MAPPER, sessionId, userId);
        if (rows.isEmpty()) throw HttpErrors.notFound("Assessment was not found");
        SessionRow session = rows.getFirst();
        boolean submitted = "COMPLETED".equals(session.status());
        List<AssessmentQuestion> questions = jdbc.query("""
                SELECT q.id, q.topic_id, q.type, q.content, q.options, q.correct_answer, q.explanation, sq.source_id,
                       a.user_response, a.is_correct
                FROM assessment_session_question sq
                JOIN question q ON q.id = sq.question_id
                LEFT JOIN user_answer a ON a.session_id = sq.session_id AND a.question_id = q.id
                WHERE sq.session_id = ? ORDER BY sq.question_order
                """, (rs, rowNum) -> {
            List<SafeOption> options = parseOptions(rs.getString("options")).stream()
                    .map(option -> new SafeOption(option.label(), option.text())).toList();
            String userAnswer = rs.getString("user_response");
            Boolean isCorrect = userAnswer == null ? null : rs.getBoolean("is_correct");
            Source source = resolveSource(rs.getObject("source_id", UUID.class));
            String type = rs.getString("type");
            String normalizedType = "CODE_COMPLETION".equals(type) ? "CODING"
                    : "OPEN_ENDED".equals(type) ? "OPEN_ENDED" : "MCQ";
            String starterCode = "CODING".equals(normalizedType)
                    ? """
                    public class Solution {
                        public static String solve(String input) {
                            // TODO: implement
                            return input == null ? "" : input;
                        }
                    }
                    """
                    : null;
            return new AssessmentQuestion(
                    rs.getObject("id", UUID.class), rs.getObject("topic_id", UUID.class),
                    normalizedType, rs.getString("content"), options, "CODING".equals(normalizedType) ? "java" : null,
                    starterCode, userAnswer, isCorrect,
                    submitted ? rs.getString("correct_answer") : null,
                    submitted ? rs.getString("explanation") : null, source);
        }, sessionId);
        return new AssessmentView(session.id(), session.sessionType(), session.status(),
                session.totalQuestions(), submitted ? session.correctCount() : null,
                submitted ? session.score() : null, session.startedAt(), session.completedAt(),
                session.submittedAt(), questions);
    }

    @Transactional
    AnswerResult answer(UUID userId, UUID sessionId, AnswerRequest request) {
        if (request.questionId() == null || request.answer() == null || request.answer().isBlank()
                || request.answer().length() > 4000) {
            throw HttpErrors.badRequest("Provide a questionId and an answer with at most 4000 characters");
        }
        List<SessionRow> sessions = jdbc.query("""
                SELECT id, session_type, status, total_questions, correct_count, score,
                       started_at, completed_at, submitted_at
                FROM assessment_session WHERE id = ? AND user_id = ? FOR UPDATE
                """, SESSION_ROW_MAPPER, sessionId, userId);
        if (sessions.isEmpty()) throw HttpErrors.notFound("Assessment was not found");
        SessionRow session = sessions.getFirst();
        List<UUID> evidenceSources = jdbc.query("""
                SELECT source_id FROM assessment_session_question
                WHERE session_id = ? AND question_id = ?
                """, (rs, rowNum) -> rs.getObject("source_id", UUID.class), sessionId, request.questionId());
        if (evidenceSources.isEmpty()) throw HttpErrors.notFound("Question is not part of this assessment");
        resolveSource(evidenceSources.getFirst());
        List<ExistingAnswer> existing = jdbc.query("""
                SELECT user_response, is_correct FROM user_answer
                WHERE session_id = ? AND question_id = ?
                """, (rs, rowNum) -> new ExistingAnswer(rs.getString("user_response"), rs.getBoolean("is_correct")),
                sessionId, request.questionId());
        if (!existing.isEmpty()) {
            ExistingAnswer previous = existing.getFirst();
            if (!previous.response().equals(request.answer())) {
                throw HttpErrors.conflict("An answer has already been submitted for this question");
            }
            return new AnswerResult(request.questionId(), previous.response(), previous.correct(),
                    "COMPLETED".equals(session.status()), assessmentScore(userId, sessionId));
        }
        if (!"IN_PROGRESS".equals(session.status())) {
            throw HttpErrors.conflict("This assessment is no longer accepting answers");
        }
        List<AnswerQuestion> selected = jdbc.query("""
                SELECT q.id, q.topic_id, q.type, q.options
                FROM assessment_session_question sq
                JOIN question q ON q.id = sq.question_id
                WHERE sq.session_id = ? AND q.id = ?
                """, (rs, rowNum) -> new AnswerQuestion(
                rs.getObject("id", UUID.class), rs.getObject("topic_id", UUID.class),
                rs.getString("type"), rs.getString("options")), sessionId, request.questionId());
        if (selected.isEmpty()) throw HttpErrors.notFound("Question is not part of this assessment");
        AnswerQuestion question = selected.getFirst();
        String type = question.type();
        if ("CODE_COMPLETION".equals(type)) {
            throw HttpErrors.conflict("Coding questions must be submitted from the coding endpoint");
        }
        Boolean correct = null;
        if ("MULTIPLE_CHOICE".equals(type)) {
            List<InternalOption> options = parseOptions(question.options());
            if (options.stream().noneMatch(option -> option.label().equals(request.answer()))) {
                throw HttpErrors.badRequest("The answer must match one of the question's option labels");
            }
            correct = options.stream().anyMatch(option -> option.label().equals(request.answer()) && option.correct());
        }
        jdbc.update("""
                INSERT INTO user_answer (session_id, question_id, user_response, is_correct)
                VALUES (?, ?, ?, ?)
                """, sessionId, request.questionId(), request.answer(), correct);
        if (correct != null) {
            skills.recordResponse(userId, question.topicId(), correct);
        }

        int answered = jdbc.queryForObject("""
                SELECT count(*) FROM user_answer WHERE session_id = ?
                """, Integer.class, sessionId);
        int correctCount = jdbc.queryForObject("""
                SELECT count(*) FROM user_answer WHERE session_id = ? AND is_correct = TRUE
                """, Integer.class, sessionId);
        boolean completed = answered >= session.totalQuestions();
        BigDecimal score = completed
                ? BigDecimal.valueOf(correctCount * 100L).divide(
                        BigDecimal.valueOf(session.totalQuestions()), 2, RoundingMode.HALF_UP)
                : null;
        jdbc.update("""
                UPDATE assessment_session
                SET correct_count = ?, score = ?, status = CASE WHEN ? THEN 'COMPLETED' ELSE status END,
                    completed_at = CASE WHEN ? THEN now() ELSE completed_at END,
                    submitted_at = CASE WHEN ? THEN now() ELSE submitted_at END
                WHERE id = ?
                """, completed ? correctCount : null, score, completed, completed, completed, sessionId);
        return new AnswerResult(request.questionId(), request.answer(), Boolean.TRUE.equals(correct), completed, score);
    }

    @Transactional
    void markCodingSubmission(UUID userId, UUID assessmentId, UUID questionId, String userResponse, boolean correct) {
        List<SessionRow> sessions = jdbc.query("""
                SELECT id, session_type, status, total_questions, correct_count, score,
                       started_at, completed_at, submitted_at
                FROM assessment_session WHERE id = ? AND user_id = ? FOR UPDATE
                """, SESSION_ROW_MAPPER, assessmentId, userId);
        if (sessions.isEmpty()) throw HttpErrors.notFound("Assessment was not found");
        SessionRow session = sessions.getFirst();
        if (!"IN_PROGRESS".equals(session.status()) && !"COMPLETED".equals(session.status())) {
            throw HttpErrors.conflict("This assessment is no longer accepting answers");
        }
        List<AnswerQuestion> selected = jdbc.query("""
                SELECT q.id, q.topic_id, q.type, q.options
                FROM assessment_session_question sq
                JOIN question q ON q.id = sq.question_id
                WHERE sq.session_id = ? AND q.id = ?
                """, (rs, rowNum) -> new AnswerQuestion(
                rs.getObject("id", UUID.class), rs.getObject("topic_id", UUID.class),
                rs.getString("type"), rs.getString("options")), assessmentId, questionId);
        if (selected.isEmpty()) throw HttpErrors.notFound("Question is not part of this assessment");
        AnswerQuestion question = selected.getFirst();
        if (!"CODE_COMPLETION".equals(question.type())) {
            throw HttpErrors.badRequest("Question is not a coding question");
        }
        List<UUID> evidenceSources = jdbc.query("""
                SELECT source_id FROM assessment_session_question
                WHERE session_id = ? AND question_id = ?
                """, (rs, rowNum) -> rs.getObject("source_id", UUID.class), assessmentId, questionId);
        if (evidenceSources.isEmpty()) throw HttpErrors.notFound("Question is not part of this assessment");
        resolveSource(evidenceSources.getFirst());

        List<ExistingAnswer> existing = jdbc.query("""
                SELECT user_response, is_correct FROM user_answer
                WHERE session_id = ? AND question_id = ?
                """, (rs, rowNum) -> new ExistingAnswer(rs.getString("user_response"), rs.getBoolean("is_correct")),
                assessmentId, questionId);
        if (!existing.isEmpty()) {
            ExistingAnswer previous = existing.getFirst();
            if (!Objects.equals(previous.response(), userResponse)) {
                throw HttpErrors.conflict("A coding answer has already been submitted for this question");
            }
        } else {
            jdbc.update("""
                    INSERT INTO user_answer (session_id, question_id, user_response, is_correct)
                    VALUES (?, ?, ?, ?)
                    """, assessmentId, questionId, userResponse, correct);
        }
        int answered = jdbc.queryForObject("SELECT count(*) FROM user_answer WHERE session_id = ?",
                Integer.class, assessmentId);
        int correctCount = jdbc.queryForObject("""
                SELECT count(*) FROM user_answer WHERE session_id = ? AND is_correct = TRUE
                """, Integer.class, assessmentId);
        boolean completed = answered >= session.totalQuestions();
        BigDecimal score = completed
                ? BigDecimal.valueOf(correctCount * 100L).divide(
                        BigDecimal.valueOf(session.totalQuestions()), 2, RoundingMode.HALF_UP)
                : null;
        jdbc.update("""
                UPDATE assessment_session
                SET correct_count = ?, score = ?, status = CASE WHEN ? THEN 'COMPLETED' ELSE status END,
                    completed_at = CASE WHEN ? THEN now() ELSE completed_at END,
                    submitted_at = CASE WHEN ? THEN now() ELSE submitted_at END
                WHERE id = ?
                """, completed ? correctCount : null, score, completed, completed, completed, assessmentId);
    }

    @Transactional
    QuestionCreated addReviewedQuestion(ManualQuestionRequest request) {
        validateManualQuestion(request);
        if (!Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM knowledge_topic WHERE id = ?)", Boolean.class, request.topicId()))) {
            throw HttpErrors.notFound("Knowledge topic was not found");
        }
        if (request.sourceChunkId() == null || !Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM knowledge_chunk kc
                    JOIN knowledge_source ks ON ks.id = kc.source_id
                    WHERE kc.id = ? AND kc.topic_id = ? AND ks.is_approved = TRUE)
                """, Boolean.class, request.sourceChunkId(), request.topicId()))) {
            throw HttpErrors.badRequest("sourceChunkId must identify approved evidence linked to this topic");
        }
        List<Map<String, Object>> options = new ArrayList<>();
        for (ManualOption option : request.options()) {
            options.add(Map.of(
                    "label", option.label().trim(),
                    "text", option.text().trim(),
                    "is_correct", option.label().trim().equals(request.correctAnswer().trim())));
        }
        UUID id = jdbc.queryForObject("""
                INSERT INTO question (topic_id, source_chunk_id, type, difficulty, content, options,
                                      correct_answer, explanation, spring_module, spring_version, is_reviewed,
                                      quality_score, review_status, reviewed_at)
                VALUES (?, ?, 'MULTIPLE_CHOICE', ?, ?, ?::jsonb, ?, ?, ?, ?, TRUE, 100, 'APPROVED', now())
                RETURNING id
                """, UUID.class, request.topicId(), request.sourceChunkId(),
                request.difficulty() == null ? "MEDIUM" : request.difficulty(),
                request.content().trim(), writeJson(options), request.correctAnswer().trim(),
                request.explanation(), request.springModule(), request.springVersion());
        jdbc.update("""
                INSERT INTO question_quality_audit
                    (question_id, quality_score, review_status, validation_errors, reviewer_user_id, note)
                VALUES (?, 100, 'APPROVED', '[]'::jsonb, null, 'manual reviewed question')
                """, id);
        return new QuestionCreated(id, request.topicId(), true);
    }

    private void validateManualQuestion(ManualQuestionRequest request) {
        if (request == null || request.topicId() == null || request.content() == null
                || request.content().isBlank() || request.options() == null || request.options().size() < 2
                || request.options().size() > 6 || request.correctAnswer() == null
                || request.correctAnswer().isBlank()) {
            throw HttpErrors.badRequest("A reviewed multiple-choice question needs a topic, prompt, 2-6 options, and correct option label");
        }
        Set<String> labels = new HashSet<>();
        for (ManualOption option : request.options()) {
            if (option == null || option.label() == null || option.label().isBlank() || option.label().length() > 20
                    || option.text() == null || option.text().isBlank() || option.text().length() > 1000
                    || !labels.add(option.label().trim())) {
                throw HttpErrors.badRequest("Question options need unique labels and non-empty text");
            }
        }
        if (!labels.contains(request.correctAnswer().trim())) {
            throw HttpErrors.badRequest("correctAnswer must match an option label");
        }
        if (request.content().length() > 5000
                || request.explanation() != null && request.explanation().length() > 5000) {
            throw HttpErrors.badRequest("Question content and explanation must be 5000 characters or fewer");
        }
        if (request.difficulty() != null && !Set.of("EASY", "MEDIUM", "HARD").contains(request.difficulty())) {
            throw HttpErrors.badRequest("difficulty must be EASY, MEDIUM, or HARD");
        }
    }

    private boolean isUsable(BankQuestion question) {
        if (question.content() == null || question.content().isBlank() || question.sourceId() == null) return false;
        try {
            List<InternalOption> options = parseOptions(question.options());
            return options.size() >= 2 && options.stream().filter(InternalOption::correct).count() == 1;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static void validateClosureOrder(List<KnowledgeGraph.Topic> closure) {
        ClosureValidator.assertTopologicalOrder(closure);
    }

    private List<InternalOption> parseOptions(String json) {
        try {
            if (json == null || json.isBlank()) return List.of();
            JsonNode root = objectMapper.readTree(json);
            if (root == null || !root.isArray()) throw HttpErrors.badRequest("Question options are invalid");
            List<InternalOption> options = new ArrayList<>();
            for (JsonNode node : root) {
                if (!node.isObject() || !node.path("label").isTextual() || !node.path("text").isTextual()) {
                    throw HttpErrors.badRequest("Question options are invalid");
                }
                options.add(new InternalOption(node.path("label").textValue(), node.path("text").textValue(),
                        node.path("is_correct").asBoolean(node.path("isCorrect").asBoolean(false))));
            }
            return options;
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Stored question options are invalid", exception);
        }
    }

    private BigDecimal assessmentScore(UUID userId, UUID sessionId) {
        return jdbc.query("""
                SELECT score FROM assessment_session WHERE id = ? AND user_id = ?
                """, rs -> rs.next() ? rs.getBigDecimal("score") : null, sessionId, userId);
    }

    private Source resolveSource(UUID sourceId) {
        if (sourceId == null) {
            throw new ResponseStatusException(HttpStatus.GONE, "Assessment evidence is no longer available");
        }
        try {
            return knowledge.source(sourceId);
        } catch (ResponseStatusException exception) {
            if (exception.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
                throw new ResponseStatusException(HttpStatus.GONE, "Assessment evidence was revoked");
            }
            throw exception;
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not serialize assessment data", exception);
        }
    }

    record InitialAssessmentRequest(
            @jakarta.validation.constraints.NotEmpty
            @jakarta.validation.constraints.Size(max = MAX_REQUESTED_TOPICS)
            List<@jakarta.validation.constraints.NotNull UUID> topicIds) {}
    record AnswerRequest(
            @jakarta.validation.constraints.NotNull UUID questionId,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 4000) String answer) {}
    record ManualQuestionRequest(
            @jakarta.validation.constraints.NotNull UUID topicId,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 5000) String content,
            @jakarta.validation.constraints.NotEmpty @jakarta.validation.constraints.Size(min = 2, max = 6)
            List<@jakarta.validation.Valid ManualOption> options,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 20) String correctAnswer,
            @jakarta.validation.constraints.Size(max = 5000) String explanation,
            @jakarta.validation.constraints.Pattern(regexp = "EASY|MEDIUM|HARD") String difficulty,
            @jakarta.validation.constraints.Size(max = 100) String springModule,
            @jakarta.validation.constraints.Size(max = 50) String springVersion,
            @jakarta.validation.constraints.NotNull UUID sourceChunkId) {}
    record ManualOption(
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 20) String label,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 1000) String text) {}
    record AssessmentView(UUID id, String sessionType, String status, int totalQuestions, Integer correctCount,
                          BigDecimal score, Instant startedAt, Instant completedAt, Instant submittedAt,
                          List<AssessmentQuestion> questions) {}
    record AssessmentQuestion(UUID id, UUID topicId, String type, String content, List<SafeOption> options,
                              String language, String starterCode, String userAnswer, Boolean isCorrect,
                              String correctAnswer, String explanation, Source source) {}
    record SafeOption(String label, String text) {}
    record AnswerResult(UUID questionId, String answer, boolean isCorrect, boolean completed, BigDecimal score) {}
    record QuestionCreated(UUID id, UUID topicId, boolean reviewed) {}
    private record SessionRow(UUID id, String sessionType, String status, int totalQuestions,
                              Integer correctCount, BigDecimal score, Instant startedAt,
                              Instant completedAt, Instant submittedAt) {}
    private record BankQuestion(UUID id, UUID topicId, String content, String options,
                                String correctAnswer, String explanation, UUID sourceId) {}
    private record AnswerQuestion(UUID id, UUID topicId, String type, String options) {}
    private record ExistingAnswer(String response, boolean correct) {}
    private record InternalOption(String label, String text, boolean correct) {}
}
