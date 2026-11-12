package com.springtechkb.ai.internal;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/operations")
class OperationsSummaryController {

    private final OperationsSummaryService operations;

    OperationsSummaryController(OperationsSummaryService operations) {
        this.operations = operations;
    }

    @GetMapping("/summary")
    @PreAuthorize("hasRole('ADMIN')")
    OperationsSummaryService.Summary summary(
            @RequestParam(defaultValue = "7") @Min(1) @Max(30) int days) {
        return operations.summary(days);
    }
}
