package com.piggymetrics.auth.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.core.annotation.Order;

/**
 * Protects auth-service's own endpoints (e.g. GET /uaa/users/current) as an OAuth2
 * resource server, validating JWTs with the local decoder (no user-info callback).
 * Ordered after the authorization-server chain (which is HIGHEST_PRECEDENCE).
 */
@Configuration
public class ResourceServerConfig {

	@Bean
	@Order(0)
	public SecurityFilterChain resourceServerFilterChain(HttpSecurity http, JwtDecoder jwtDecoder) throws Exception {
		http
				.securityMatcher("/users/**")
				.authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
				.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.csrf(csrf -> csrf.disable())
				.oauth2ResourceServer(rs -> rs.jwt(jwt -> jwt.decoder(jwtDecoder)));
		return http.build();
	}

}
