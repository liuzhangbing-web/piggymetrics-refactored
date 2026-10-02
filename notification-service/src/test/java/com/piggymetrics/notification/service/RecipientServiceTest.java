package com.piggymetrics.notification.service;

import com.piggymetrics.notification.domain.Frequency;
import com.piggymetrics.notification.domain.NotificationSettings;
import com.piggymetrics.notification.domain.NotificationType;
import com.piggymetrics.notification.domain.Recipient;
import com.piggymetrics.notification.repository.RecipientRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Cases REC-01..04: RecipientServiceImpl legacy semantics. */
@ExtendWith(MockitoExtension.class)
class RecipientServiceTest {

	@Mock
	private RecipientRepository repository;

	@InjectMocks
	private RecipientServiceImpl service;

	private Recipient recipient() {
		Recipient r = new Recipient();
		r.setEmail("test@example.com");
		Map<NotificationType, NotificationSettings> settings = new HashMap<>();
		NotificationSettings s = new NotificationSettings();
		s.setActive(true);
		s.setFrequency(Frequency.WEEKLY);
		settings.put(NotificationType.BACKUP, s);
		r.setScheduledNotifications(settings);
		return r;
	}

	@Test
	@DisplayName("REC-01 save forces accountName from path (prevents tampering)")
	void save_overridesAccountName() {
		Recipient r = recipient();
		r.setAccountName("attacker-value");

		service.save("real-owner", r);

		ArgumentCaptor<Recipient> captor = ArgumentCaptor.forClass(Recipient.class);
		verify(repository).save(captor.capture());
		assertEquals("real-owner", captor.getValue().getAccountName());
	}

	@Test
	@DisplayName("REC-02 save initializes null lastNotified, keeps non-null")
	void save_lastNotifiedInit() {
		Recipient r = recipient();
		Date preset = new Date(1000000L);
		NotificationSettings remind = new NotificationSettings();
		remind.setActive(true);
		remind.setFrequency(Frequency.MONTHLY);
		remind.setLastNotified(preset);
		r.getScheduledNotifications().put(NotificationType.REMIND, remind);
		// BACKUP lastNotified is null

		service.save("owner", r);

		assertNotNull(r.getScheduledNotifications().get(NotificationType.BACKUP).getLastNotified(),
				"null lastNotified must be initialized");
		assertEquals(preset, r.getScheduledNotifications().get(NotificationType.REMIND).getLastNotified(),
				"non-null lastNotified must be preserved");
	}

	@Test
	@DisplayName("REC-03 findReadyToNotify dispatches per type")
	void findReadyToNotify() {
		service.findReadyToNotify(NotificationType.BACKUP);
		verify(repository).findReadyForBackup();
		service.findReadyToNotify(NotificationType.REMIND);
		verify(repository).findReadyForRemind();
	}

	@Test
	@DisplayName("REC-03b findByAccountName blank -> IllegalArgumentException")
	void findByAccountName_blank() {
		assertThrows(IllegalArgumentException.class, () -> service.findByAccountName(""));
	}

	@Test
	@DisplayName("REC-04 markNotified updates lastNotified and saves")
	void markNotified() {
		Recipient r = recipient();
		service.markNotified(NotificationType.BACKUP, r);
		assertNotNull(r.getScheduledNotifications().get(NotificationType.BACKUP).getLastNotified());
		verify(repository).save(r);
	}
}
