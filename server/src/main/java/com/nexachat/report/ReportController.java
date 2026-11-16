package com.nexachat.report;

import com.nexachat.common.dto.JwtPrincipal;
import com.nexachat.common.error.ApiErrorCode;
import com.nexachat.common.error.ApiException;
import com.nexachat.report.dto.CreateReportRequest;
import com.nexachat.report.dto.ReportPageResponse;
import com.nexachat.report.dto.ReportResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Abuse-report REST endpoints (V38 safety), caller-scoped:
 *
 * <ul>
 *   <li>{@code POST /reports} → 201 on a new filing, 200 when the
 *       reporter already holds an OPEN report on the same target.</li>
 *   <li>{@code GET /reports/mine?limit=&cursor=} → 200
 *       {@link ReportPageResponse}, newest first.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/reports")
public class ReportController {

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    @PostMapping
    public ResponseEntity<ReportResponse> submit(
            @AuthenticationPrincipal JwtPrincipal principal,
            @Valid @RequestBody CreateReportRequest request) {
        ReportService.Result result = reportService.submit(requireUserId(principal), request);
        return ResponseEntity
                .status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(result.report());
    }

    @GetMapping("/mine")
    public ReportPageResponse listMine(
            @AuthenticationPrincipal JwtPrincipal principal,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "cursor", required = false) String cursor) {
        return reportService.listMine(requireUserId(principal), limit, cursor);
    }

    private static UUID requireUserId(JwtPrincipal principal) {
        if (principal == null) {
            throw ApiException.of(ApiErrorCode.UNAUTHORIZED, "Authentication required");
        }
        return principal.userId();
    }
}
