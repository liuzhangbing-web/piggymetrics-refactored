package com.piggymetrics.account.config;

import feign.RequestTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;

import java.time.Instant;
import java.util.Collection;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Cases ACC-F01/F02: Feign OAuth2 propagation interceptor.
 */
class FeignOAuth2ConfigTest {

	private final FeignOAuth2Config config = new FeignOAuth2Config();

	private ClientRegistration registration() {
		return ClientRegistration.withRegistrationId("account-service")
				.clientId("account-service")
				.clientSecret("secret")
				.authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
				.tokenUri("http://auth-service:5000/uaa/oauth2/token")
				.build();
	}

	@Test
	@DisplayName("ACC-F01 token present -> Authorization: Bearer <token> header set")
	void addsBearerHeader() {
		OAuth2AuthorizedClientManager manager = mock(OAuth2AuthorizedClientManager.class);
		OAuth2AccessToken token = new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER,
				"jwt-token-value", Instant.now(), Instant.now().plusSeconds(3600));
		when(manager.authorize(any())).thenReturn(new OAuth2AuthorizedClient(registration(), "account-service", token));

		Environment env = new MockEnvironment();
		feign.RequestInterceptor interceptor = config.oauth2FeignRequestInterceptor(manager, env);
		RequestTemplate template = new RequestTemplate();
		interceptor.apply(template);

		Collection<String> headers = template.headers().get("Authorization");
		assertNotNull(headers);
		assertTrue(headers.contains("Bearer jwt-token-value"));
	}

	@Test
	@DisplayName("ACC-F02 manager returns null -> no header, no exception")
	void noTokenNoHeader() {
		OAuth2AuthorizedClientManager manager = mock(OAuth2AuthorizedClientManager.class);
		when(manager.authorize(any())).thenReturn(null);

		Environment env = new MockEnvironment();
		feign.RequestInterceptor interceptor = config.oauth2FeignRequestInterceptor(manager, env);
		RequestTemplate template = new RequestTemplate();
		assertDoesNotThrow(() -> interceptor.apply(template));
		assertFalse(template.headers().containsKey("Authorization"));
	}
}
