package org.apache.fluss.manager.config;

import org.apache.fluss.manager.store.AppConfigStore;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

@Configuration
public class SecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    UserDetailsService userDetailsService(AppConfigStore store) {
        return username -> {
            var admin = store.snapshot().admin();
            if (!admin.username().equals(username)) {
                throw new UsernameNotFoundException(username);
            }
            return User.withUsername(admin.username()).password(admin.passwordHash()).roles("ADMIN").build();
        };
    }

    @Bean
    AuthenticationManager authenticationManager(
            UserDetailsService userDetailsService, PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(List.of(provider));
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(
                        requests ->
                                requests
                                        .requestMatchers("/", "/index.html", "/app.js", "/styles.css")
                                        .permitAll()
                                        .requestMatchers("/api/auth/login")
                                        .permitAll()
                                        .requestMatchers("/api/**")
                                        .authenticated()
                                        .anyRequest()
                                        .permitAll())
                .exceptionHandling(
                        handling -> handling.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .logout(logout -> logout.disable())
                .build();
    }
}
