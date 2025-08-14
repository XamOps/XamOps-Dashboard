package com.xammer.cloud.controller;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.service.CloudListService;
import com.xammer.cloud.service.ExcelExportService;
import com.xammer.cloud.service.SecurityService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayInputStream;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/security")
public class SecurityController {

    private static final Logger logger = LoggerFactory.getLogger(SecurityController.class);

    private final SecurityService securityService;
    private final CloudListService cloudListService;
    private final ExcelExportService excelExportService;
    private final CloudAccountRepository cloudAccountRepository;

    public SecurityController(SecurityService securityService,
                              CloudListService cloudListService,
                              ExcelExportService excelExportService,
                              CloudAccountRepository cloudAccountRepository) {
        this.securityService = securityService;
        this.cloudListService = cloudListService;
        this.excelExportService = excelExportService;
        this.cloudAccountRepository = cloudAccountRepository;
    }

    @GetMapping("/findings")
    public CompletableFuture<ResponseEntity<List<DashboardData.SecurityFinding>>> getSecurityFindings(@RequestParam String accountId) {
        CloudAccount account = cloudAccountRepository.findByAwsAccountId(accountId)
                .orElseThrow(() -> new RuntimeException("Account not found: " + accountId));

        return cloudListService.getRegionStatusForAccount(account, true)
                .thenCompose((List<DashboardData.RegionStatus> activeRegions) -> securityService.getComprehensiveSecurityFindings(account, activeRegions, true))
                .thenApply(ResponseEntity::ok)
                .exceptionally(ex -> {
                    logger.error("Error fetching security findings for account {}", accountId, ex);
                    return ResponseEntity.status(500).body(Collections.emptyList());
                });
    }

    @GetMapping("/export")
    public CompletableFuture<ResponseEntity<byte[]>> exportFindingsToExcel(@RequestParam String accountId) {
        CloudAccount account = cloudAccountRepository.findByAwsAccountId(accountId)
                .orElseThrow(() -> new RuntimeException("Account not found: " + accountId));

        return cloudListService.getRegionStatusForAccount(account, true)
                .thenCompose((List<DashboardData.RegionStatus> activeRegions) -> securityService.getComprehensiveSecurityFindings(account, activeRegions, true))
                .thenApply(findings -> {
                    ByteArrayInputStream in = excelExportService.exportSecurityFindingsToExcel(findings);
                    HttpHeaders headers = new HttpHeaders();
                    headers.add("Content-Disposition", "attachment; filename=security-findings.xlsx");

                    return ResponseEntity
                            .ok()
                            .headers(headers)
                            .contentType(MediaType.APPLICATION_OCTET_STREAM)
                            .body(in.readAllBytes());
                })
                .exceptionally(ex -> {
                    logger.error("Error exporting security findings for account {}", accountId, ex);
                    return ResponseEntity.status(500).build();
                });
    }
}