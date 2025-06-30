package com.xammer.cloud.security;

import com.xammer.cloud.service.AwsDataService;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

@Component
public class CustomAuthenticationSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final AwsDataService awsDataService;

    public CustomAuthenticationSuccessHandler(AwsDataService awsDataService) {
        this.awsDataService = awsDataService;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException, ServletException {
        // Clear all AWS data caches upon successful login
        awsDataService.clearAllCaches();
        
        // Set the default target URL to redirect to after login
        setDefaultTargetUrl("/");
        
        // Proceed with the default Spring Security login success behavior (e.g., redirect)
        super.onAuthenticationSuccess(request, response, authentication);
    }
}