package com.piggymetrics.statistics.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.piggymetrics.statistics.config.ResourceServerConfig;
import com.piggymetrics.statistics.domain.Account;
import com.piggymetrics.statistics.domain.Currency;
import com.piggymetrics.statistics.domain.Saving;
import com.piggymetrics.statistics.service.StatisticsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Cases STA-C01..C04: verifies scope-based authorization equivalence (not relaxed).
 */
@WebMvcTest(controllers = StatisticsController.class,
		properties = "server.servlet.context-path=")
@Import(ResourceServerConfig.class)
class StatisticsControllerTest {
	// @EnableMethodSecurity comes from the primary configuration (StatisticsApplication).

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@MockBean
	private StatisticsService statisticsService;

	@MockBean
	private JwtDecoder jwtDecoder;

	private Account validAccount() {
		Account account = new Account();
		account.setIncomes(Collections.emptyList());
		account.setExpenses(Collections.emptyList());
		Saving saving = new Saving();
		saving.setAmount(new BigDecimal("100"));
		saving.setCurrency(Currency.USD);
		saving.setInterest(BigDecimal.ZERO);
		saving.setDeposit(false);
		saving.setCapitalization(false);
		account.setSaving(saving);
		return account;
	}

	@Test
	@DisplayName("STA-C01 [RED] PUT /{accountName} with SCOPE_server -> 200, service.save invoked")
	void putWithServerScope() throws Exception {
		mockMvc.perform(put("/acct-1").with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_server")))
						.contentType("application/json")
						.content(objectMapper.writeValueAsString(validAccount())))
				.andExpect(status().isOk());
		verify(statisticsService).save(eq("acct-1"), any(Account.class));
	}

	@Test
	@DisplayName("STA-C02 [RED] PUT /{accountName} without server scope -> 403")
	void putWithoutServerScope() throws Exception {
		mockMvc.perform(put("/acct-1").with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_ui")))
						.contentType("application/json")
						.content(objectMapper.writeValueAsString(validAccount())))
				.andExpect(status().isForbidden());
		verify(statisticsService, never()).save(anyString(), any());
	}

	@Test
	@DisplayName("STA-C03 [RED] GET /demo without scope -> 200 (or-branch preserved)")
	void getDemoWithoutScope() throws Exception {
		when(statisticsService.findByAccountName("demo")).thenReturn(Collections.emptyList());
		mockMvc.perform(get("/demo").with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_ui"))))
				.andExpect(status().isOk());
	}

	@Test
	@DisplayName("STA-C04 GET /current -> queried by principal name")
	void getCurrent() throws Exception {
		when(statisticsService.findByAccountName("test-user")).thenReturn(Collections.emptyList());
		mockMvc.perform(get("/current").with(jwt().jwt(j -> j.subject("test-user"))))
				.andExpect(status().isOk());
		verify(statisticsService).findByAccountName("test-user");
	}
}
