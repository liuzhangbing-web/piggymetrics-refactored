package com.piggymetrics.account.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.piggymetrics.account.config.ResourceServerConfig;
import com.piggymetrics.account.domain.Account;
import com.piggymetrics.account.domain.Currency;
import com.piggymetrics.account.domain.Saving;
import com.piggymetrics.account.domain.User;
import com.piggymetrics.account.service.AccountService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Cases ACC-C01..C07 + ACC-S01/S02: controller behavior and JWT resource-server rules,
 * verifying the legacy #oauth2.hasScope('server') -> hasAuthority('SCOPE_server') mapping
 * is equivalent and NOT relaxed.
 */
@WebMvcTest(controllers = {AccountController.class, ErrorHandler.class},
		properties = "server.servlet.context-path=")
@Import(ResourceServerConfig.class)
class AccountControllerTest {
	// NOTE: @EnableMethodSecurity comes from the detected primary configuration
	// (AccountApplication). A nested @Configuration here would REPLACE the primary
	// config and break controller detection - do not re-add.

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@MockBean
	private AccountService accountService;

	@MockBean
	private JwtDecoder jwtDecoder;

	private Account demoAccount() {
		Account account = new Account();
		account.setName("demo");
		Saving saving = new Saving();
		saving.setAmount(new java.math.BigDecimal("0"));
		saving.setCurrency(Currency.USD);
		saving.setInterest(new java.math.BigDecimal("0"));
		saving.setDeposit(false);
		saving.setCapitalization(false);
		account.setSaving(saving);
		return account;
	}

	@Test
	@DisplayName("ACC-C01 [RED] GET /{name} with SCOPE_server -> 200")
	void getByName_serverScope() throws Exception {
		when(accountService.findByName("demo")).thenReturn(demoAccount());
		mockMvc.perform(get("/demo").with(jwt().authorities(
					new org.springframework.security.core.authority.SimpleGrantedAuthority("SCOPE_server"))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("demo"))
				.andExpect(jsonPath("$.saving.currency").value("USD"));
	}

	@Test
	@DisplayName("ACC-C02 [RED] GET /demo without server scope -> 200 (SpEL or-branch)")
	void getByName_demoWithoutScope() throws Exception {
		when(accountService.findByName("demo")).thenReturn(demoAccount());
		mockMvc.perform(get("/demo").with(jwt().authorities(
					new org.springframework.security.core.authority.SimpleGrantedAuthority("SCOPE_ui"))))
				.andExpect(status().isOk());
	}

	@Test
	@DisplayName("ACC-C03 [RED] GET /{other} without server scope -> 403 (must NOT be relaxed)")
	void getByName_otherUserForbidden() throws Exception {
		mockMvc.perform(get("/somebody").with(jwt().authorities(
					new org.springframework.security.core.authority.SimpleGrantedAuthority("SCOPE_ui"))))
				.andExpect(status().isForbidden());
		verify(accountService, never()).findByName(anyString());
	}

	@Test
	@DisplayName("ACC-C04 GET /current with user JWT -> queried by principal name")
	void getCurrent() throws Exception {
		when(accountService.findByName("test-user")).thenReturn(demoAccount());
		mockMvc.perform(get("/current").with(jwt().jwt(j -> j.subject("test-user"))))
				.andExpect(status().isOk());
		verify(accountService).findByName("test-user");
	}

	@Test
	@DisplayName("ACC-C05 PUT /current -> saveChanges(principalName, body)")
	void putCurrent() throws Exception {
		Account body = demoAccount();
		mockMvc.perform(put("/current").with(jwt().jwt(j -> j.subject("test-user")))
						.contentType("application/json")
						.content(objectMapper.writeValueAsString(body)))
				.andExpect(status().isOk());
		verify(accountService).saveChanges(eq("test-user"), any(Account.class));
	}

	@Test
	@DisplayName("ACC-C06 POST / validation: username too short -> 400")
	void createValidation() throws Exception {
		User user = new User();
		user.setUsername("ab");
		user.setPassword("123");
		mockMvc.perform(post("/").contentType("application/json")
						.content(objectMapper.writeValueAsString(user)))
				.andExpect(status().isBadRequest());
		verify(accountService, never()).create(any());
	}

	@Test
	@DisplayName("ACC-C07/S02 unauthenticated protected endpoint -> 401")
	void unauthenticated() throws Exception {
		mockMvc.perform(get("/current")).andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("ACC-S01 [RED] GET / without token -> NOT 401 (permitAll preserved)")
	void rootPermitAll() throws Exception {
		// "/" is permitAll in ResourceServerConfig. GET / passes the security chain and
		// reaches the dispatcher, where only POST is mapped -> 405 Method Not Allowed.
		// 405 (not 401) is the proof that permitAll survived the migration.
		mockMvc.perform(get("/")).andExpect(status().isMethodNotAllowed());
	}

	@Test
	@DisplayName("ACC-S01b GET /demo without token -> permitAll reaches controller")
	void demoPermitAll() throws Exception {
		when(accountService.findByName("demo")).thenReturn(demoAccount());
		mockMvc.perform(get("/demo")).andExpect(status().isOk());
	}
}
