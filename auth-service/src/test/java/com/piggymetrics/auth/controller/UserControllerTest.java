package com.piggymetrics.auth.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.piggymetrics.auth.domain.User;
import com.piggymetrics.auth.service.UserService;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Cases USR-C01..C03: POST /users requires SCOPE_server (RED-LINE, must not be relaxed).
 * The full SecurityFilterChain (authorization server + resource server) is not loaded here;
 * a minimal test chain plus method security reproduces the authorization rule under test.
 */
@WebMvcTest(controllers = UserController.class,
		properties = "server.servlet.context-path=")
@Import(AuthResourceServerTestConfig.class)
class UserControllerTest {
	// Security chain + method security provided by a TOP-LEVEL test configuration
	// (nested @Configuration would replace the primary config and break controller
	// detection). Production AuthApplication already carries @EnableMethodSecurity.

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@MockBean
	private UserService userService;

	@MockBean
	private JwtDecoder jwtDecoder;

	@Test
	@DisplayName("USR-C01 [RED] POST /users with SCOPE_server -> 200, service.create invoked")
	void createUserWithServerScope() throws Exception {
		User user = new User();
		user.setUsername("new-user");
		user.setPassword("secret1");

		mockMvc.perform(post("/users").with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_server")))
						.contentType("application/json")
						.content(objectMapper.writeValueAsString(user)))
				.andExpect(status().isOk());
		verify(userService).create(any(User.class));
	}

	@Test
	@DisplayName("USR-C02 [RED] POST /users with scope=ui only -> 403")
	void createUserWithUiScopeForbidden() throws Exception {
		User user = new User();
		user.setUsername("new-user");
		user.setPassword("secret1");

		mockMvc.perform(post("/users").with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_ui")))
						.contentType("application/json")
						.content(objectMapper.writeValueAsString(user)))
				.andExpect(status().isForbidden());
		verify(userService, never()).create(any());
	}

	@Test
	@DisplayName("USR-C03 GET /users/current with user JWT -> 200")
	void currentUser() throws Exception {
		mockMvc.perform(get("/users/current").with(jwt().jwt(j -> j.subject("test-user"))))
				.andExpect(status().isOk());
	}
}
