package com.piggymetrics.statistics.repository;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.piggymetrics.statistics.domain.Currency;
import com.piggymetrics.statistics.domain.timeseries.DataPoint;
import com.piggymetrics.statistics.domain.timeseries.DataPointId;
import com.piggymetrics.statistics.domain.timeseries.ItemMetric;
import com.piggymetrics.statistics.domain.timeseries.StatisticMetric;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Case CVT-03 [RED]: REAL MongoDB round-trip of DataPoint with the composite
 * DataPointId, executed against the live mongo instance provided via TEST_MONGO_URI
 * (no mocks, no embedded db). Also verifies the raw persisted document uses the legacy
 * field names "account"/"date" inside _id (data wire-compatibility with the old system).
 */
@SpringBootTest(properties = {
		"spring.cloud.nacos.discovery.enabled=false",
		"spring.cloud.nacos.config.enabled=false",
		"spring.cloud.sentinel.enabled=false",
		"spring.config.import="
})
@EnabledIf("com.piggymetrics.statistics.repository.DataPointRepositoryRealMongoTest#mongoAvailable")
class DataPointRepositoryRealMongoTest {

	private static String mongoUri;

	static {
		String uri = System.getenv("TEST_MONGO_URI");
		if (uri == null || uri.isBlank()) {
			uri = System.getProperty("test.mongo.uri");
		}
		mongoUri = (uri != null && !uri.isBlank()) ? uri : null;
	}

	static boolean mongoAvailable() {
		return mongoUri != null;
	}

	@DynamicPropertySource
	static void props(DynamicPropertyRegistry registry) {
		registry.add("spring.data.mongodb.uri",
				() -> mongoUri != null ? mongoUri : "mongodb://localhost:27017");
		registry.add("spring.data.mongodb.database", () -> "piggymetrics-cvt-test");
	}

	private static MongoClient rawClient;

	@BeforeAll
	static void openRaw() {
		rawClient = MongoClients.create(mongoUri);
	}

	@AfterAll
	static void closeRaw() {
		if (rawClient != null) {
			rawClient.close();
		}
	}

	@Autowired
	private DataPointRepository repository;

	@Autowired
	private MongoTemplate mongoTemplate;

	@Test
	@DisplayName("CVT-03 [RED] save -> findById round-trip over REAL MongoDB preserves composite id and values")
	void roundTripRealMongo() {
		String account = "cvt-real-" + System.currentTimeMillis();
		Date today = new Date();

		DataPoint dp = new DataPoint();
		dp.setId(new DataPointId(account, today));
		dp.setIncomes(Set.of(new ItemMetric("salary", new BigDecimal("1234.5600"))));
		dp.setExpenses(Set.of(new ItemMetric("rent", new BigDecimal("500.0000"))));
		dp.setStatistics(Map.of(
				StatisticMetric.INCOMES_AMOUNT, new BigDecimal("1234.5600"),
				StatisticMetric.EXPENSES_AMOUNT, new BigDecimal("500.0000"),
				StatisticMetric.SAVING_AMOUNT, new BigDecimal("100.0000")));
		dp.setRates(Map.of(Currency.USD, BigDecimal.ONE,
				Currency.EUR, new BigDecimal("0.9000"),
				Currency.RUB, new BigDecimal("75.0000")));

		repository.save(dp);

		// read back through Spring Data (converter path)
		Optional<DataPoint> loaded = repository.findById(new DataPointId(account, today));
		assertTrue(loaded.isPresent(), "DataPoint must be found by composite id");
		DataPoint back = loaded.get();
		assertEquals(account, back.getId().getAccount());
		assertEquals(today, back.getId().getDate());
		assertEquals(0, new BigDecimal("1234.5600")
				.compareTo(back.getStatistics().get(StatisticMetric.INCOMES_AMOUNT)));
		assertEquals(1, back.getIncomes().size());
		assertEquals("salary", back.getIncomes().iterator().next().getTitle());
		assertEquals(new BigDecimal("75.0000"), back.getRates().get(Currency.RUB));

		// findByIdAccount query path
		List<DataPoint> byAccount = repository.findByAccountName(account);
		assertEquals(1, byAccount.size());
	}

	@Test
	@DisplayName("CVT-03b [RED] raw persisted _id uses legacy field names account/date (wire-compatible)")
	void rawDocumentWireCompatible() {
		String account = "cvt-raw-" + System.currentTimeMillis();
		Date today = new Date();

		DataPoint dp = new DataPoint();
		dp.setId(new DataPointId(account, today));
		dp.setIncomes(Set.of());
		dp.setExpenses(Set.of());
		dp.setStatistics(Map.of(StatisticMetric.SAVING_AMOUNT, BigDecimal.ZERO));
		dp.setRates(Map.of(Currency.USD, BigDecimal.ONE));
		repository.save(dp);

		// inspect the RAW document with the plain driver (bypassing all Spring converters)
		Document raw = rawClient.getDatabase("piggymetrics-cvt-test")
				.getCollection("datapoints")
				.find(new Document("_id.account", account))
				.first();
		assertNotNull(raw, "raw query by _id.account must find the document");
		Object id = raw.get("_id");
		assertTrue(id instanceof Document, "_id must be a subdocument, was: " + id);
		Document idDoc = (Document) id;
		assertEquals(account, idDoc.get("account"), "legacy field name 'account' must be used");
		assertTrue(idDoc.containsKey("date"), "legacy field name 'date' must be used");
		assertTrue(idDoc.get("date") instanceof Date, "_id.date must be persisted as BSON date");
	}
}
