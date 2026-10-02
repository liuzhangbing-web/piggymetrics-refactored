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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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

	// ================= Plan-A compensation endpoint (FT-AUTH-012..015) =================

	@Test
	@DisplayName("FT-AUTH-012 [RED] DELETE /users/{username} with SCOPE_server -> 200, deleteByUsername invoked")
	void deleteUserWithServerScope() throws Exception {
		when(userService.deleteByUsername("orphan")).thenReturn(true);

		mockMvc.perform(delete("/users/orphan")
						.with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_server"))))
				.andExpect(status().isOk());
		verify(userService).deleteByUsername("orphan");
	}

	@Test
	@DisplayName("FT-AUTH-013 [RED] DELETE /users/{username} with scope=ui -> 403, service untouched")
	void deleteUserWithUiScopeForbidden() throws Exception {
		mockMvc.perform(delete("/users/orphan")
						.with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_ui"))))
				.andExpect(status().isForbidden());
		verify(userService, never()).deleteByUsername(anyString());
	}

	@Test
	@DisplayName("FT-AUTH-014 [RED] DELETE /users/{username} anonymous -> 401")
	void deleteUserAnonymous() throws Exception {
		mockMvc.perform(delete("/users/orphan"))
				.andExpect(status().isUnauthorized());
		verify(userService, never()).deleteByUsername(anyString());
	}

	@Test
	@DisplayName("FT-AUTH-015 [RED] DELETE and create share the identical @PreAuthorize expression (anti-relaxation lock)")
	void deleteAndCreatePreAuthorizeIdentical() throws Exception {
		java.lang.reflect.Method create = UserController.class
				.getMethod("createUser", User.class);
		java.lang.reflect.Method delete = UserController.class
				.getMethod("deleteUser", String.class);
		org.springframework.security.access.prepost.PreAuthorize createAnn =
				create.getAnnotation(org.springframework.security.access.prepost.PreAuthorize.class);
		org.springframework.security.access.prepost.PreAuthorize deleteAnn =
				delete.getAnnotation(org.springframework.security.access.prepost.PreAuthorize.class);
		assertNotNull(createAnn, "createUser must keep @PreAuthorize");
		assertNotNull(deleteAnn, "deleteUser must keep @PreAuthorize");
		assertEquals(createAnn.value(), deleteAnn.value(),
				"compensation delete must never be less strict than create (red-line lock)");
		assertEquals("hasAuthority('SCOPE_server')", deleteAnn.value());
	}
}
