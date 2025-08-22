package com.xammer.cloud.service.gcp;

import com.google.cloud.billing.budgets.v1.Budget;
import com.google.cloud.billing.budgets.v1.BudgetServiceClient;
import com.google.cloud.billing.budgets.v1.ListBudgetsRequest;
import com.xammer.cloud.dto.gcp.GcpBudgetDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@Service
@Slf4j
public class GcpBudgetService {

    private final GcpClientProvider gcpClientProvider;

    public GcpBudgetService(GcpClientProvider gcpClientProvider) {
        this.gcpClientProvider = gcpClientProvider;
    }

    public CompletableFuture<List<GcpBudgetDto>> getBudgets(String billingAccountId) {
        return CompletableFuture.supplyAsync(() -> {
            if (billingAccountId == null || billingAccountId.isBlank() || billingAccountId.contains("YOUR_BILLING_ACCOUNT_ID")) {
                log.warn("GCP Billing Account ID is not configured. Skipping budget fetch.");
                return Collections.emptyList();
            }
            try (BudgetServiceClient client = gcpClientProvider.getBudgetServiceClient(billingAccountId)) {
                String parent = "billingAccounts/" + billingAccountId;
                ListBudgetsRequest request = ListBudgetsRequest.newBuilder().setParent(parent).build();
                return StreamSupport.stream(client.listBudgets(request).iterateAll().spliterator(), false)
                    .map(this::mapToDto)
                    .collect(Collectors.toList());
            } catch (Exception e) {
                log.error("Failed to fetch GCP budgets for billing account {}: {}", billingAccountId, e.getMessage());
                return Collections.emptyList();
            }
        });
    }

    private GcpBudgetDto mapToDto(Budget budget) {
        double limit = budget.getAmount().getSpecifiedAmount().getUnits();
        // The Budget class does not provide actual or forecasted spend, so set them to 0.0
        double actual = 0.0;
        double forecast = 0.0;
        return new GcpBudgetDto(budget.getDisplayName(), limit, actual, forecast);
    }
}