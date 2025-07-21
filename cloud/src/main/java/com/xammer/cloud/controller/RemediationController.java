package com.xammer.cloud.controller;

import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.service.RemediationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/remediation")
public class RemediationController {

    private final RemediationService remediationService;

    public RemediationController(RemediationService remediationService) {
        this.remediationService = remediationService;
    }

    @PostMapping("/steps")
    public ResponseEntity<Map<String, String>> getRemediationSteps(@RequestBody DashboardData.SecurityFinding finding) {
        String stepsHtml = remediationService.getRemediationForFinding(finding);
        return ResponseEntity.ok(Map.of("steps", stepsHtml));
    }
}
