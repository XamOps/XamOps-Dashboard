package com.xammer.cloud.controller;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.service.AwsDataService;
import com.xammer.cloud.service.ExcelExportService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.concurrent.ExecutionException;

@RestController
@RequestMapping("/api/security")
public class SecurityController {

    private final AwsDataService awsDataService;
    private final ExcelExportService excelExportService;
    private final CloudAccountRepository cloudAccountRepository;

    public SecurityController(AwsDataService awsDataService, ExcelExportService excelExportService, CloudAccountRepository cloudAccountRepository) {
        this.awsDataService = awsDataService;
        this.excelExportService = excelExportService;
        this.cloudAccountRepository = cloudAccountRepository;
    }

    @GetMapping("/findings")
    public ResponseEntity<List<DashboardData.SecurityFinding>> getSecurityFindings(@RequestParam String accountId) throws ExecutionException, InterruptedException {
        CloudAccount account = cloudAccountRepository.findByAwsAccountId(accountId)
                .orElseThrow(() -> new RuntimeException("Account not found: " + accountId));
        
        // **FIXED**: Fetch active regions first, then pass them to the service method.
        List<DashboardData.RegionStatus> activeRegions = awsDataService.getRegionStatusForAccount(account).get();
        List<DashboardData.SecurityFinding> findings = awsDataService.getComprehensiveSecurityFindings(account, activeRegions).get();

        return ResponseEntity.ok(findings);
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> exportFindingsToExcel(@RequestParam String accountId) throws ExecutionException, InterruptedException {
        CloudAccount account = cloudAccountRepository.findByAwsAccountId(accountId)
                .orElseThrow(() -> new RuntimeException("Account not found: " + accountId));
        
        // **FIXED**: Fetch active regions first, then pass them to the service method for export.
        List<DashboardData.RegionStatus> activeRegions = awsDataService.getRegionStatusForAccount(account).get();
        List<DashboardData.SecurityFinding> findings = awsDataService.getComprehensiveSecurityFindings(account, activeRegions).get();

        ByteArrayInputStream in = excelExportService.exportSecurityFindingsToExcel(findings);

        HttpHeaders headers = new HttpHeaders();
        headers.add("Content-Disposition", "attachment; filename=security-findings.xlsx");

        return ResponseEntity
                .ok()
                .headers(headers)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(in.readAllBytes());
    }
}
