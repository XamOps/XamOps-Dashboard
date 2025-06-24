package com.xammer.cloud.controller;

import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.service.AwsDataService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;

@Controller
public class DashboardController {

    private final AwsDataService awsDataService;

    public DashboardController(AwsDataService awsDataService) {
        this.awsDataService = awsDataService;
    }

    @GetMapping("/")
    public String getDashboard(Model model) {
        try {
            DashboardData data = awsDataService.getDashboardData();
            model.addAttribute("dashboardData", data);
        } catch (Exception e) {
            model.addAttribute("error", "Failed to load AWS data. Please check backend logs.");
            e.printStackTrace();
        }
        return "dashboard";
    }

    @GetMapping("/waste")
    public String getWasteDashboard(Model model) {
        try {
            DashboardData dashboardData = awsDataService.getDashboardData();
            model.addAttribute("dashboardData", dashboardData);

            List<DashboardData.WastedResource> wastedResources = awsDataService.getWastedResources();
            model.addAttribute("wastedResources", wastedResources);
        } catch (Exception e) {
            model.addAttribute("error", "Failed to load wasted resources data.");
            e.printStackTrace();
        }
        return "waste";
    }
}
