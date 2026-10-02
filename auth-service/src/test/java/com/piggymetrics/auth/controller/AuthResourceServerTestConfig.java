package com.piggymetrics.auth.controller;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Minimal resource-server chain for UserControllerTest (TOP-LEVEL @TestConfiguration:
 * a nested @Configuration inside the test class would replace the primary configuration
 * and break @WebMvcTest controller detection).
 */
@TestConfiguration
public class AuthResourceServerTestConfig {

	@Bean
	public SecurityFilterChain testChain(HttpSecurity http) throws Exception {
		http.authorizeHttpRequests(a -> a.anyRequest().authenticated())
				.csrf(c -> c.disable())
				.oauth2ResourceServer(o -> o.jwt(j -> {
				}));
		return http.build();
	}

}
