package com.piggymetrics.notification;

import com.piggymetrics.notification.repository.converter.FrequencyReaderConverter;
import com.piggymetrics.notification.repository.converter.FrequencyWriterConverter;
import com.mongodb.client.MongoClient;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.mongo.MongoLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

import java.util.Arrays;

@SpringBootApplication
@EnableDiscoveryClient
@EnableFeignClients
@EnableMethodSecurity
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M")
public class NotificationServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(NotificationServiceApplication.class, args);
	}

	@Bean
	public LockProvider lockProvider(MongoClient mongoClient) {
		// lock collection lives in the service's own database ("piggymetrics")
		return new MongoLockProvider(mongoClient.getDatabase("piggymetrics").getCollection("shedlock"));
	}

	@Configuration
	static class CustomConversionsConfig {

		@Bean
		public MongoCustomConversions customConversions() {
			return new MongoCustomConversions(Arrays.asList(new FrequencyReaderConverter(),
					new FrequencyWriterConverter()));
		}
	}
}
