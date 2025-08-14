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

    private final CacheService cacheService; 

    public CustomAuthenticationSuccessHandler(CacheService cacheService) {
        this.cacheService = cacheService;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                      Authentication authentication) throws IOException, ServletException {
        
        //
        // REMOVE OR COMMENT OUT THIS LINE. This is the cause of the issue.
        // By removing it, the cache will persist between user sessions.
        // cacheService.evictAllCaches();
        //
        
        setDefaultTargetUrl("/");
        
        super.onAuthenticationSuccess(request, response, authentication);
    }
}