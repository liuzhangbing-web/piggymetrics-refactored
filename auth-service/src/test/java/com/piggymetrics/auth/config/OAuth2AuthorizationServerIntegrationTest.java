package com.piggymetrics.auth.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.piggymetrics.auth.domain.User;
import com.piggymetrics.auth.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MongoDBContainer;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Cases AUTH-01..08 [RED]: Spring Authorization Server integration - client_credentials,
 * legacy password grant extension, refresh token, JWKS, error paths.
 * Runs the FULL application context against a real MongoDB.
 *
 * Mongo source resolution (in order):
 *  1. TEST_MONGO_URI env var (e.g. a manually started container: mongodb://localhost:27117)
 *  2. Testcontainers MongoDBContainer (mongo:4.4), when a Docker environment is usable
 *  3. neither -> the whole class is skipped (@EnabledIf), build stays green
 *
 * NOTE: on this machine docker-java/Testcontainers 1.21.2 cannot talk to the Docker Desktop
 * socket forwarder (HTTP 400 on /info regardless of socket/API version), so path 1 is used.
 */
@SpringBootTest(properties = {
		"spring.cloud.nacos.discovery.enabled=false",
		"spring.cloud.nacos.config.enabled=false",
		"spring.cloud.sentinel.enabled=false",
		"spring.config.import=",
		"ACCOUNT_SERVICE_PASSWORD=account-secret",
		"STATISTICS_SERVICE_PASSWORD=statistics-secret",
		"NOTIFICATION_SERVICE_PASSWORD=notification-secret"
})
@AutoConfigureMockMvc
@EnabledIf("com.piggymetrics.auth.config.OAuth2AuthorizationServerIntegrationTest#mongoAvailable")
class OAuth2AuthorizationServerIntegrationTest {

	private static MongoDBContainer container;
	private static String mongoUri;

	static {
		String uri = System.getenv("TEST_MONGO_URI");
		if (uri == null || uri.isBlank()) {
			uri = System.getProperty("test.mongo.uri");
		}
		if (uri != null && !uri.isBlank()) {
			mongoUri = uri;
		}
		else {
			try {
				if (DockerClientFactory.instance().isDockerAvailable()) {
					container = new MongoDBContainer("mongo:4.4");
					container.start();
					mongoUri = container.getReplicaSetUrl();
				}
			}
			catch (Throwable t) {
				mongoUri = null; // class will be skipped
			}
		}
	}

	static boolean mongoAvailable() {
		return mongoUri != null;
	}

	@DynamicPropertySource
	static void mongoProps(DynamicPropertyRegistry registry) {
		registry.add("spring.data.mongodb.uri", () -> mongoUri != null ? mongoUri : "mongodb://localhost:27017");
		registry.add("spring.data.mongodb.database", () -> "piggymetrics-test");
	}

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private UserRepository userRepository;

	private final ObjectMapper objectMapper = new ObjectMapper();

	@BeforeEach
	void seedUser() {
		if (userRepository.findById("it-user").isEmpty()) {
			User user = new User();
			user.setUsername("it-user");
			user.setPassword(new BCryptPasswordEncoder().encode("it-password"));
			userRepository.save(user);
		}
	}

	private static String basic(String id, String secret) {
		return "Basic " + Base64.getEncoder().encodeToString((id + ":" + secret).getBytes(StandardCharsets.UTF_8));
	}

	private JsonNode token(String clientId, String secret, String form) throws Exception {
		MvcResult result = mockMvc.perform(post("/oauth2/token")
						.header(HttpHeaders.AUTHORIZATION, basic(clientId, secret))
						.contentType("application/x-www-form-urlencoded")
						.content(form))
				.andExpect(status().isOk())
				.andReturn();
		return objectMapper.readTree(result.getResponse().getContentAsString());
	}

	private JsonNode decodeJwtPayload(String jwt) throws Exception {
		String[] parts = jwt.split("\\.");
		assertEquals(3, parts.length, "access token must be a JWT (three parts)");
		return objectMapper.readTree(Base64.getUrlDecoder().decode(parts[1]));
	}

	@Test
	@DisplayName("AUTH-01 [RED] client_credentials: account-service -> JWT with scope=server, RS256")
	void clientCredentials() throws Exception {
		JsonNode body = token("account-service", "account-secret",
				"grant_type=client_credentials&scope=server");
		String accessToken = body.get("access_token").asText();
		assertEquals("Bearer", body.get("token_type").asText());
		JsonNode payload = decodeJwtPayload(accessToken);
		assertEquals("account-service", payload.get("sub").asText());
		assertTrue(payload.get("scope").toString().contains("server"),
				"scope claim must contain 'server': " + payload.get("scope"));
		String[] parts = accessToken.split("\\.");
		JsonNode header = objectMapper.readTree(Base64.getUrlDecoder().decode(parts[0]));
		assertEquals("RS256", header.get("alg").asText());
	}

	@Test
	@DisplayName("AUTH-02 [RED] client_credentials with wrong secret -> 401")
	void clientCredentialsWrongSecret() throws Exception {
		mockMvc.perform(post("/oauth2/token")
						.header(HttpHeaders.AUTHORIZATION, basic("account-service", "wrong"))
						.contentType("application/x-www-form-urlencoded")
						.content("grant_type=client_credentials&scope=server"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("AUTH-03 [RED] legacy password grant (browser): valid user -> JWT sub=username, scope=ui, refresh token issued")
	void passwordGrant() throws Exception {
		MvcResult pwResult = mockMvc.perform(post("/oauth2/token")
						.param("grant_type", "password")
						.param("client_id", "browser")
						.param("username", "it-user")
						.param("password", "it-password")
						.param("scope", "ui")
						.contentType("application/x-www-form-urlencoded"))
				.andExpect(status().isOk())
				.andReturn();
		JsonNode body = objectMapper.readTree(pwResult.getResponse().getContentAsString());
		JsonNode payload = decodeJwtPayload(body.get("access_token").asText());
		assertEquals("it-user", payload.get("sub").asText());
		assertTrue(payload.get("scope").toString().contains("ui"));
		assertNotNull(body.get("refresh_token"), "refresh token must be issued (legacy parity)");
	}

	@Test
	@DisplayName("AUTH-04 [RED] password grant with wrong password -> 400 invalid_grant")
	void passwordGrantWrongPassword() throws Exception {
		MvcResult result = mockMvc.perform(post("/oauth2/token")
						.param("grant_type", "password")
						.param("client_id", "browser")
						.param("username", "it-user")
						.param("password", "wrong-password")
						.param("scope", "ui")
						.contentType("application/x-www-form-urlencoded"))
				.andExpect(status().isBadRequest())
				.andReturn();
		assertTrue(result.getResponse().getContentAsString().contains("invalid_grant"));
	}

	@Test
	@DisplayName("AUTH-05 refresh_token flow issues new access token; rotated refresh token invalidates the old one")
	void refreshToken() throws Exception {
		MvcResult pw = mockMvc.perform(post("/oauth2/token")
						.param("grant_type", "password")
						.param("client_id", "browser")
						.param("username", "it-user")
						.param("password", "it-password")
						.param("scope", "ui")
						.contentType("application/x-www-form-urlencoded"))
				.andExpect(status().isOk()).andReturn();
		JsonNode pwBody = objectMapper.readTree(pw.getResponse().getContentAsString());
		String refresh = pwBody.get("refresh_token").asText();

		MvcResult rr = mockMvc.perform(post("/oauth2/token")
						.param("grant_type", "refresh_token")
						.param("refresh_token", refresh)
						.param("client_id", "browser")
						.contentType("application/x-www-form-urlencoded"))
				.andExpect(status().isOk()).andReturn();
		JsonNode refreshed = objectMapper.readTree(rr.getResponse().getContentAsString());
		assertNotNull(refreshed.get("access_token"));

		// reuseRefreshTokens=false -> old refresh token must no longer work
		mockMvc.perform(post("/oauth2/token")
						.param("grant_type", "refresh_token")
						.param("refresh_token", refresh)
						.param("client_id", "browser")
						.contentType("application/x-www-form-urlencoded"))
				.andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("AUTH-06 [RED] JWKS endpoint exposes RSA public key with kid")
	void jwks() throws Exception {
		MvcResult result = mockMvc.perform(get("/oauth2/jwks"))
				.andExpect(status().isOk())
				.andReturn();
		JsonNode jwks = objectMapper.readTree(result.getResponse().getContentAsString());
		assertTrue(jwks.get("keys").size() >= 1);
		assertEquals("RSA", jwks.get("keys").get(0).get("kty").asText());
		assertNotNull(jwks.get("keys").get(0).get("kid"));
	}

	@Test
	@DisplayName("AUTH-07 request unsupported scope -> 400 invalid_scope")
	void invalidScope() throws Exception {
		MvcResult result = mockMvc.perform(post("/oauth2/token")
						.header(HttpHeaders.AUTHORIZATION, basic("account-service", "account-secret"))
						.contentType("application/x-www-form-urlencoded")
						.content("grant_type=client_credentials&scope=admin"))
				.andExpect(status().isBadRequest())
				.andReturn();
		assertTrue(result.getResponse().getContentAsString().contains("invalid_scope"));
	}

	@Test
	@DisplayName("AUTH-08 unregistered client_id -> 401")
	void unknownClient() throws Exception {
		mockMvc.perform(post("/oauth2/token")
						.header(HttpHeaders.AUTHORIZATION, basic("ghost-service", "x"))
						.contentType("application/x-www-form-urlencoded")
						.content("grant_type=client_credentials&scope=server"))
				.andExpect(status().isUnauthorized());
	}
}
