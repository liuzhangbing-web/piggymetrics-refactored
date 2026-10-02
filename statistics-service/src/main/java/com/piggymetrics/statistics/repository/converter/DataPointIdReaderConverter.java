package com.piggymetrics.statistics.repository.converter;

import com.piggymetrics.statistics.domain.timeseries.DataPointId;
import org.bson.Document;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.stereotype.Component;

import java.util.Date;

/**
 * MIGRATION NOTE: Spring Data MongoDB 4.x no longer supports com.mongodb.DBObject based
 * conversions; the equivalent org.bson.Document form is used. Field names and semantics
 * ("date", "account") are unchanged, so persisted documents remain wire-compatible.
 */
@Component
@ReadingConverter
public class DataPointIdReaderConverter implements Converter<Document, DataPointId> {

	@Override
	public DataPointId convert(Document object) {

		Date date = object.get("date", Date.class);
		String account = object.getString("account");

		return new DataPointId(account, date);
	}
}
