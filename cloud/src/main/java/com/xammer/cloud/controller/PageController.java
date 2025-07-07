package com.xammer.cloud.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class PageController {

    @GetMapping("/")
    public String dashboardPage() {
        return "dashboard";
    }

    @GetMapping("/waste")
    public String wastePage() {
        return "waste";
    }

    @GetMapping("/login")
    public String loginPage() {
        return "login";
    }

    @GetMapping("/cloudlist")
    public String cloudlistPage() {
        return "cloudlist";
    }

    @GetMapping("/rightsizing")
    public String rightsizingPage() {
        return "rightsizing";
    }

    @GetMapping("/cloudmap")
    public String cloudmapPage() {
        return "cloudmap";
    }

    @GetMapping("/security")
    public String securityPage() {
        return "security";
    }

    @GetMapping("/performance")
    public String performancePage() {
        return "performance";
    }

    // ADDED: Mapping for the new FinOps Report page
    @GetMapping("/finops")
    public String finopsReportPage() {
        return "finops";
    }
}
