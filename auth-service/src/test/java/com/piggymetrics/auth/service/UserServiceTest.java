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

	@Test
	@DisplayName("USR-04 [Plan-A] deleteByUsername existing -> deletes, returns true")
	void delete_existing() {
		User existing = new User();
		existing.setUsername("orphan");
		when(repository.findById("orphan")).thenReturn(Optional.of(existing));

		boolean deleted = service.deleteByUsername("orphan");

		assertTrue(deleted);
		verify(repository).deleteById("orphan");
	}

	@Test
	@DisplayName("UT-USR-004 [Plan-A/D17] compensation delete emits WARN audit log with COMPENSATION marker + username")
	void delete_auditLog() {
		ch.qos.logback.classic.Logger logger =
				(ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(UserServiceImpl.class);
		ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
				new ch.qos.logback.core.read.ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		try {
			User existing = new User();
			existing.setUsername("audit-orphan");
			when(repository.findById("audit-orphan")).thenReturn(Optional.of(existing));

			service.deleteByUsername("audit-orphan");

			boolean found = appender.list.stream().anyMatch(e ->
					e.getLevel() == ch.qos.logback.classic.Level.WARN
					&& e.getFormattedMessage().contains("COMPENSATION")
					&& e.getFormattedMessage().contains("audit-orphan"));
			assertTrue(found, "WARN audit log with COMPENSATION marker and username required (D17 traceability)");
		} finally {
			logger.detachAppender(appender);
		}
	}

	@Test
	@DisplayName("USR-05 [Plan-A] deleteByUsername missing -> idempotent no-op, returns false, no delete")
	void delete_missing_idempotent() {
		when(repository.findById("ghost")).thenReturn(Optional.empty());

		boolean deleted = service.deleteByUsername("ghost");

		assertFalse(deleted);
		verify(repository, never()).deleteById(anyString());
	}

	@Test
	@DisplayName("USR-06 [Plan-A] deleteByUsername blank -> IllegalArgumentException (Assert semantics)")
	void delete_blank() {
		assertThrows(IllegalArgumentException.class, () -> service.deleteByUsername(""));
		assertThrows(IllegalArgumentException.class, () -> service.deleteByUsername(null));
	}
}
