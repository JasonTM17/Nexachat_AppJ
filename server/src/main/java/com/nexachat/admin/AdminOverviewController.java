package com.nexachat.admin;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** KPI cards plus the 7/30/90-day series for the admin dashboard. */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminOverviewController {

    private final AdminOverviewService overviewService;

    public AdminOverviewController(AdminOverviewService overviewService) {
        this.overviewService = overviewService;
    }

    @GetMapping("/overview")
    public AdminOverviewService.Overview overview(@RequestParam(defaultValue = "7") int days) {
        AdminGuard.requireStaff();
        return overviewService.overview(days);
    }
}
