// package com.xammer.cloud.service;

// import com.opencsv.bean.CsvToBeanBuilder;
// import com.xammer.cloud.dto.SecurityFinding;
// import org.apache.poi.ss.usermodel.Cell;
// import org.apache.poi.ss.usermodel.Row;
// import org.apache.poi.ss.usermodel.Sheet;
// import org.apache.poi.ss.usermodel.Workbook;
// import org.apache.poi.xssf.usermodel.XSSFWorkbook;
// import org.springframework.stereotype.Service;
// import org.springframework.core.io.ClassPathResource;

// import java.io.ByteArrayInputStream;
// import java.io.ByteArrayOutputStream;
// import java.io.FileReader;
// import java.io.IOException;
// import java.io.InputStreamReader;
// import java.io.Reader;
// import java.util.List;
// import java.util.stream.Collectors;

// @Service
// public class SecurityService {

//     /**
//      * Reads security findings from a CSV file in the classpath.
//      * @return A list of SecurityFinding objects.
//      */
//     public List<SecurityFinding> getSecurityFindings() throws IOException {
//         // The CSV file should be placed in the src/main/resources directory.
//         ClassPathResource resource = new ClassPathResource("cloudguard_security.xlsx - insights-report.csv");
        
//         try (Reader reader = new InputStreamReader(resource.getInputStream())) {
//             List<SecurityFinding> findings = new CsvToBeanBuilder<SecurityFinding>(reader)
//                     .withType(SecurityFinding.class)
//                     .withSkipLines(1) // Skip the header row
//                     .build()
//                     .parse();
//             // Filter out any potential empty rows read by the parser
//             return findings.stream()
//                            .filter(finding -> finding.getId() != null && !finding.getId().isEmpty())
//                            .collect(Collectors.toList());
//         }
//     }

//     /**
//      * Creates an Excel file in memory from the list of security findings.
//      * @param findings The list of security findings.
//      * @return A ByteArrayInputStream containing the Excel file data.
//      */
//     public ByteArrayInputStream findingsToExcel(List<SecurityFinding> findings) throws IOException {
//         String[] columns = {"ID", "Account", "Category", "Severity", "Title", "Description", "Resource", "Link", "Compliance", "References"};
//         try (
//             Workbook workbook = new XSSFWorkbook();
//             ByteArrayOutputStream out = new ByteArrayOutputStream();
//         ) {
//             Sheet sheet = workbook.createSheet("Security Findings");

//             // Header
//             Row headerRow = sheet.createRow(0);
//             for (int col = 0; col < columns.length; col++) {
//                 Cell cell = headerRow.createCell(col);
//                 cell.setCellValue(columns[col]);
//             }

//             // Data
//             int rowIdx = 1;
//             for (SecurityFinding finding : findings) {
//                 Row row = sheet.createRow(rowIdx++);
//                 row.createCell(0).setCellValue(finding.getId());
//                 row.createCell(1).setCellValue(finding.getAccount());
//                 row.createCell(2).setCellValue(finding.getCategory());
//                 row.createCell(3).setCellValue(finding.getSeverity());
//                 row.createCell(4).setCellValue(finding.getTitle());
//                 row.createCell(5).setCellValue(finding.getDescription());
//                 row.createCell(6).setCellValue(finding.getResource());
//                 row.createCell(7).setCellValue(finding.getLink());
//                 row.createCell(8).setCellValue(finding.getCompliance());
//                 row.createCell(9).setCellValue(finding.getReferences());
//             }

//             workbook.write(out);
//             return new ByteArrayInputStream(out.toByteArray());
//         }
//     }
// }
