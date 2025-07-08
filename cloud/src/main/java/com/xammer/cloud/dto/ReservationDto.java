package com.xammer.cloud.dto;

import com.xammer.cloud.dto.DashboardData.ReservationAnalysis;
import com.xammer.cloud.dto.DashboardData.ReservationPurchaseRecommendation;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Data Transfer Object for the Reservation page.
 * This DTO combines reservation analysis, purchase recommendations, inventory, historical data, and modification recommendations.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReservationDto {
    private ReservationAnalysis analysis;
    private List<ReservationPurchaseRecommendation> purchaseRecommendations;
    private List<ReservationInventoryDto> inventory;
    private HistoricalReservationDataDto historicalData;
    private List<ReservationModificationRecommendationDto> modificationRecommendations;
}
