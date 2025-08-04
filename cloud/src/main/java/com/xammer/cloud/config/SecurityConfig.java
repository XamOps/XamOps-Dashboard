package com.xammer.cloud.config;

import com.xammer.cloud.security.ClientUserDetails;
import com.xammer.cloud.security.CustomAuthenticationSuccessHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import com.xammer.cloud.repository.UserRepository;
import java.util.ArrayList;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final CustomAuthenticationSuccessHandler authenticationSuccessHandler;

    public SecurityConfig(CustomAuthenticationSuccessHandler authenticationSuccessHandler) {
        this.authenticationSuccessHandler = authenticationSuccessHandler;
    }

@Bean
public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    http
        .authorizeHttpRequests((requests) -> requests
            // Allow WebSocket connections
            .antMatchers("/ws/**").permitAll()
            .antMatchers("/", "/waste", "/cloudlist", "/account-manager", "/add-account").authenticated()

            // ✅ ADD THIS LINE to secure your Kubernetes API
            .antMatchers("/api/k8s/**").authenticated()

            // ✅ CHANGE THIS LINE to secure all other requests by default
            .anyRequest().authenticated()
        )
        .formLogin((form) -> form
            .loginPage("/login")
            .successHandler(authenticationSuccessHandler)
            .permitAll()
        )
        .logout((logout) -> logout
            .logoutUrl("/logout")
            .logoutSuccessUrl("/login?logout")
            .invalidateHttpSession(true)
            .deleteCookies("JSESSIONID")
            .permitAll()
        );

    http.csrf().disable();

    return http.build();
}

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public UserDetailsService userDetailsService(UserRepository userRepository) {
        return username -> userRepository.findByUsername(username)
                .map(user -> new ClientUserDetails(
                        user.getUsername(),
                        user.getPassword(),
                        new ArrayList<>(),
                        user.getClient().getId() // Pass the client ID here
                ))
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));
    }
}