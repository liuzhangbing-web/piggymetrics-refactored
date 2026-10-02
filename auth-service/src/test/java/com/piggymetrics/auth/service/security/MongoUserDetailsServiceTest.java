package com.piggymetrics.auth.service.security;

import com.piggymetrics.auth.domain.User;
import com.piggymetrics.auth.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/** Case USR-C04 + loadUserByUsername behavior. */
@ExtendWith(MockitoExtension.class)
class MongoUserDetailsServiceTest {

	@Mock
	private UserRepository repository;

	@InjectMocks
	private MongoUserDetailsService service;

	@Test
	@DisplayName("loadUserByUsername returns stored user")
	void found() {
		User user = new User();
		user.setUsername("test-user");
		user.setPassword("$2a$10$hash");
		when(repository.findById("test-user")).thenReturn(Optional.of(user));

		UserDetails loaded = service.loadUserByUsername("test-user");
		assertEquals("test-user", loaded.getUsername());
		assertEquals("$2a$10$hash", loaded.getPassword());
		assertTrue(loaded.isEnabled());
	}

	@Test
	@DisplayName("USR-C04 loadUserByUsername unknown -> UsernameNotFoundException")
	void notFound() {
		when(repository.findById("ghost")).thenReturn(Optional.empty());
		assertThrows(UsernameNotFoundException.class, () -> service.loadUserByUsername("ghost"));
	}
}
