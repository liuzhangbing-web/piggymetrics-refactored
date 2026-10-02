package com.piggymetrics.statistics.repository.converter;

import com.piggymetrics.statistics.domain.timeseries.DataPointId;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cases CVT-01/CVT-02 [RED]: data wire-compatibility after DBObject -> org.bson.Document
 * migration. Field names must stay exactly "date" and "account".
 */
class DataPointIdConverterTest {

	private final DataPointIdWriterConverter writer = new DataPointIdWriterConverter();
	private final DataPointIdReaderConverter reader = new DataPointIdReaderConverter();

	@Test
	@DisplayName("CVT-01 [RED] writer emits exactly fields date/account")
	void writerFields() {
		Date date = new Date(1700000000000L);
		Document doc = writer.convert(new DataPointId("acct-1", date));
		assertEquals(2, doc.size(), "must contain exactly 2 fields");
		assertTrue(doc.containsKey("date"));
		assertTrue(doc.containsKey("account"));
		assertEquals(date, doc.get("date"));
		assertEquals("acct-1", doc.getString("account"));
	}

	@Test
	@DisplayName("CVT-02 [RED] reader restores legacy-format document")
	void readerLegacy() {
		Date date = new Date(1700000000000L);
		Document legacy = new Document("date", date).append("account", "acct-legacy");
		DataPointId id = reader.convert(legacy);
		assertEquals("acct-legacy", id.getAccount());
		assertEquals(date, id.getDate());
	}

	@Test
	@DisplayName("CVT round-trip writer->reader preserves identity")
	void roundTrip() {
		DataPointId original = new DataPointId("acct-rt", new Date());
		DataPointId restored = reader.convert(writer.convert(original));
		assertEquals(original.getAccount(), restored.getAccount());
		assertEquals(original.getDate(), restored.getDate());
	}
}
