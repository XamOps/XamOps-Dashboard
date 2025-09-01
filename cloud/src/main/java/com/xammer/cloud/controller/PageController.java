package com.xammer.cloud.controller;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.security.ClientUserDetails;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@Controller
public class PageController {

    private final CloudAccountRepository cloudAccountRepository;

    public PageController(CloudAccountRepository cloudAccountRepository) {
        this.cloudAccountRepository = cloudAccountRepository;
    }

    private String getProviderForAccount(String accountId) {
        if (accountId == null || accountId.isBlank()) {
            return "AWS";
        }
        return cloudAccountRepository.findByAwsAccountIdOrGcpProjectId(accountId, accountId)
                .map(CloudAccount::getProvider)
                .orElse("AWS");
    }

    @GetMapping("/")
    public String dashboardPage(@RequestParam(required = false, name = "accountIds") List<String> accountIds, Authentication authentication, Model model) {
        if (accountIds != null && !accountIds.isEmpty()) {
            if (accountIds.size() == 1 && "GCP".equals(getProviderForAccount(accountIds.get(0)))) {
                return "gcp_dashboard";
            }
            return "dashboard";
        }

        ClientUserDetails userDetails = (ClientUserDetails) authentication.getPrincipal();
        Long clientId = userDetails.getClientId();
        List<CloudAccount> accounts = cloudAccountRepository.findByClientId(clientId);

        if (accounts.isEmpty()) {
            model.addAttribute("hasAccounts", false);
            return "dashboard";
        } else {
            CloudAccount defaultAccount = accounts.get(0);
            String firstAccountId = "AWS".equals(defaultAccount.getProvider())
                    ? defaultAccount.getAwsAccountId()
                    : defaultAccount.getGcpProjectId();
            
            return "redirect:/?accountIds=" + firstAccountId;
        }
    }
    
    private String getFirstAccountId(List<String> accountIds) {
        return (accountIds != null && !accountIds.isEmpty()) ? accountIds.get(0) : null;
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

    @GetMapping("/cloudk8s")
    public String cloudk8sPage() {
        return "cloudk8s";
    }

    @GetMapping("/reservation")
    public String reservationPage() {
        return "reservation";
    }


    @GetMapping("/waste")
    public String wastePage(@RequestParam(required = false, name = "accountIds") List<String> accountIds) {
        if ("GCP".equals(getProviderForAccount(getFirstAccountId(accountIds)))) {
            return "gcp_waste";
        }
        return "waste";
    }

    @GetMapping("/cloudlist")
    public String cloudlistPage(@RequestParam(required = false, name = "accountIds") List<String> accountIds) {
        if ("GCP".equals(getProviderForAccount(getFirstAccountId(accountIds)))) {
            return "gcp_cloudlist";
        }
        return "cloudlist";
    }

    @GetMapping("/rightsizing")
    public String rightsizingPage(@RequestParam(required = false, name = "accountIds") List<String> accountIds) {
        if ("GCP".equals(getProviderForAccount(getFirstAccountId(accountIds)))) {
            return "gcp_rightsizing";
        }
        return "rightsizing";
    }

    @GetMapping("/cloudmap")
    public String cloudmapPage(@RequestParam(required = false, name = "accountIds") List<String> accountIds) {
        if ("GCP".equals(getProviderForAccount(getFirstAccountId(accountIds)))) {
            return "gcp_cloudmap";
        }
        return "cloudmap";
    }

    @GetMapping("/security")
    public String securityPage(@RequestParam(required = false, name = "accountIds") List<String> accountIds) {
        if ("GCP".equals(getProviderForAccount(getFirstAccountId(accountIds)))) {
            return "gcp_security";
        }
        return "security";
    }

    @GetMapping("/performance")
    public String performancePage(@RequestParam(required = false, name = "accountIds") List<String> accountIds) {
        if ("GCP".equals(getProviderForAccount(getFirstAccountId(accountIds)))) {
            return "gcp_performance";
        }
        return "performance";
    }

    @GetMapping("/finops")
    public String finopsReportPage(@RequestParam(required = false, name = "accountIds") List<String> accountIds) {
        if ("GCP".equals(getProviderForAccount(getFirstAccountId(accountIds)))) {
            return "gcp_finops";
        }
        return "finops";
    }

    @GetMapping("/cost")
    public String costPage(@RequestParam(required = false, name = "accountIds") List<String> accountIds) {
        if ("GCP".equals(getProviderForAccount(getFirstAccountId(accountIds)))) {
            return "gcp_cost";
        }
        return "cost";
    }

    @GetMapping("/resourcedetail")
    public String resourceDetailPage(@RequestParam(required = false, name = "accountIds") List<String> accountIds) {
        return "resourcedetail";
    }
    @GetMapping("/cloud/eks/details")
    public String eksDetailsPage() {
        return "eks-details";
    }

    @GetMapping("/settings")
    public String settingsPage() {
        return "settings";
    }

    @GetMapping("/login")
    public String loginPage() {
        return "login";
    }
}