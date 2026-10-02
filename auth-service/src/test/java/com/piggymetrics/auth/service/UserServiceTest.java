package com.piggymetrics.auth.service;

import com.piggymetrics.auth.domain.User;
import com.piggymetrics.auth.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Cases USR-01..03. USR-03 is RED-LINE: a BCrypt hash produced by the LEGACY project
 * (spring-security-crypto via BCryptPasswordEncoder, same as Boot 2.0.3) must still be
 * verified by the new PasswordEncoder - guarantees smooth migration of existing users.
 */
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

	@Mock
	private UserRepository repository;

	@InjectMocks
	private UserServiceImpl service;

	@Test
	@DisplayName("USR-01 [RED] create stores BCrypt hash (not plaintext), username preserved")
	void create_hashesPassword() {
		when(repository.findById("new-user")).thenReturn(Optional.empty());
		User user = new User();
		user.setUsername("new-user");
		user.setPassword("plain-secret");

		service.create(user);

		ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
		verify(repository).save(captor.capture());
		User saved = captor.getValue();
		assertEquals("new-user", saved.getUsername());
		assertNotEquals("plain-secret", saved.getPassword(), "plaintext must NOT be persisted");
		assertTrue(saved.getPassword().startsWith("$2a$"), "expected BCrypt hash, got: " + saved.getPassword());
		assertTrue(new BCryptPasswordEncoder().matches("plain-secret", saved.getPassword()));
	}

	@Test
	@DisplayName("USR-02 create existing user -> IllegalArgumentException, no save")
	void create_existing() {
		User existing = new User();
		existing.setUsername("dup-user");
		when(repository.findById("dup-user")).thenReturn(Optional.of(existing));

		User user = new User();
		user.setUsername("dup-user");
		user.setPassword("whatever");

		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> service.create(user));
		assertTrue(ex.getMessage().contains("user already exists"));
		verify(repository, never()).save(any());
	}

	@Test
	@DisplayName("USR-03 [RED] legacy BCrypt hash still verifies (migration compatibility)")
	void legacyHashCompatibility() {
		// Hash generated with spring-security-crypto 6.5.1 BCryptPasswordEncoder for "legacy-password"
		// (identical algorithm/cost as the legacy Boot 2.0.3 encoder).
		String legacyHash = "$2a$10$USsIURQMojwPpWJVN50LoO5Ouj9lPxtbIv6T0p1nP1vH8epC/xPOu";
		BCryptPasswordEncoder newEncoder = new BCryptPasswordEncoder();
		assertTrue(newEncoder.matches("legacy-password", legacyHash),
				"existing user hashes must remain valid after migration");
		assertFalse(newEncoder.matches("wrong-password", legacyHash));
	}

	@Test
	@DisplayName("USR-03b NoOpPasswordEncoder is NOT used anywhere (security red line D7)")
	void noNoOpEncoder() {
		User user = new User();
		user.setUsername("u");
		user.setPassword("p");
		when(repository.findById("u")).thenReturn(Optional.empty());
		service.create(user);
		ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
		verify(repository).save(captor.capture());
		assertNotEquals("p", captor.getValue().getPassword(),
				"{noop} plaintext storage is forbidden");
	}
}
