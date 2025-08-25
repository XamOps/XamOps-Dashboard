package com.xammer.cloud.controller;

import com.xammer.cloud.domain.CloudAccount;
import com.xammer.cloud.repository.CloudAccountRepository;
import com.xammer.cloud.security.ClientUserDetails; // Import this
import org.springframework.security.core.Authentication; // Import this
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List; // Import this

@Controller
public class PageController {

    private final CloudAccountRepository cloudAccountRepository;

    public PageController(CloudAccountRepository cloudAccountRepository) {
        this.cloudAccountRepository = cloudAccountRepository;
    }

    /**
     * Determines the cloud provider ("AWS" or "GCP") for a given account ID.
     * Defaults to "AWS" if the account is not found or no ID is provided.
     * @param accountId The AWS Account ID or GCP Project ID.
     * @return The provider name as a String.
     */
    private String getProviderForAccount(String accountId) {
        if (accountId == null || accountId.isBlank()) {
            return "AWS"; // Default to AWS if no account is selected
        }
        // Use the repository method that checks both potential ID fields
        return cloudAccountRepository.findByAwsAccountIdOrGcpProjectId(accountId, accountId)
                .map(CloudAccount::getProvider)
                .orElse("AWS"); // Default to AWS if account not found
    }

    // --- THIS IS THE MODIFIED METHOD ---
    @GetMapping("/")
    public String dashboardPage(@RequestParam(required = false) String accountId, Authentication authentication, Model model) {
        // If an accountId is already in the URL, it means we have been redirected or the user is switching.
        // Let the existing logic handle it.
        if (accountId != null && !accountId.isBlank()) {
            if ("GCP".equals(getProviderForAccount(accountId))) {
                return "gcp_dashboard";
            }
            return "dashboard";
        }

        // If no accountId is in the URL, this is the first load after login.
        // We need to decide whether to redirect or show a special welcome message.
        ClientUserDetails userDetails = (ClientUserDetails) authentication.getPrincipal();
        Long clientId = userDetails.getClientId();
        List<CloudAccount> accounts = cloudAccountRepository.findByClientId(clientId);

        if (accounts.isEmpty()) {
            // SCENARIO 2: User has NO accounts.
            // Add a flag to the model and render the dashboard page.
            // The template will use this flag to show the "add account" message.
            model.addAttribute("hasAccounts", false);
            return "dashboard";
        } else {
            // SCENARIO 1: User HAS accounts.
            // Get the first account and redirect to the dashboard URL with its ID.
            CloudAccount defaultAccount = accounts.get(0);
            String firstAccountId = "AWS".equals(defaultAccount.getProvider())
                    ? defaultAccount.getAwsAccountId()
                    : defaultAccount.getGcpProjectId();
            
            return "redirect:/?accountId=" + firstAccountId;
        }
    }

    
    // NEWLY ADDED
    @GetMapping("/account-manager")
    public String accountManagerPage() {
        return "account-manager";
    }

    // NEWLY ADDED
    @GetMapping("/add-account")
    public String addAccountPage() {
        return "add-account";
    }

    // NEWLY ADDED
    @GetMapping("/add-gcp-account")
    public String addGcpAccountPage() {
        return "add-gcp-account";
    }

    // NEWLY ADDED
    @GetMapping("/cloudk8s")
    public String cloudk8sPage() {
        return "cloudk8s";
    }

    // NEWLY ADDED
    @GetMapping("/reservation")
    public String reservationPage() {
        return "reservation";
    }


    @GetMapping("/waste")
    public String wastePage(@RequestParam(required = false) String accountId) {
        if ("GCP".equals(getProviderForAccount(accountId))) {
            return "gcp_waste";
        }
        return "waste";
    }

    @GetMapping("/cloudlist")
    public String cloudlistPage(@RequestParam(required = false) String accountId) {
        if ("GCP".equals(getProviderForAccount(accountId))) {
            return "gcp_cloudlist";
        }
        return "cloudlist";
    }

    @GetMapping("/rightsizing")
    public String rightsizingPage(@RequestParam(required = false) String accountId) {
        if ("GCP".equals(getProviderForAccount(accountId))) {
            return "gcp_rightsizing";
        }
        return "rightsizing";
    }

    @GetMapping("/cloudmap")
    public String cloudmapPage(@RequestParam(required = false) String accountId) {
        if ("GCP".equals(getProviderForAccount(accountId))) {
            return "gcp_cloudmap";
        }
        return "cloudmap";
    }

    @GetMapping("/security")
    public String securityPage(@RequestParam(required = false) String accountId) {
        if ("GCP".equals(getProviderForAccount(accountId))) {
            return "gcp_security";
        }
        return "security";
    }

    @GetMapping("/performance")
    public String performancePage(@RequestParam(required = false) String accountId) {
        if ("GCP".equals(getProviderForAccount(accountId))) {
            return "gcp_performance";
        }
        return "performance";
    }

    @GetMapping("/finops")
    public String finopsReportPage(@RequestParam(required = false) String accountId) {
        if ("GCP".equals(getProviderForAccount(accountId))) {
            return "gcp_finops";
        }
        return "finops";
    }

    @GetMapping("/cost")
    public String costPage(@RequestParam(required = false) String accountId) {
        if ("GCP".equals(getProviderForAccount(accountId))) {
            return "gcp_cost";
        }
        return "cost";
    }

    @GetMapping("/resourcedetail")
    public String resourceDetailPage(@RequestParam(required = false) String accountId) {
        // This page might need more specific logic if GCP resources have a different detail view
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