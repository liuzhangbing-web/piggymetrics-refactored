package com.piggymetrics.notification.service;

import com.piggymetrics.notification.domain.Frequency;
import com.piggymetrics.notification.domain.NotificationSettings;
import com.piggymetrics.notification.domain.NotificationType;
import com.piggymetrics.notification.domain.Recipient;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Cases EML-01..03: EmailServiceImpl (jakarta.mail migration + env-driven templates). */
@ExtendWith(MockitoExtension.class)
class EmailServiceTest {

	@Mock
	private JavaMailSender mailSender;

	@InjectMocks
	private EmailServiceImpl emailService;

	private MimeMessage mimeMessage;

	@BeforeEach
	void setUp() {
		MockEnvironment env = new MockEnvironment();
		env.setProperty("remind.email.subject", "PiggyMetrics reminder");
		env.setProperty("remind.email.text", "Hey, {0}! Time to check your budget.");
		env.setProperty("backup.email.subject", "PiggyMetrics account backup");
		env.setProperty("backup.email.text", "Howdy, {0}. Your account backup is ready.");
		env.setProperty("backup.email.attachment", "backup.json");
		ReflectionTestUtils.setField(emailService, "env", env);

		mimeMessage = new MimeMessage((Session) null);
		when(mailSender.createMimeMessage()).thenReturn(mimeMessage);
	}

	private Recipient recipient() {
		Recipient r = new Recipient();
		r.setAccountName("test-user");
		r.setEmail("test@example.com");
		Map<NotificationType, NotificationSettings> settings = new HashMap<>();
		NotificationSettings s = new NotificationSettings();
		s.setActive(true);
		s.setFrequency(Frequency.WEEKLY);
		settings.put(NotificationType.REMIND, s);
		r.setScheduledNotifications(settings);
		return r;
	}

	/**
	 * Extracts the text content from a (possibly multipart) MimeMessage.
	 * saveChanges() is required first: the message was built by MimeMessageHelper on a
	 * session-less MimeMessage, so headers (Content-Type) are only materialized on save.
	 */
	private String textOf(MimeMessage message) throws Exception {
		message.saveChanges();
		Object content = message.getContent();
		if (content instanceof String s) {
			return s;
		}
		return collectText(content);
	}

	/** Recursively collects all text/* content from nested multipart structures. */
	private String collectText(Object content) throws Exception {
		if (content instanceof String s) {
			return s;
		}
		if (content instanceof jakarta.mail.Multipart multipart) {
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < multipart.getCount(); i++) {
				jakarta.mail.BodyPart part = multipart.getBodyPart(i);
				Object pc = part.getContent();
				if (part.isMimeType("text/*") && pc instanceof String s) {
					sb.append(s);
				}
				else if (pc instanceof jakarta.mail.Multipart) {
					sb.append(collectText(pc));
				}
			}
			return sb.toString();
		}
		return String.valueOf(content);
	}

	@Test
	@DisplayName("EML-01 REMIND: subject/text from env, MessageFormat fills accountName")
	void sendRemind() throws Exception {
		emailService.send(NotificationType.REMIND, recipient(), null);

		ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
		verify(mailSender).send(captor.capture());
		MimeMessage sent = captor.getValue();
		assertEquals("PiggyMetrics reminder", sent.getSubject());
		assertEquals("test@example.com", sent.getAllRecipients()[0].toString());
		String body = textOf(sent);
		assertTrue(body.contains("Hey, test-user!"),
				"MessageFormat must fill accountName, body was: " + body);
	}

	@Test
	@DisplayName("EML-02 BACKUP with attachment: multipart, attachment name from env")
	void sendBackupWithAttachment() throws Exception {
		emailService.send(NotificationType.BACKUP, recipient(), "{\"name\":\"test-user\"}");

		verify(mailSender).send(any(MimeMessage.class));
		mimeMessage.saveChanges();
		assertTrue(mimeMessage.isMimeType("multipart/mixed"),
				"attachment mail must be multipart/mixed, was: " + mimeMessage.getContentType());
		String body = textOf(mimeMessage);
		assertTrue(body.contains("Howdy, test-user."), "backup text must contain accountName");
	}

	@Test
	@DisplayName("EML-03 mail send failure propagates (jakarta.mail migration check)")
	void mailSendFailurePropagates() {
		// JavaMailSender.send() throws MailException (unchecked); the service signature
		// still declares MessagingException (jakarta) from MimeMessageHelper usage.
		doThrow(new org.springframework.mail.MailSendException("smtp failure"))
				.when(mailSender).send(any(MimeMessage.class));
		org.springframework.mail.MailException ex = assertThrows(
				org.springframework.mail.MailException.class,
				() -> emailService.send(NotificationType.REMIND, recipient(), null));
		assertTrue(String.valueOf(ex.getMessage()).contains("smtp failure"));
	}
}
