package com.xammer.cloud.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

/**
 * Service responsible for sending real-time dashboard updates to clients
 * via WebSockets.
 */
@Service
public class DashboardUpdateService {

    private static final Logger logger = LoggerFactory.getLogger(DashboardUpdateService.class);
    private final SimpMessagingTemplate messagingTemplate;

    @Autowired
    public DashboardUpdateService(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * Sends a payload to a specific WebSocket topic for a given account.
     * Clients subscribed to this topic will receive the update.
     *
     * @param accountId The ID of the account to which the update pertains.
     * @param topic     The specific topic suffix (e.g., "inventory", "kpis").
     * @param payload   The data to be sent as the update.
     */
    public void sendUpdate(String accountId, String topic, Object payload) {
        String destination = String.format("/topic/dashboard/%s/%s", accountId, topic);
        logger.info("Sending WebSocket update to destination: {}", destination);
        try {
            messagingTemplate.convertAndSend(destination, payload);
        } catch (Exception e) {
            logger.error("Failed to send WebSocket update to {}", destination, e);
        }
    }
}
