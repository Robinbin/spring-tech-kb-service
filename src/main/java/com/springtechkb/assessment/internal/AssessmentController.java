package com.springtechkb.assessment.internal;

import java.util.UUID;

import jakarta.validation.Valid;
import com.springtechkb.JwtPrincipalResolver;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/assessments")
class AssessmentController {

    private final AssessmentService assessments;
    private final AssessmentPhase3Service phase3;

    AssessmentController(AssessmentService assessments, AssessmentPhase3Service phase3) {
        this.assessments = assessments;
        this.phase3 = phase3;
    }

    @PostMapping("/initial")
    @ResponseStatus(HttpStatus.CREATED)
    AssessmentService.AssessmentView start(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody AssessmentService.InitialAssessmentRequest request) {
        return assessments.start(userId(jwt), request);
    }

    @GetMapping("/{assessmentId}")
    AssessmentService.AssessmentView get(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID assessmentId) {
        return assessments.get(userId(jwt), assessmentId);
    }

    @PostMapping("/{assessmentId}/answers")
    AssessmentService.AnswerResult answer(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID assessmentId,
            @Valid @RequestBody AssessmentService.AnswerRequest request) {
        return assessments.answer(userId(jwt), assessmentId, request);
    }

    @PostMapping("/questions")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    AssessmentService.QuestionCreated addQuestion(
            @Valid @RequestBody AssessmentService.ManualQuestionRequest request) {
        return assessments.addReviewedQuestion(request);
    }

    @PostMapping("/admin/question-batches")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    AssessmentPhase3Service.BatchResult generateBatch(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody AssessmentPhase3Service.BatchGenerateRequest request) {
        return phase3.generateBatch(userId(jwt), request);
    }

    @PostMapping("/admin/questions/{questionId}/review")
    @PreAuthorize("hasRole('ADMIN')")
    AssessmentPhase3Service.ReviewResult review(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID questionId,
            @Valid @RequestBody AssessmentPhase3Service.ReviewRequest request) {
        return phase3.reviewQuestion(userId(jwt), questionId, request);
    }

    @PostMapping("/coding/submissions")
    AssessmentPhase3Service.CodingSubmissionResult submitCoding(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody AssessmentPhase3Service.CodingSubmissionRequest request) {
        return phase3.submitCoding(userId(jwt), request);
    }

    @PostMapping("/open-ended/grades")
    AssessmentPhase3Service.SubjectiveGradeResult gradeOpenEnded(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody AssessmentPhase3Service.SubjectiveGradeRequest request) {
        return phase3.gradeOpenEnded(userId(jwt), request);
    }

    @PostMapping("/admin/irt/calibrate")
    @PreAuthorize("hasRole('ADMIN')")
    AssessmentPhase3Service.IrtCalibrationResult calibrate() {
        return phase3.calibrateIrt();
    }

    @GetMapping("/adaptive/next")
    AssessmentPhase3Service.AdaptiveQuestion adaptive(
            @AuthenticationPrincipal Jwt jwt,
            @org.springframework.web.bind.annotation.RequestParam UUID topicId) {
        return phase3.adaptiveNext(userId(jwt), topicId);
    }

    @GetMapping("/ability-report")
    AssessmentPhase3Service.AbilityReport abilityReport(@AuthenticationPrincipal Jwt jwt) {
        return phase3.abilityReport(userId(jwt));
    }

    @PostMapping("/{assessmentId}/coding/run")
    AssessmentPhase3Service.FrontendCodingResult runCodingForAssessment(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID assessmentId,
            @Valid @RequestBody AssessmentPhase3Service.FrontendCodingRequest request) {
        return phase3.runCodingPreview(userId(jwt), assessmentId, request);
    }

    @PostMapping("/{assessmentId}/coding/submit")
    AssessmentPhase3Service.FrontendCodingResult submitCodingForAssessment(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID assessmentId,
            @Valid @RequestBody AssessmentPhase3Service.FrontendCodingRequest request) {
        AssessmentPhase3Service.FrontendCodingResult result =
                phase3.submitCodingFromAssessment(userId(jwt), assessmentId, request);
        assessments.markCodingSubmission(userId(jwt), assessmentId, request.questionId(), request.code(),
                "SUCCESS".equals(result.status()));
        return result;
    }

    @GetMapping("/{assessmentId}/report")
    AssessmentPhase3Service.FrontendAbilityReport reportByAssessment(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID assessmentId) {
        return phase3.assessmentReport(userId(jwt), assessmentId);
    }

    private static UUID userId(Jwt jwt) {
        return JwtPrincipalResolver.userId(jwt);
    }
}
