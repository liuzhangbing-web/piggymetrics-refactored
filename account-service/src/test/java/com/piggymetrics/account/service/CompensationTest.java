package com.piggymetrics.account.service;

import com.piggymetrics.account.client.AuthServiceClient;
import com.piggymetrics.account.client.StatisticsServiceClient;
import com.piggymetrics.account.domain.Account;
import com.piggymetrics.account.domain.CompensationTask;
import com.piggymetrics.account.domain.User;
import com.piggymetrics.account.repository.AccountRepository;
import com.piggymetrics.account.repository.CompensationTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Plan-A (idempotent compensation + reconciliation self-healing) unit tests.
 * COMP-01..COMP-09. These lock the NEW failure-path behavior while the existing
 * AccountServiceTest (ACC-01..08) locks the unchanged success-path red-line semantics.
 */
@ExtendWith(MockitoExtension.class)
class CompensationTest {

	@Mock
	private AccountRepository repository;
	@Mock
	private AuthServiceClient authClient;
	@Mock
	private StatisticsServiceClient statisticsClient;
	@Mock
	private CompensationTaskRepository taskRepository;

	private AccountServiceImpl service;

	private User user;

	@BeforeEach
	void setUp() {
		service = new AccountServiceImpl();
		ReflectionTestUtils.setField(service, "repository", repository);
		ReflectionTestUtils.setField(service, "authClient", authClient);
		ReflectionTestUtils.setField(service, "statisticsClient", statisticsClient);
		ReflectionTestUtils.setField(service, "compensationTaskRepository", taskRepository);
		user = new User();
		user.setUsername("comp-user");
		user.setPassword("secret1");
	}

	@Test
	@DisplayName("COMP-01 [RED-adjacent] create: local save fails -> immediate compensation deleteUser + original exception propagates")
	void create_saveFails_compensatesImmediately() {
		when(repository.findByName("comp-user")).thenReturn(null);
		doThrow(new RuntimeException("mongo down")).when(repository).save(any(Account.class));

		RuntimeException ex = assertThrows(RuntimeException.class, () -> service.create(user));
		assertEquals("mongo down", ex.getMessage(), "original failure must propagate (legacy semantics)");

		// compensation invoked exactly once, and the account was NOT persisted
		verify(authClient, times(1)).createUser(user);
		verify(authClient, times(1)).deleteUser("comp-user");
		// immediate compensation succeeded -> no async task needed
		verify(taskRepository, never()).save(any());
	}

	@Test
	@DisplayName("COMP-02 create: save fails AND compensation delete fails -> async DELETE_AUTH_USER task enqueued")
	void create_saveFails_compensationFails_enqueuesTask() {
		when(repository.findByName("comp-user")).thenReturn(null);
		doThrow(new RuntimeException("mongo down")).when(repository).save(any(Account.class));
		doThrow(new RuntimeException("auth unreachable")).when(authClient).deleteUser("comp-user");
		when(taskRepository.findByTypeAndAccountNameAndStatus(
				CompensationTask.Type.DELETE_AUTH_USER, "comp-user", CompensationTask.Status.PENDING))
				.thenReturn(List.of());

		assertThrows(RuntimeException.class, () -> service.create(user));

		ArgumentCaptor<CompensationTask> captor = ArgumentCaptor.forClass(CompensationTask.class);
		verify(taskRepository).save(captor.capture());
		CompensationTask task = captor.getValue();
		assertEquals(CompensationTask.Type.DELETE_AUTH_USER, task.getType());
		assertEquals("comp-user", task.getAccountName());
		assertEquals(CompensationTask.Status.PENDING, task.getStatus());
	}

	@Test
	@DisplayName("COMP-03 create compensation enqueue is idempotent (existing PENDING task -> no duplicate)")
	void create_compensationEnqueue_idempotent() {
		when(repository.findByName("comp-user")).thenReturn(null);
		doThrow(new RuntimeException("mongo down")).when(repository).save(any(Account.class));
		doThrow(new RuntimeException("auth unreachable")).when(authClient).deleteUser("comp-user");
		CompensationTask existing = new CompensationTask(CompensationTask.Type.DELETE_AUTH_USER, "comp-user");
		when(taskRepository.findByTypeAndAccountNameAndStatus(
				CompensationTask.Type.DELETE_AUTH_USER, "comp-user", CompensationTask.Status.PENDING))
				.thenReturn(List.of(existing));

		assertThrows(RuntimeException.class, () -> service.create(user));
		verify(taskRepository, never()).save(any());
	}

	@Test
	@DisplayName("COMP-04 [RED] create success path: NO compensation, NO deleteUser, NO task (legacy behavior intact)")
	void create_success_noCompensation() {
		when(repository.findByName("comp-user")).thenReturn(null);

		Account created = service.create(user);

		assertNotNull(created);
		verify(authClient, times(1)).createUser(user);
		verify(authClient, never()).deleteUser(anyString());
		verify(repository, times(1)).save(any(Account.class));
		verifyNoInteractions(taskRepository);
	}

	@Test
	@DisplayName("COMP-05 saveChanges raw statistics failure -> UPDATE_STATISTICS task enqueued + exception propagates")
	void saveChanges_statisticsFails_enqueuesTask() {
		Account existing = new Account();
		existing.setName("comp-user");
		when(repository.findByName("comp-user")).thenReturn(existing);
		doThrow(new RuntimeException("statistics down"))
				.when(statisticsClient).updateStatistics(anyString(), any());
		when(taskRepository.findByTypeAndAccountNameAndStatus(
				CompensationTask.Type.UPDATE_STATISTICS, "comp-user", CompensationTask.Status.PENDING))
				.thenReturn(List.of());

		assertThrows(RuntimeException.class, () -> service.saveChanges("comp-user", new Account()));

		ArgumentCaptor<CompensationTask> captor = ArgumentCaptor.forClass(CompensationTask.class);
		verify(taskRepository).save(captor.capture());
		assertEquals(CompensationTask.Type.UPDATE_STATISTICS, captor.getValue().getType());
		verify(repository).save(existing); // account itself was persisted (source of truth intact)
	}

	@Test
	@DisplayName("COMP-06 saveChanges success path: NO task enqueued (legacy behavior intact)")
	void saveChanges_success_noTask() {
		Account existing = new Account();
		existing.setName("comp-user");
		when(repository.findByName("comp-user")).thenReturn(existing);

		service.saveChanges("comp-user", new Account());

		verify(statisticsClient, times(1)).updateStatistics(eq("comp-user"), same(existing));
		verifyNoInteractions(taskRepository);
	}

	// ---------- reconciliation job ----------

	@Test
	@DisplayName("COMP-07 reconcile: DELETE_AUTH_USER task success -> DONE")
	void reconcile_deleteTask_success() {
		CompensationReconciliationJob job = job();
		CompensationTask task = new CompensationTask(CompensationTask.Type.DELETE_AUTH_USER, "orphan");
		task.setId("t1");
		when(taskRepository.findByStatus(CompensationTask.Status.PENDING)).thenReturn(List.of(task));
		when(authClient.deleteUser("orphan")).thenReturn(true);

		job.reconcile();

		assertEquals(CompensationTask.Status.DONE, task.getStatus());
		assertEquals(1, task.getAttempts());
		verify(taskRepository).save(task);
	}

	@Test
	@DisplayName("COMP-08 reconcile: UPDATE_STATISTICS recomputes from account (source of truth) -> DONE")
	void reconcile_statisticsTask_recomputes() {
		CompensationReconciliationJob job = job();
		CompensationTask task = new CompensationTask(CompensationTask.Type.UPDATE_STATISTICS, "comp-user");
		task.setId("t2");
		when(taskRepository.findByStatus(CompensationTask.Status.PENDING)).thenReturn(List.of(task));
		Account account = new Account();
		account.setName("comp-user");
		when(repository.findByName("comp-user")).thenReturn(account);

		job.reconcile();

		verify(compClient).updateStatistics("comp-user", account);
		assertEquals(CompensationTask.Status.DONE, task.getStatus());
	}

	@Test
	@DisplayName("COMP-09 reconcile: repeated failure exhausts maxAttempts -> FAILED (alert), stays out of retry loop")
	void reconcile_exhaustsAttempts_failed() {
		CompensationReconciliationJob job = job();
		CompensationTask task = new CompensationTask(CompensationTask.Type.DELETE_AUTH_USER, "orphan");
		task.setId("t3");
		task.setAttempts(4); // maxAttempts default 5 -> this attempt exhausts
		when(taskRepository.findByStatus(CompensationTask.Status.PENDING)).thenReturn(List.of(task));
		doThrow(new RuntimeException("auth still down")).when(authClient).deleteUser("orphan");

		job.reconcile();

		assertEquals(CompensationTask.Status.FAILED, task.getStatus());
		assertEquals(5, task.getAttempts());
		assertNotNull(task.getLastError());
		verify(taskRepository).save(task);
	}

	// helper: build a job with mocks injected (statisticsCompensationClient = no-fallback client)
	private com.piggymetrics.account.client.StatisticsCompensationClient compClient;

	private CompensationReconciliationJob job() {
		CompensationReconciliationJob job = new CompensationReconciliationJob();
		compClient = mock(com.piggymetrics.account.client.StatisticsCompensationClient.class);
		ReflectionTestUtils.setField(job, "taskRepository", taskRepository);
		ReflectionTestUtils.setField(job, "accountRepository", repository);
		ReflectionTestUtils.setField(job, "authClient", authClient);
		ReflectionTestUtils.setField(job, "statisticsCompensationClient", compClient);
		ReflectionTestUtils.setField(job, "maxAttempts", 5);
		return job;
	}

	// ================= dp-spec UT-CMP-004..009 (reconciliation job state machine) =================

	@Test
	@DisplayName("UT-CMP-004 reconcile: account gone -> UPDATE_STATISTICS task closed DONE, client untouched (no futile retry)")
	void reconcile_accountMissing_done() {
		CompensationReconciliationJob job = job();
		CompensationTask task = new CompensationTask(CompensationTask.Type.UPDATE_STATISTICS, "vanished");
		task.setId("t4");
		when(taskRepository.findByStatus(CompensationTask.Status.PENDING)).thenReturn(List.of(task));
		when(repository.findByName("vanished")).thenReturn(null);

		job.reconcile();

		verifyNoInteractions(compClient);
		assertEquals(CompensationTask.Status.DONE, task.getStatus());
	}

	@Test
	@DisplayName("UT-CMP-005 reconcile: single failure below max -> stays PENDING, attempts+1, lastError recorded")
	void reconcile_singleFailure_staysPending() {
		CompensationReconciliationJob job = job();
		CompensationTask task = new CompensationTask(CompensationTask.Type.DELETE_AUTH_USER, "orphan");
		task.setId("t5");
		task.setAttempts(1);
		when(taskRepository.findByStatus(CompensationTask.Status.PENDING)).thenReturn(List.of(task));
		doThrow(new RuntimeException("transient")).when(authClient).deleteUser("orphan");

		job.reconcile();

		assertEquals(CompensationTask.Status.PENDING, task.getStatus());
		assertEquals(2, task.getAttempts());
		assertNotNull(task.getLastError());
		assertTrue(task.getLastError().contains("transient"));
		verify(taskRepository).save(task);
	}

	@Test
	@DisplayName("UT-CMP-006 reconcile: task load failure (mongo blip) -> job survives, no client calls")
	void reconcile_loadFailure_survives() {
		CompensationReconciliationJob job = job();
		when(taskRepository.findByStatus(CompensationTask.Status.PENDING))
				.thenThrow(new RuntimeException("mongo blip"));

		assertDoesNotThrow(job::reconcile);
		verifyNoInteractions(authClient);
		verifyNoInteractions(compClient);
	}

	@Test
	@DisplayName("UT-CMP-007 reconcile: empty pending list -> silent return, zero client interactions")
	void reconcile_empty_silent() {
		CompensationReconciliationJob job = job();
		when(taskRepository.findByStatus(CompensationTask.Status.PENDING)).thenReturn(List.of());

		job.reconcile();

		verifyNoInteractions(authClient);
		verifyNoInteractions(compClient);
		verify(taskRepository, never()).save(any());
	}

	@Test
	@DisplayName("UT-CMP-008 [RED] no-fallback client guard: statistics still down -> task MUST stay PENDING (never false DONE)")
	void reconcile_noFallbackClient_staysPending() {
		// Design lock: the job uses StatisticsCompensationClient (no Sentinel fallback).
		// If someone swaps in the primary fallback-equipped client, a still-down statistics
		// service would silently 'succeed' and the task would be wrongly marked DONE.
		CompensationReconciliationJob job = job();
		CompensationTask task = new CompensationTask(CompensationTask.Type.UPDATE_STATISTICS, "comp-user");
		task.setId("t8");
		when(taskRepository.findByStatus(CompensationTask.Status.PENDING)).thenReturn(List.of(task));
		Account account = new Account();
		account.setName("comp-user");
		when(repository.findByName("comp-user")).thenReturn(account);
		doThrow(new RuntimeException("statistics still down"))
				.when(compClient).updateStatistics("comp-user", account);

		job.reconcile();

		assertEquals(CompensationTask.Status.PENDING, task.getStatus(),
				"a swallowed failure (fallback) would flip this to DONE - guard violated");
		assertEquals(1, task.getAttempts());
		assertNotNull(task.getLastError());
	}

	@Test
	@DisplayName("UT-CMP-009 reconcile: null/unknown task type -> treated as retryable failure, FAILED after max attempts")
	void reconcile_nullType_failsAfterMax() {
		// Enum type cannot hold an 'unknown' literal; a null type (corrupt document) hits
		// the NPE path -> handleAttemptFailure. With attempts already at max-1 -> FAILED.
		CompensationReconciliationJob job = job();
		CompensationTask task = new CompensationTask();
		task.setId("t9");
		task.setAccountName("corrupt");
		task.setStatus(CompensationTask.Status.PENDING);
		task.setAttempts(4);
		when(taskRepository.findByStatus(CompensationTask.Status.PENDING)).thenReturn(List.of(task));

		job.reconcile();

		assertEquals(CompensationTask.Status.FAILED, task.getStatus());
		assertNotNull(task.getLastError());
		verify(taskRepository).save(task);
	}

	// ================= dp-spec UT-ACC-013/014 + CMP-B07/B08 (null-safety & best-effort) =================

	@Test
	@DisplayName("UT-ACC-013 taskRepository not wired (null): save-failure compensation must not NPE, deleteUser still attempted")
	void create_nullTaskRepo_noNpe() {
		AccountServiceImpl bare = new AccountServiceImpl();
		ReflectionTestUtils.setField(bare, "repository", repository);
		ReflectionTestUtils.setField(bare, "authClient", authClient);
		ReflectionTestUtils.setField(bare, "statisticsClient", statisticsClient);
		// compensationTaskRepository deliberately NOT injected (null)
		when(repository.findByName("comp-user")).thenReturn(null);
		doThrow(new RuntimeException("mongo down")).when(repository).save(any(Account.class));
		doThrow(new RuntimeException("auth unreachable")).when(authClient).deleteUser("comp-user");

		RuntimeException ex = assertThrows(RuntimeException.class, () -> bare.create(user));
		assertEquals("mongo down", ex.getMessage(), "original failure propagates, no NPE masking");
		verify(authClient).deleteUser("comp-user");
	}

	@Test
	@DisplayName("UT-ACC-014 enqueue save itself throws -> original exception still propagates unchanged")
	void create_enqueueFails_originalPropagates() {
		when(repository.findByName("comp-user")).thenReturn(null);
		doThrow(new RuntimeException("mongo down")).when(repository).save(any(Account.class));
		doThrow(new RuntimeException("auth unreachable")).when(authClient).deleteUser("comp-user");
		when(taskRepository.findByTypeAndAccountNameAndStatus(any(), anyString(), any()))
				.thenReturn(List.of());
		when(taskRepository.save(any())).thenThrow(new RuntimeException("task store down"));

		RuntimeException ex = assertThrows(RuntimeException.class, () -> service.create(user));
		assertEquals("mongo down", ex.getMessage(), "enqueue failure must never mask the original");
	}

	@Test
	@DisplayName("CMP-B07 fallback enqueue is best-effort: task-store failure never escapes updateStatistics")
	void fallback_enqueueThrows_neverEscapes() {
		com.piggymetrics.account.client.StatisticsServiceClientFallback fallback =
				new com.piggymetrics.account.client.StatisticsServiceClientFallback();
		CompensationTaskRepository brokenRepo = mock(CompensationTaskRepository.class);
		when(brokenRepo.findByTypeAndAccountNameAndStatus(any(), anyString(), any()))
				.thenThrow(new RuntimeException("task store down"));
		ReflectionTestUtils.setField(fallback, "compensationTaskRepository", brokenRepo);

		assertDoesNotThrow(() -> fallback.updateStatistics("comp-user", new Account()));
	}

	@Test
	@DisplayName("CMP-B08 fallback without wired repository -> legacy log-only behavior, no NPE")
	void fallback_nullRepo_legacyBehavior() {
		com.piggymetrics.account.client.StatisticsServiceClientFallback fallback =
				new com.piggymetrics.account.client.StatisticsServiceClientFallback();
		// compensationTaskRepository stays null (plain new, no Spring context)
		assertDoesNotThrow(() -> fallback.updateStatistics("comp-user", new Account()));
	}
}
