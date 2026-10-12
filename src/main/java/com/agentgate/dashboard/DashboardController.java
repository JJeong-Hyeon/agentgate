package com.agentgate.dashboard;

import com.agentgate.dashboard.DashboardService.Period;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The console's operations overview (any role). */
@RestController
@RequestMapping("/api/v1/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

    @GetMapping
    public DashboardResponse get(@RequestParam(defaultValue = "24h") String period) {
        return dashboardService.get(Period.of(period));
    }
}
