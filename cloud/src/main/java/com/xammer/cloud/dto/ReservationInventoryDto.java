package com.xammer.cloud.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * DTO for representing a single reservation in the inventory.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReservationInventoryDto {
    private String reservationId;
    private String offeringType;
    private String instanceType;
    private String scope;
    private String availabilityZone;
    private long duration;
    private Instant start;
    private Instant end;
    private int instanceCount;
    private String state;
}
