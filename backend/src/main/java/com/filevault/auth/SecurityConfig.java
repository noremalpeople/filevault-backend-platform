package com.filevault.auth;

import com.filevault.user.UserMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {
    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    UserDetailsService accounts(UserMapper users) {
        return username -> {
            var account = users.find(username);
            if (account == null) {
                throw new UsernameNotFoundException("Invalid credentials");
            }
            return User.withUsername(account.username()).password(account.passwordHash()).roles("USER").build();
        };
    }

    @Bean
    SecurityFilterChain security(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/", "/index.html", "/assets/**", "/api/v1/health", "/actuator/health",
                                "/api/v1/auth/csrf", "/api/v1/public/shares/*",
                                "/api/v1/public/shares/*/download").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/register", "/api/v1/auth/login").permitAll()
                        .dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ASYNC,
                                jakarta.servlet.DispatcherType.ERROR).permitAll()
                        .anyRequest().authenticated())
                .csrf(Customizer.withDefaults())
                .requestCache(cache -> cache.disable())
                .formLogin(login -> login.loginPage("/api/v1/auth/login").loginProcessingUrl("/api/v1/auth/login")
                        .successHandler((request, response, authentication) -> response.setStatus(204))
                        .failureHandler((request, response, error) ->
                                error(response, 401, "INVALID_CREDENTIALS", "Invalid credentials.")))
                .logout(logout -> logout.logoutUrl("/api/v1/auth/logout").deleteCookies("JSESSIONID")
                        .logoutSuccessHandler((request, response, authentication) -> response.setStatus(204)))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, failure) ->
                                error(response, 401, "UNAUTHENTICATED", "Sign in first."))
                        .accessDeniedHandler((request, response, failure) ->
                                error(response, 403, "FORBIDDEN", "The request was denied.")))
                .build();
    }

    private static void error(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().printf("{\"code\":\"%s\",\"message\":\"%s\"}", code, message);
    }
}
