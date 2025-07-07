package com.xammer.cloud.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/cloudguard")
public class AlertsController {

    @GetMapping("/alerts")
    public String alertsPage() {
        return "alerts";
    }
}