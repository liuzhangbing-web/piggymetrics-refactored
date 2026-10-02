package com.piggymetrics.account.service;

import com.piggymetrics.account.client.AuthServiceClient;
import com.piggymetrics.account.client.StatisticsServiceClient;
import com.piggymetrics.account.domain.Account;
import com.piggymetrics.account.domain.Currency;
import com.piggymetrics.account.domain.Item;
import com.piggymetrics.account.domain.Saving;
import com.piggymetrics.account.domain.User;
import com.piggymetrics.account.repository.AccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Stage-4 unit tests for AccountServiceImpl (cases ACC-01 .. ACC-08).
 * RED-LINE: these tests LOCK the legacy business behavior (call order, default saving,
 * error semantics). They must never be "fixed" by changing the service - see H1/H2.
 */
@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

	@Mock
	private AccountRepository repository;

	@Mock
	private AuthServiceClient authClient;

	@Mock
	private StatisticsServiceClient statisticsClient;

	@InjectMocks
	private AccountServiceImpl service;

	private User user;

	@BeforeEach
	void setUp() {
		user = new User();
		user.setUsername("test-user");
		user.setPassword("secret1");
	}

	@Test
	@DisplayName("ACC-01 findByName delegates to repository")
	void findByName_ok() {
		Account expected = new Account();
		when(repository.findByName("test-user")).thenReturn(expected);
		assertSame(expected, service.findByName("test-user"));
		verify(repository, times(1)).findByName("test-user");
	}

	@Test
	@DisplayName("ACC-02 findByName rejects blank/null (legacy Assert semantics)")
	void findByName_blank() {
		assertThrows(IllegalArgumentException.class, () -> service.findByName(""));
		assertThrows(IllegalArgumentException.class, () -> service.findByName(null));
	}

	@Test
	@DisplayName("ACC-03 [RED] create: order authClient.createUser -> repository.save, default saving")
	void create_ok() {
		when(repository.findByName("test-user")).thenReturn(null);

		Account created = service.create(user);

		// strict call order (H1 chain, must not change)
		InOrder inOrder = inOrder(authClient, repository);
		inOrder.verify(repository).findByName("test-user");
		inOrder.verify(authClient).createUser(user);
		inOrder.verify(repository).save(any(Account.class));

		assertEquals("test-user", created.getName());
		assertNotNull(created.getLastSeen());
		Saving saving = created.getSaving();
		assertNotNull(saving);
		assertEquals(0, saving.getAmount().compareTo(BigDecimal.ZERO));
		assertEquals(Currency.getDefault(), saving.getCurrency());
		assertEquals(Currency.USD, saving.getCurrency());
		assertEquals(0, saving.getInterest().compareTo(BigDecimal.ZERO));
		assertFalse(saving.getDeposit());
		assertFalse(saving.getCapitalization());
	}

	@Test
	@DisplayName("ACC-04 [RED] create: existing account -> IllegalArgumentException, no side effects")
	void create_existing() {
		when(repository.findByName("test-user")).thenReturn(new Account());

		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> service.create(user));
		assertTrue(ex.getMessage().contains("account already exists"));
		verifyNoInteractions(authClient);
		verify(repository, never()).save(any());
	}

	@Test
	@DisplayName("ACC-05 [RED] create: auth failure -> account NOT saved, exception propagates (locks known H1 gap)")
	void create_authFailure() {
		when(repository.findByName("test-user")).thenReturn(null);
		doThrow(new RuntimeException("auth down")).when(authClient).createUser(user);

		assertThrows(RuntimeException.class, () -> service.create(user));
		verify(repository, never()).save(any());
	}

	@Test
	@DisplayName("ACC-06 [RED] saveChanges: overwrite fields + statisticsClient called exactly once with saved account")
	void saveChanges_ok() {
		Account existing = new Account();
		existing.setName("test-user");
		when(repository.findByName("test-user")).thenReturn(existing);

		Account update = new Account();
		Item income = new Item();
		income.setTitle("salary");
		income.setAmount(new BigDecimal("1000"));
		income.setCurrency(Currency.USD);
		update.setIncomes(List.of(income));
		update.setExpenses(Collections.emptyList());
		Saving saving = new Saving();
		saving.setAmount(new BigDecimal("100"));
		saving.setCurrency(Currency.USD);
		saving.setInterest(new BigDecimal("0.05"));
		saving.setDeposit(true);
		saving.setCapitalization(false);
		update.setSaving(saving);
		update.setNote("note");

		service.saveChanges("test-user", update);

		assertSame(update.getIncomes(), existing.getIncomes());
		assertSame(update.getExpenses(), existing.getExpenses());
		assertSame(update.getSaving(), existing.getSaving());
		assertEquals("note", existing.getNote());
		assertNotNull(existing.getLastSeen());
		verify(repository).save(existing);
		verify(statisticsClient, times(1)).updateStatistics(eq("test-user"), same(existing));
	}

	@Test
	@DisplayName("ACC-07 [RED] saveChanges: unknown account -> IllegalArgumentException, statistics untouched")
	void saveChanges_notFound() {
		when(repository.findByName("missing")).thenReturn(null);
		IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
				() -> service.saveChanges("missing", new Account()));
		assertTrue(ex.getMessage().contains("can't find account with name"));
		verifyNoInteractions(statisticsClient);
	}

	@Test
	@DisplayName("ACC-08 saveChanges: statistics failure swallowed by fallback semantics (H4 status quo)")
	void saveChanges_statisticsFailure() {
		Account existing = new Account();
		existing.setName("test-user");
		when(repository.findByName("test-user")).thenReturn(existing);
		doThrow(new RuntimeException("statistics down"))
				.when(statisticsClient).updateStatistics(anyString(), any());

		// NOTE: without Hystrix/Sentinel active in unit context the raw Feign exception
		// propagates; the FALLBACK behavior itself is covered by StatisticsServiceClientFallbackTest.
		assertThrows(RuntimeException.class, () -> service.saveChanges("test-user", new Account()));
		verify(repository).save(existing);
	}

	@Test
	@DisplayName("ACC-08b fallback logs and swallows (legacy StatisticsServiceClientFallback semantics)")
	void fallback_semantics() {
		com.piggymetrics.account.client.StatisticsServiceClientFallback fallback =
				new com.piggymetrics.account.client.StatisticsServiceClientFallback();
		assertDoesNotThrow(() -> fallback.updateStatistics("test-user", new Account()));
	}
}
