package com.xammer.cloud.controller;

import com.xammer.cloud.dto.AiAdvisorSummaryDto;
import com.xammer.cloud.service.AiAdvisorService;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
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
    private final SimpMessagingTemplate messagingTemplate;


    public AiAdvisorController(AiAdvisorService aiAdvisorService, SimpMessagingTemplate messagingTemplate) {
        this.aiAdvisorService = aiAdvisorService;
        this.messagingTemplate = messagingTemplate;
    }

    @GetMapping("/summary")
    public ResponseEntity<?> getDashboardSummary(@RequestParam String accountId) {
        try {
            AiAdvisorSummaryDto summary = aiAdvisorService.getDashboardSummary(accountId).get();
            return ResponseEntity.ok(summary);
        } catch (InterruptedException | ExecutionException e) {
            Thread.currentThread().interrupt();
            return ResponseEntity.status(500).body(Map.of("error", "Failed to generate AI summary."));
        }
    }

    /**
     * Handles interactive chat messages for the AI Advisor.
     * Receives a message from a client, gets a response from the AI service,
     * and sends the response back to the specific client.
     */
    @MessageMapping("/advisor/chat")
    public void handleChatMessage(@Payload Map<String, String> message, SimpMessageHeaderAccessor headerAccessor) {
        String userQuestion = message.get("question");
        String accountId = message.get("accountId");
        String sessionId = headerAccessor.getSessionId();
        String destination = String.format("/topic/advisor/response/%s", sessionId);

        if (userQuestion == null || accountId == null || sessionId == null) {
            messagingTemplate.convertAndSend(destination, Map.of("error", "Invalid message format."));
            return;
        }

        aiAdvisorService.getInteractiveResponse(userQuestion, accountId)
            .thenAccept(response -> {
                messagingTemplate.convertAndSend(destination, response);
            })
            .exceptionally(ex -> {
                messagingTemplate.convertAndSend(destination, Map.of("error", "Failed to get AI response."));
                return null;
            });
    }
}