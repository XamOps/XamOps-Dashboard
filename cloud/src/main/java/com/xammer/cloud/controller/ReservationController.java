package com.xammer.cloud.controller;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.CostByTagDto;
import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.dto.ReservationDto;
import com.xammer.cloud.dto.ReservationModificationRequestDto;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.service.ReservationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/reservations")
public class ReservationController {

    private static final Logger logger = LoggerFactory.getLogger(ReservationController.class);

    private final ReservationService reservationService;
    private final CloudAccountRepository cloudAccountRepository; // Added repository

    public ReservationController(ReservationService reservationService, CloudAccountRepository cloudAccountRepository) { // Updated constructor
        this.reservationService = reservationService;
        this.cloudAccountRepository = cloudAccountRepository; // Updated constructor
    }

    @GetMapping
    public CompletableFuture<ResponseEntity<ReservationDto>> getReservationData(
            @RequestParam String accountId,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {
        return reservationService.getReservationPageData(accountId, forceRefresh)
                .thenApply(ResponseEntity::ok)
                .exceptionally(ex -> {
                    logger.error("Error fetching reservation data for account {}", accountId, ex);
                    return ResponseEntity.status(500).body(null);
                });
    }

    @GetMapping("/recommendations")
    public CompletableFuture<ResponseEntity<List<DashboardData.ReservationPurchaseRecommendation>>> getPurchaseRecommendations(
            @RequestParam String accountId,
            @RequestParam(defaultValue = "ONE_YEAR") String term,
            @RequestParam(defaultValue = "NO_UPFRONT") String paymentOption,
            @RequestParam(defaultValue = "THIRTY_DAYS") String lookback,
            @RequestParam(defaultValue = "STANDARD") String offeringClass,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {
        
        CloudAccount account = cloudAccountRepository.findByAwsAccountId(accountId)
                .orElseThrow(() -> new RuntimeException("Account not found: " + accountId));

        return reservationService.getReservationPurchaseRecommendations(
                    account, term, paymentOption, lookback, offeringClass, forceRefresh
                )
                .thenApply(ResponseEntity::ok)
                .exceptionally(ex -> {
                    logger.error("Error fetching purchase recommendations for account {}", accountId, ex);
                    return ResponseEntity.status(500).body(Collections.emptyList());
                });
    }

    @GetMapping("/cost-by-tag")
    public CompletableFuture<ResponseEntity<List<CostByTagDto>>> getReservationCostByTag(
            @RequestParam String accountId,
            @RequestParam String tagKey,
            @RequestParam(defaultValue = "false") boolean forceRefresh) {
        return reservationService.getReservationCostByTag(accountId, tagKey, forceRefresh)
                .thenApply(ResponseEntity::ok)
                .exceptionally(ex -> {
                    logger.error("Error fetching reservation cost by tag for account {}", accountId, ex);
                    return ResponseEntity.status(500).body(Collections.emptyList());
                });
    }

    @PostMapping("/modify")
    public ResponseEntity<Map<String, String>> modifyReservation(
            @RequestParam String accountId,
            @RequestBody ReservationModificationRequestDto modificationRequest) {
        try {
            String transactionId = reservationService.applyReservationModification(accountId, modificationRequest);
            return ResponseEntity.ok(Map.of("status", "success", "transactionId", transactionId));
        } catch (Exception e) {
            logger.error("Error modifying reservation for account {}", accountId, e);
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", e.getMessage()));
        }
    }
}