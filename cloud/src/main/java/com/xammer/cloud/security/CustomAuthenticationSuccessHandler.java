package com.xammer.cloud.security;

import com.xammer.cloud.service.CacheService; // Import the new service
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

@Component
public class CustomAuthenticationSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final CacheService cacheService; // Use the new CacheService

    // Update the constructor to inject CacheService
    public CustomAuthenticationSuccessHandler(CacheService cacheService) {
        this.cacheService = cacheService;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                      Authentication authentication) throws IOException, ServletException {
        // Clear all application data caches upon successful login
        cacheService.evictAllCaches();
        
        // Set the default target URL to redirect to after login
        setDefaultTargetUrl("/");
        
        // Proceed with the default Spring Security login success behavior
        super.onAuthenticationSuccess(request, response, authentication);
    }
}