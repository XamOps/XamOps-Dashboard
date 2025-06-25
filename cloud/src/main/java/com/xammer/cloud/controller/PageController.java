package com.xammer.cloud.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class PageController {

    @GetMapping("/")
    public String dashboardPage() {
        return "dashboard"; // returns dashboard.html
    }

    @GetMapping("/waste")
    public String wastePage() {
        return "waste"; // returns waste.html
    }
    
    // FIXED: Added a mapping to handle the /login URL and return the login page.
    @GetMapping("/login")
    public String loginPage() {
        return "login"; // returns login.html
    }
}
