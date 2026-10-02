package com.piggymetrics.auth.service.security;

import com.piggymetrics.auth.grant.OAuth2ResourceOwnerPasswordAuthenticationToken;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Parity test: the 4 legacy clients keep identical grant types / scopes / auth methods
 * (equivalence requirement from stage-1 M2 - authorization semantics must NOT change).
 */
class RegisteredClientRepositoryConfigTest {

	private final RegisteredClientRepository repository = new RegisteredClientRepositoryConfig()
			.registeredClientRepository(envWithSecrets(), new BCryptPasswordEncoder());

	private MockEnvironment envWithSecrets() {
		MockEnvironment env = new MockEnvironment();
		env.setProperty("ACCOUNT_SERVICE_PASSWORD", "acct-secret");
		env.setProperty("STATISTICS_SERVICE_PASSWORD", "stat-secret");
		env.setProperty("NOTIFICATION_SERVICE_PASSWORD", "notif-secret");
		return env;
	}

	@Test
	@DisplayName("browser client: password(legacy)+refresh_token, scope=ui, public client")
	void browserClient() {
		RegisteredClient browser = repository.findByClientId("browser");
		assertNotNull(browser);
		assertTrue(browser.getAuthorizationGrantTypes()
				.contains(OAuth2ResourceOwnerPasswordAuthenticationToken.PASSWORD_GRANT_TYPE));
		assertTrue(browser.getAuthorizationGrantTypes().contains(AuthorizationGrantType.REFRESH_TOKEN));
		assertEquals(java.util.Set.of("ui"), browser.getScopes());
		assertTrue(browser.getClientAuthenticationMethods().contains(ClientAuthenticationMethod.NONE));
	}

	@Test
	@DisplayName("service clients: client_credentials+refresh_token, scope=server, secret BCrypt-encoded")
	void serviceClients() {
		for (String id : new String[]{"account-service", "statistics-service", "notification-service"}) {
			RegisteredClient client = repository.findByClientId(id);
			assertNotNull(client, id + " must be registered");
			assertTrue(client.getAuthorizationGrantTypes().contains(AuthorizationGrantType.CLIENT_CREDENTIALS), id);
			assertTrue(client.getAuthorizationGrantTypes().contains(AuthorizationGrantType.REFRESH_TOKEN), id);
			assertEquals(java.util.Set.of("server"), client.getScopes(), id);
			assertNotNull(client.getClientSecret());
			assertTrue(client.getClientSecret().startsWith("$2a$"),
					id + " secret must be BCrypt-encoded, not plaintext");
		}
	}
}
