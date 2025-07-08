package com.xammer.cloud.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for handling a request to modify a Reserved Instance.
 * This object is received as the request body for the modification endpoint.
 */
@Data
@NoArgsConstructor
public class ReservationModificationRequestDto {

    /**
     * The ID of the Reserved Instance to be modified (e.g., "ri-12345abcde").
     */
    private String reservationId;

    /**
     * The target instance type for the modification (e.g., "t2.large").
     */
    private String targetInstanceType;

    /**
     * The number of instances to apply to the target configuration.
     */
    private int instanceCount;
}
