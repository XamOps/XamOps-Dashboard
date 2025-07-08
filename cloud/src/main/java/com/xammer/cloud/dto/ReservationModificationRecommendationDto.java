package com.xammer.cloud.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for representing a single RI modification recommendation.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReservationModificationRecommendationDto {
    private String reservationId;
    private String currentInstanceType;
    private String recommendedInstanceType;
    private String reason;
    private double estimatedMonthlySavings;
}
