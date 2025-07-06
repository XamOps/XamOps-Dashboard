package com.xammer.cloud.controller;

import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.service.AwsDataService;
import com.xammer.cloud.service.ExcelExportService;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
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

    public SecurityController(AwsDataService awsDataService, ExcelExportService excelExportService) {
        this.awsDataService = awsDataService;
        this.excelExportService = excelExportService;
    }

    @GetMapping("/findings")
    public ResponseEntity<List<DashboardData.SecurityFinding>> getSecurityFindings() throws ExecutionException, InterruptedException {
        List<DashboardData.SecurityFinding> findings = awsDataService.getComprehensiveSecurityFindings().get();
        return ResponseEntity.ok(findings);
    }

    @GetMapping("/export")
    public ResponseEntity<InputStreamResource> exportFindings() throws ExecutionException, InterruptedException {
        List<DashboardData.SecurityFinding> findings = awsDataService.getComprehensiveSecurityFindings().get();
        ByteArrayInputStream in = excelExportService.exportSecurityFindingsToExcel(findings);

        HttpHeaders headers = new HttpHeaders();
        String timestamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm").format(new Date());
        String filename = "XamOps_Security_Report_" + timestamp + ".xlsx";
        headers.add("Content-Disposition", "attachment; filename=" + filename);

        return ResponseEntity
                .ok()
                .headers(headers)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new InputStreamResource(in));
    }
}
