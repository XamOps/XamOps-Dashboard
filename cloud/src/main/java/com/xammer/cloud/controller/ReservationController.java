package com.xammer.cloud.controller;

import com.xammer.cloud.dto.CostByTagDto;
import com.xammer.cloud.dto.ReservationDto;
import com.xammer.cloud.service.AwsDataService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.concurrent.ExecutionException;

/**
 * Controller to handle API requests for the Reservations page.
 */
@RestController
@RequestMapping("/api/reservations")
public class ReservationController {

    private final AwsDataService awsDataService;

    public ReservationController(AwsDataService awsDataService) {
        this.awsDataService = awsDataService;
    }

    /**
     * Fetches reservation analysis, purchase recommendations, and inventory.
     * @return A DTO containing all necessary data for the reservations page.
     */
    @GetMapping
    public ResponseEntity<ReservationDto> getReservationData() throws ExecutionException, InterruptedException {
        ReservationDto reservationData = awsDataService.getReservationPageData().get();
        return ResponseEntity.ok(reservationData);
    }

    /**
     * Fetches reservation cost breakdown by a specific tag.
     * @param tagKey The tag key to group costs by.
     * @return A list of costs grouped by tag values.
     */
    @GetMapping("/cost-by-tag")
    public ResponseEntity<List<CostByTagDto>> getReservationCostByTag(@RequestParam String tagKey) throws ExecutionException, InterruptedException {
        List<CostByTagDto> costData = awsDataService.getReservationCostByTag(tagKey).get();
        return ResponseEntity.ok(costData);
    }
}
