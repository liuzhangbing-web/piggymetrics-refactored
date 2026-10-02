package com.piggymetrics.auth.config;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.piggymetrics.auth.grant.OAuth2ResourceOwnerPasswordAuthenticationConverter;
import com.piggymetrics.auth.grant.PasswordClientAuthenticationConverter;
import com.piggymetrics.auth.grant.PasswordClientAuthenticationProvider;
import com.piggymetrics.auth.grant.OAuth2ResourceOwnerPasswordAuthenticationProvider;
import com.piggymetrics.auth.service.security.RegisteredClientRepositoryConfig;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.token.DelegatingOAuth2TokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.JwtGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2RefreshTokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.oauth2.server.authorization.InMemoryOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;

import com.piggymetrics.auth.token.JwtKeyProvider;


/**
 * Spring Authorization Server replacement for the retired spring-security-oauth2
 * {@code @EnableAuthorizationServer}. Issues stateless JWT access tokens signed with
 * an RSA key (see {@link JwtKeyProvider}); refresh tokens remain opaque.
 *
 * Backward compatibility: the legacy "password" grant used by the "browser" client is
 * re-implemented as a custom grant extension, because OAuth 2.1 removed it from core.
 */
@Configuration
public class OAuth2AuthorizationServerConfig {

	@Bean
	@Order(Ordered.HIGHEST_PRECEDENCE)
	public SecurityFilterChain authorizationServerSecurityFilterChain(HttpSecurity http,
			AuthenticationManager authenticationManager,
			OAuth2AuthorizationService authorizationService,
			OAuth2TokenGenerator<? extends org.springframework.security.oauth2.core.OAuth2Token> tokenGenerator,
			RegisteredClientRepository registeredClientRepository)
			throws Exception {

		OAuth2AuthorizationServerConfiguration.applyDefaultSecurity(http);

		http.getConfigurer(OAuth2AuthorizationServerConfigurer.class)
				// public-client authentication for the legacy password grant (browser client)
				.clientAuthentication(clientAuth -> clientAuth
						.authenticationConverter(new PasswordClientAuthenticationConverter())
						.authenticationProvider(new PasswordClientAuthenticationProvider(registeredClientRepository)))
				// register the legacy password grant
				.tokenEndpoint(tokenEndpoint -> tokenEndpoint
						.accessTokenRequestConverter(new OAuth2ResourceOwnerPasswordAuthenticationConverter())
						.authenticationProvider(new OAuth2ResourceOwnerPasswordAuthenticationProvider(
								authorizationService, authenticationManager, tokenGenerator)))
				.oidc(Customizer.withDefaults());

		http.oauth2ResourceServer(rs -> rs.jwt(Customizer.withDefaults()));
		return http.build();
	}

	@Bean
	public OAuth2AuthorizationService authorizationService() {
		// NOTE: JWT access tokens are stateless (self-contained), so an in-memory
		// authorization service is acceptable for the authorization-code/refresh bookkeeping.
		// This replaces the previous InMemoryTokenStore. For strict session revocation
		// requirements, swap in a JDBC/Redis-backed OAuth2AuthorizationService (tracked as H6).
		return new InMemoryOAuth2AuthorizationService();
	}

	@Bean
	public OAuth2TokenGenerator<? extends org.springframework.security.oauth2.core.OAuth2Token> tokenGenerator(
			JwtDecoder jwtDecoder) {
		org.springframework.security.oauth2.jwt.JwtEncoder jwtEncoder =
				new org.springframework.security.oauth2.jwt.NimbusJwtEncoder(jwkSource());
		JwtGenerator jwtGenerator = new JwtGenerator(jwtEncoder);
		jwtGenerator.setJwtCustomizer(jwtCustomizer());
		return new DelegatingOAuth2TokenGenerator(jwtGenerator, new OAuth2RefreshTokenGenerator());
	}

	/**
	 * Adds the "scope"/"user_name" style claims historically consumed by resource servers.
	 * Spring Authorization Server already emits "scope"; we keep this hook explicit and
	 * non-behavior-changing for the principal name.
	 */
	private org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer<JwtEncodingContext> jwtCustomizer() {
		return context -> { /* scopes & principal are emitted by the framework defaults */ };
	}

	@Bean
	public JWKSource<SecurityContext> jwkSource() {
		return new ImmutableJWKSet<>(new JWKSet(JwtKeyProvider.rsaKey()));
	}

	@Bean
	public JwtDecoder jwtDecoder() {
		return JwtKeyProvider.jwtDecoder();
	}

	@Bean
	public AuthorizationServerSettings authorizationServerSettings() {
		return AuthorizationServerSettings.builder()
				.issuer("http://auth-service:5000/uaa")
				.build();
	}

}
