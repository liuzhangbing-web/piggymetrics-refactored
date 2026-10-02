package com.piggymetrics.auth.service.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;

import com.piggymetrics.auth.grant.OAuth2ResourceOwnerPasswordAuthenticationToken;

import java.time.Duration;

/**
 * Replaces the retired in-memory ClientDetailsServiceConfigurer from
 * OAuth2AuthorizationConfig. The four legacy clients are preserved with identical
 * grant types and scopes:
 *   browser            -> refresh_token, password          (scope: ui)
 *   account-service    -> client_credentials, refresh_token (scope: server)
 *   statistics-service -> client_credentials, refresh_token (scope: server)
 *   notification-service-> client_credentials, refresh_token (scope: server)
 *
 * SECURITY (H6): secrets are no longer stored plaintext. They are read from the
 * environment and BCrypt-encoded at registration; in production the encoded value
 * should come from KMS/Nacos-encrypted config instead of raw env (tracked separately).
 */
@Configuration
public class RegisteredClientRepositoryConfig {

	@Bean
	public RegisteredClientRepository registeredClientRepository(Environment env, PasswordEncoder passwordEncoder) {

		TokenSettings tokenSettings = TokenSettings.builder()
				.accessTokenTimeToLive(Duration.ofHours(1))
				.refreshTokenTimeToLive(Duration.ofHours(12))
				.reuseRefreshTokens(false)
				.build();

		RegisteredClient browser = RegisteredClient.withId("browser")
				.clientId("browser")
				.clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
				.authorizationGrantType(OAuth2ResourceOwnerPasswordAuthenticationToken.PASSWORD_GRANT_TYPE)
				.authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
				.scope("ui")
				.tokenSettings(tokenSettings)
				.build();

		RegisteredClient accountService = serviceClient("account-service",
				env.getProperty("ACCOUNT_SERVICE_PASSWORD"), passwordEncoder, tokenSettings);
		RegisteredClient statisticsService = serviceClient("statistics-service",
				env.getProperty("STATISTICS_SERVICE_PASSWORD"), passwordEncoder, tokenSettings);
		RegisteredClient notificationService = serviceClient("notification-service",
				env.getProperty("NOTIFICATION_SERVICE_PASSWORD"), passwordEncoder, tokenSettings);

		return new InMemoryRegisteredClientRepository(
				browser, accountService, statisticsService, notificationService);
	}

	private RegisteredClient serviceClient(String clientId, String rawSecret,
			PasswordEncoder passwordEncoder, TokenSettings tokenSettings) {
		return RegisteredClient.withId(clientId)
				.clientId(clientId)
				.clientSecret(passwordEncoder.encode(rawSecret == null ? "" : rawSecret))
				.clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
				.clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
				.authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
				.authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
				.scope("server")
				.tokenSettings(tokenSettings)
				.build();
	}

}
