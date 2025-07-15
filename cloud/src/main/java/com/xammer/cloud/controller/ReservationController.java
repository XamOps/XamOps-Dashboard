package com.xammer.cloud.controller;

import com.xammer.cloud.dto.CostByTagDto;
import com.xammer.cloud.dto.ReservationDto;
import com.xammer.cloud.dto.ReservationModificationRequestDto;
import com.xammer.cloud.service.AwsDataService;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

@RestController
@RequestMapping("/api/reservations")
public class ReservationController {

    private final AwsDataService awsDataService;

    public ReservationController(AwsDataService awsDataService) {
        this.awsDataService = awsDataService;
    }

    @GetMapping
    public ResponseEntity<ReservationDto> getReservationData(@RequestParam String accountId) throws ExecutionException, InterruptedException {
        ReservationDto reservationData = awsDataService.getReservationPageData(accountId).get();
        return ResponseEntity.ok(reservationData);
    }

    @GetMapping("/cost-by-tag")
    public ResponseEntity<List<CostByTagDto>> getReservationCostByTag(@RequestParam String accountId, @RequestParam String tagKey) throws ExecutionException, InterruptedException {
        List<CostByTagDto> costData = awsDataService.getReservationCostByTag(accountId, tagKey).get();
        return ResponseEntity.ok(costData);
    }

    @PostMapping("/modify")
    public ResponseEntity<Map<String, String>> modifyReservation(@RequestParam String accountId, @RequestBody ReservationModificationRequestDto modificationRequest) {
        try {
            String transactionId = awsDataService.applyReservationModification(accountId, modificationRequest);
            return ResponseEntity.ok(Map.of("status", "success", "transactionId", transactionId));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", e.getMessage()));
        }
    }
}
