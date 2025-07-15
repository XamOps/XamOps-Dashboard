package com.xammer.cloud.controller;

import com.xammer.cloud.service.AiAdvisorService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.ExecutionException;

@RestController
@RequestMapping("/api/advisor")
public class AiAdvisorController {

    private final AiAdvisorService aiAdvisorService;

    public AiAdvisorController(AiAdvisorService aiAdvisorService) {
        this.aiAdvisorService = aiAdvisorService;
    }

    @GetMapping("/summary")
    public ResponseEntity<Map<String, String>> getDashboardSummary(@RequestParam String accountId) {
        try {
            String summary = aiAdvisorService.getDashboardSummary(accountId).get();
            return ResponseEntity.ok(Map.of("summary", summary));
        } catch (InterruptedException | ExecutionException e) {
            Thread.currentThread().interrupt();
            return ResponseEntity.status(500).body(Map.of("error", "Failed to generate AI summary."));
        }
    }
}
