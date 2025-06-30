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

    // ADDED: Mapping for the new Cloudlist page
    @GetMapping("/cloudlist")
    public String cloudlistPage() {
        return "cloudlist";
    }

    // ADDED: Mapping for the new Rightsizing page
    @GetMapping("/rightsizing")
    public String rightsizingPage() {
        return "rightsizing";
    }
}