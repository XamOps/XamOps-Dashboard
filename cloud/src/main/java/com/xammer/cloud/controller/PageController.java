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

    @GetMapping("/finops")
    public String finopsReportPage() {
        return "finops";
    }

    @GetMapping("/reservation")
    public String reservationPage() {
        return "reservation";
    }
    
    @GetMapping("/cloudk8s")
    public String cloudk8sPage() {
        return "cloudk8s";
    }

    @GetMapping("/account-manager")
    public String accountManagerPage() {
        return "account-manager";
    }

    @GetMapping("/add-account")
    public String addAccountPage() {
        return "add-account";
    }
    
    @GetMapping("/add-gcp-account")
    public String addGcpAccountPage() {
        return "add-gcp-account";
    }

    @GetMapping("/cost")
    public String costPage() {
        return "cost";
    }
    
    @GetMapping("/resourcedetail")
    public String resourceDetailPage() {
        return "resourcedetail";
    }
}