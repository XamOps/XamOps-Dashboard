package com.xammer.cloud.service;

import com.xammer.cloud.dto.DashboardData.SecurityFinding;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

@Service
public class ExcelExportService {

    private static final Logger logger = LoggerFactory.getLogger(ExcelExportService.class);

    /**
     * Exports a list of security findings to an in-memory Excel workbook.
     * @param findings The list of security findings to export.
     * @return A ByteArrayInputStream containing the raw bytes of the Excel file.
     */
    public ByteArrayInputStream exportSecurityFindingsToExcel(List<SecurityFinding> findings) {
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Security Findings");

            // Header Font Style
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerFont.setFontHeightInPoints((short) 12);

            // Header Cell Style
            CellStyle headerCellStyle = workbook.createCellStyle();
            headerCellStyle.setFont(headerFont);

            // Define column headers
            String[] columns = { "Resource ID", "Region", "Category", "Severity", "Description", "Compliance Framework", "Control ID" };
            
            // Create Header Row
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < columns.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(columns[i]);
                cell.setCellStyle(headerCellStyle);
            }

            // Populate Data Rows
            int rowIdx = 1;
            for (SecurityFinding finding : findings) {
                Row row = sheet.createRow(rowIdx++);
                row.createCell(0).setCellValue(finding.getResourceId());
                row.createCell(1).setCellValue(finding.getRegion());
                row.createCell(2).setCellValue(finding.getCategory());
                row.createCell(3).setCellValue(finding.getSeverity());
                row.createCell(4).setCellValue(finding.getDescription());
                row.createCell(5).setCellValue(finding.getComplianceFramework());
                row.createCell(6).setCellValue(finding.getControlId());
            }
            
            // Auto-size columns for better readability
            for(int i = 0; i < columns.length; i++) {
                sheet.autoSizeColumn(i);
            }

            workbook.write(out);
            logger.info("Successfully created Excel report with {} security findings.", findings.size());
            return new ByteArrayInputStream(out.toByteArray());
        } catch (IOException e) {
            logger.error("Failed to export security findings to Excel", e);
            throw new RuntimeException("Failed to generate Excel file: " + e.getMessage());
        }
    }
}
