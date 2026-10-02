package com.piggymetrics.auth.config;

import com.piggymetrics.auth.service.security.MongoUserDetailsService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Replaces the deprecated WebSecurityConfigurerAdapter (removed in Spring Security 6).
 * The AuthenticationManager is exposed as a bean and used both by the authorization
 * server's password grant extension and by DaoAuthenticationProvider.
 */
@Configuration
@EnableWebSecurity
public class WebSecurityConfig {

	private final MongoUserDetailsService userDetailsService;

	public WebSecurityConfig(MongoUserDetailsService userDetailsService) {
		this.userDetailsService = userDetailsService;
	}

	@Bean
	public PasswordEncoder passwordEncoder() {
		// NOTE: NoOpPasswordEncoder removed. Existing users are stored with BCrypt hashes
		// (UserServiceImpl#create encodes with BCryptPasswordEncoder), so BCrypt is the
		// compatible production encoder. Client secrets are also stored BCrypt-encoded.
		return new BCryptPasswordEncoder();
	}

	@Bean
	public AuthenticationManager authenticationManager(PasswordEncoder passwordEncoder) {
		DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
		provider.setUserDetailsService(userDetailsService);
		provider.setPasswordEncoder(passwordEncoder);
		return new ProviderManager(provider);
	}

	@Bean
	public SecurityFilterChain defaultSecurityFilterChain(HttpSecurity http) throws Exception {
		http
				.authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
				.csrf(csrf -> csrf.disable())
				.httpBasic(Customizer.withDefaults())
				.formLogin(form -> form.disable());
		return http.build();
	}

}
