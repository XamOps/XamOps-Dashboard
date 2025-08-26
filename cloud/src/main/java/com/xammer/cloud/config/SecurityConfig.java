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
import org.springframework.security.web.firewall.HttpFirewall;
import org.springframework.security.web.firewall.StrictHttpFirewall;
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
            // ENABLE CORS to apply the rules from your WebConfig
            .cors().and()
            .authorizeHttpRequests((requests) -> requests
                .antMatchers("/login", "/css/**", "/js/**", "/images/**", "/icons/**", "/webjars/**").permitAll()
                .antMatchers("/ws/**").authenticated()
                .antMatchers("/", "/waste", "/cloudlist", "/account-manager", "/add-account").authenticated()
                .antMatchers("/api/k8s/**").authenticated()
                .antMatchers("/vendor/**", "/ec/vendor/**", "/.php", "/**/.php").denyAll()
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

        // ✅ UPDATE THIS LINE to ignore CSRF for the account manager API
        http.csrf(csrf -> csrf
            .ignoringAntMatchers("/api/k8s/**", "/api/account-manager/**")
        );

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
                        user.getClient().getId()
                ))
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));
    }

    @Bean
    public HttpFirewall allowSemicolonHttpFirewall() {
        StrictHttpFirewall firewall = new StrictHttpFirewall();
        // This is the critical change to allow session IDs in the URL
        firewall.setAllowSemicolon(true);
        return firewall;
    }
}