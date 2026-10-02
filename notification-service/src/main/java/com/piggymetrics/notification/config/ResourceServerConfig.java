package com.piggymetrics.notification.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Resource server for notification-service (JWT local validation).
 * Replaces ResourceServerConfigurerAdapter + OAuth2FeignRequestInterceptor bean;
 * Feign OAuth2 propagation is handled by FeignOAuth2Config using client_credentials.
 */
@Configuration
@EnableWebSecurity
public class ResourceServerConfig {

	@Bean
	public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		http
				.authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
				.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.csrf(csrf -> csrf.disable())
				.oauth2ResourceServer(rs -> rs.jwt(Customizer.withDefaults()));
		return http.build();
	}

}
