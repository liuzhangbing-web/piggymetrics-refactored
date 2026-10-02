package com.piggymetrics.account.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Resource server for account-service. Replaces the retired
 * ResourceServerConfigurerAdapter + CustomUserInfoTokenServices (user-info callback).
 * Tokens are now validated locally as JWT against auth-service JWK set.
 *
 * Access rules preserved from the legacy config:
 *   "/" and "/demo" -> permitAll ; everything else -> authenticated.
 */
@Configuration
@EnableWebSecurity
public class ResourceServerConfig {

	@Bean
	public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		http
				.authorizeHttpRequests(auth -> auth
						.requestMatchers("/", "/demo").permitAll()
						.anyRequest().authenticated())
				.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.csrf(csrf -> csrf.disable())
				.oauth2ResourceServer(rs -> rs.jwt(Customizer.withDefaults()));
		return http.build();
	}

}
