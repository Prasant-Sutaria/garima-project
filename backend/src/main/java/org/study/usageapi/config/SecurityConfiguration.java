package org.study.usageapi.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.study.usageapi.service.JwtAuthenticationFilter;
import org.study.usageapi.service.SecurityErrorWriter;

@Configuration
public class SecurityConfiguration {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    @Profile("local")
    UserDetailsService localAdminUser(
            PasswordEncoder encoder,
            @Value("${backend.admin.username}") String username,
            @Value("${backend.admin.password}") String password) {
        if (username.isBlank() || password.length() < 20) {
            throw new IllegalStateException("ADMIN_USERNAME and a 20-character ADMIN_PASSWORD are required");
        }
        return new InMemoryUserDetailsManager(
                User.withUsername(username).password(encoder.encode(password)).roles("ADMIN").build());
    }

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http,
                                   JwtAuthenticationFilter jwtAuthenticationFilter,
                                   SecurityErrorWriter errorWriter) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(AbstractHttpConfigurer::disable)
                .sessionManagement(config -> config.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(AbstractHttpConfigurer::disable)
                .httpBasic(Customizer.withDefaults())
                .exceptionHandling(config -> config
                        .authenticationEntryPoint(errorWriter)
                        .accessDeniedHandler(errorWriter))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/v3/api-docs/admin", "/v3/api-docs/admin/**",
                                "/v3/api-docs", "/v3/api-docs.yaml").hasRole("ADMIN")
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/v1/usage/snapshots")
                        .hasAuthority("SCOPE_usage:write")
                        .requestMatchers(HttpMethod.GET, "/api/v1/usage/snapshots/**")
                        .hasAuthority("SCOPE_usage:write")
                        .anyRequest().permitAll())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
