package com.xammer.cloud.dto.gcp;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class GcpBudgetDto {
    private String budgetName;
    private double budgetLimit;
    private double actualSpend;
    private double forecastedSpend;
}