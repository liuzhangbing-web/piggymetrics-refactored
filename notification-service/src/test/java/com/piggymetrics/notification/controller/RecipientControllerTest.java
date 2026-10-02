package com.piggymetrics.notification.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.piggymetrics.notification.config.ResourceServerConfig;
import com.piggymetrics.notification.domain.Recipient;
import com.piggymetrics.notification.service.RecipientService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * dp-spec FT-NOT-001..003: notification recipient endpoints behind the JWT resource
 * server (anyRequest().authenticated()). Slice test: service mocked, no cross-service calls.
 * context-path neutralized so /recipients/** is hit directly (same pattern as
 * AccountControllerTest / UserControllerTest).
 */
@WebMvcTest(controllers = RecipientController.class,
		properties = {
				"server.servlet.context-path=",
				// slice test: no Nacos / no Sentinel / no config-import (same isolation
				// pattern as GatewayRoutesTest)
				"spring.cloud.nacos.discovery.enabled=false",
				"spring.cloud.nacos.config.enabled=false",
				"spring.cloud.sentinel.enabled=false",
				"spring.config.import="
		})
@Import(ResourceServerConfig.class)
class RecipientControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@MockBean
	private RecipientService recipientService;

	@MockBean
	private JwtDecoder jwtDecoder;

	// NotificationServiceApplication defines a ShedLock LockProvider @Bean that calls
	// mongoClient.getDatabase(...).getCollection(...) at creation time; the WebMvc slice
	// excludes Mongo autoconfiguration, so mock with DEEP STUBS (plain mock returns null
	// from getDatabase and MongoLockProvider NPEs during bean creation).
	@MockBean(answer = org.mockito.Answers.RETURNS_DEEP_STUBS)
	private com.mongodb.client.MongoClient mongoClient;

	private Recipient validRecipient() {
		Recipient r = new Recipient();
		r.setEmail("test@example.com");
		r.setScheduledNotifications(Map.of(
				com.piggymetrics.notification.domain.NotificationType.BACKUP,
				notifications(true, com.piggymetrics.notification.domain.Frequency.WEEKLY),
				com.piggymetrics.notification.domain.NotificationType.REMIND,
				notifications(true, com.piggymetrics.notification.domain.Frequency.MONTHLY)));
		return r;
	}

	private com.piggymetrics.notification.domain.NotificationSettings notifications(
			boolean active, com.piggymetrics.notification.domain.Frequency f) {
		com.piggymetrics.notification.domain.NotificationSettings s =
				new com.piggymetrics.notification.domain.NotificationSettings();
		s.setActive(active);
		s.setFrequency(f);
		return s;
	}

	@Test
	@DisplayName("FT-NOT-001 GET /recipients/current with user JWT -> 200, queried by principal name")
	void getCurrentSettings() throws Exception {
		Recipient stored = validRecipient();
		when(recipientService.findByAccountName("test-user")).thenReturn(stored);

		mockMvc.perform(get("/recipients/current").with(jwt().jwt(j -> j.subject("test-user"))))
				.andExpect(status().isOk());

		verify(recipientService).findByAccountName(eq("test-user"));
	}

	@Test
	@DisplayName("FT-NOT-002 PUT /recipients/current with user JWT + valid body -> 200, save(principalName, body)")
	void saveCurrentSettings() throws Exception {
		Recipient body = validRecipient();
		when(recipientService.save(eq("test-user"), any(Recipient.class))).thenReturn(body);

		mockMvc.perform(put("/recipients/current")
						.with(jwt().jwt(j -> j.subject("test-user")))
						.contentType("application/json")
						.content(objectMapper.writeValueAsString(body)))
				.andExpect(status().isOk());

		verify(recipientService).save(eq("test-user"), any(Recipient.class));
	}

	@Test
	@DisplayName("FT-NOT-003 [RED] unauthenticated access to /recipients/current -> 401 (resource server)")
	void unauthenticatedForbidden() throws Exception {
		mockMvc.perform(get("/recipients/current"))
				.andExpect(status().isUnauthorized());
		verifyNoInteractions(recipientService);
	}
}
