package com.piggymetrics.notification.service;

import com.piggymetrics.notification.client.AccountServiceClient;
import com.piggymetrics.notification.domain.Frequency;
import com.piggymetrics.notification.domain.NotificationSettings;
import com.piggymetrics.notification.domain.NotificationType;
import com.piggymetrics.notification.domain.Recipient;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Cases NOT-01..05: scheduled notification behavior + M7 ShedLock verification. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NotificationServiceTest {

	@Mock
	private AccountServiceClient client;

	@Mock
	private RecipientService recipientService;

	@Mock
	private EmailService emailService;

	@InjectMocks
	private NotificationServiceImpl service;

	private Recipient recipient(String name) {
		Recipient r = new Recipient();
		r.setAccountName(name);
		r.setEmail(name + "@example.com");
		Map<NotificationType, NotificationSettings> settings = new HashMap<>();
		for (NotificationType t : NotificationType.values()) {
			NotificationSettings s = new NotificationSettings();
			s.setActive(true);
			s.setFrequency(Frequency.WEEKLY);
			settings.put(t, s);
		}
		r.setScheduledNotifications(settings);
		return r;
	}

	@Test
	@DisplayName("NOT-01 remind: each recipient gets one email + markNotified (async completion awaited)")
	void remindNotifications() throws Exception {
		Recipient r1 = recipient("user1");
		Recipient r2 = recipient("user2");
		when(recipientService.findReadyToNotify(NotificationType.REMIND)).thenReturn(List.of(r1, r2));

		service.sendRemindNotifications();

		await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
			verify(emailService, times(2)).send(eq(NotificationType.REMIND), any(Recipient.class), isNull());
			verify(recipientService, times(2)).markNotified(eq(NotificationType.REMIND), any(Recipient.class));
		});
	}

	@Test
	@DisplayName("NOT-02 one recipient failure does not affect others (legacy swallow semantics)")
	void partialFailureIsolated() throws Exception {
		Recipient r1 = recipient("user1");
		Recipient r2 = recipient("user2");
		when(recipientService.findReadyToNotify(NotificationType.REMIND)).thenReturn(List.of(r1, r2));
		doThrow(new RuntimeException("smtp down"))
				.when(emailService).send(eq(NotificationType.REMIND), eq(r1), isNull());

		service.sendRemindNotifications();

		await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
				verify(emailService, times(2)).send(eq(NotificationType.REMIND), any(Recipient.class), isNull()));
		verify(recipientService, times(1)).markNotified(NotificationType.REMIND, r2);
		verify(recipientService, never()).markNotified(NotificationType.REMIND, r1);
	}

	@Test
	@DisplayName("NOT-03 backup: attachment fetched from account-service and passed to email")
	void backupWithAttachment() throws Exception {
		Recipient r1 = recipient("user1");
		when(recipientService.findReadyToNotify(NotificationType.BACKUP)).thenReturn(List.of(r1));
		when(client.getAccount("user1")).thenReturn("{\"name\":\"user1\"}");

		service.sendBackupNotifications();

		await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
				verify(emailService).send(eq(NotificationType.BACKUP), eq(r1), eq("{\"name\":\"user1\"}")));
	}

	@Test
	@DisplayName("NOT-04 both scheduled methods carry @SchedulerLock with unique names (M7)")
	void schedulerLockPresent() throws Exception {
		Method backup = NotificationServiceImpl.class.getMethod("sendBackupNotifications");
		Method remind = NotificationServiceImpl.class.getMethod("sendRemindNotifications");

		SchedulerLock lockB = backup.getAnnotation(SchedulerLock.class);
		SchedulerLock lockR = remind.getAnnotation(SchedulerLock.class);
		assertNotNull(lockB, "sendBackupNotifications must be lock-protected");
		assertNotNull(lockR, "sendRemindNotifications must be lock-protected");
		assertNotEquals(lockB.name(), lockR.name(), "lock names must be unique");
		assertNotNull(backup.getAnnotation(Scheduled.class), "cron schedule must remain");
		assertNotNull(remind.getAnnotation(Scheduled.class));
		assertEquals("${backup.cron}", backup.getAnnotation(Scheduled.class).cron(),
				"cron expression binding must stay config-driven (business timing unchanged)");
		assertEquals("${remind.cron}", remind.getAnnotation(Scheduled.class).cron());
	}
}
