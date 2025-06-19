package com.xammer.cloud.controller;

import com.xammer.cloud.dto.DashboardData;
import com.xammer.cloud.service.AwsDataService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * This is a Spring @Controller, which means it is responsible for handling
 * web requests and returning the name of an HTML view to render.
 */
@Controller
public class DashboardController {

    private final AwsDataService awsDataService;

    public DashboardController(AwsDataService awsDataService) {
        this.awsDataService = awsDataService;
    }

    /**
     * This method handles GET requests to the root URL of the application ("/").
     * When you navigate to http://localhost:8080, this method is executed.
     *
     * @param model The Spring Model object, used to pass data to the HTML template.
     * @return The name of the HTML template file to be rendered.
     */
    @GetMapping("/")
    public String getDashboard(Model model) {
        try {
            // 1. Fetch all live data from the AWS service layer.
            // CORRECTED: The method name is now getDashboardData()
            DashboardData data = awsDataService.getDashboardData();
            // 2. Add the live data to the model with the key "dashboardData".
            // The HTML file will use this key to access the data.
            model.addAttribute("dashboardData", data);
        } catch (Exception e) {
            // If an error occurs, add an error message to the model.
            model.addAttribute("error", "Failed to load AWS data. Please check backend logs.");
            e.printStackTrace();
        }
        // 3. Return the string "dashboard". This tells Spring Boot's Thymeleaf engine
        //    to find and render a file named "dashboard.html" located in the
        //    src/main/resources/templates/ directory.
        return "dashboard";
    }
    
}
