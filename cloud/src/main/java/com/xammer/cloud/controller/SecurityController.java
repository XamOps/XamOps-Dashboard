package com.xammer.cloud.controller;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.service.AwsDataService;
import com.xammer.cloud.service.ExcelExportService;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayInputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
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
        List<DashboardData.SecurityFinding> findings = awsDataService.getComprehensiveSecurityFindings(account).get();
        return ResponseEntity.ok(findings);
    }

    @GetMapping("/export")
    public ResponseEntity<InputStreamResource> exportFindings(@RequestParam String accountId) throws ExecutionException, InterruptedException {
        CloudAccount account = cloudAccountRepository.findByAwsAccountId(accountId)
                .orElseThrow(() -> new RuntimeException("Account not found: " + accountId));
        
        // Fetch the security findings data
        List<DashboardData.SecurityFinding> findings = awsDataService.getComprehensiveSecurityFindings(account).get();
        
        // Use the export service to generate the Excel file in memory
        ByteArrayInputStream in = excelExportService.exportSecurityFindingsToExcel(findings);

        // Set HTTP headers for file download
        HttpHeaders headers = new HttpHeaders();
        String timestamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm").format(new Date());
        String filename = "XamOps_Security_Report_" + accountId + "_" + timestamp + ".xlsx";
        headers.add("Content-Disposition", "attachment; filename=" + filename);

        return ResponseEntity
                .ok()
                .headers(headers)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new InputStreamResource(in));
    }
}
