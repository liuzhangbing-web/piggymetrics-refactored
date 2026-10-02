package com.piggymetrics.statistics.repository.converter;

import com.piggymetrics.statistics.domain.timeseries.DataPointId;
import org.bson.Document;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.WritingConverter;
import org.springframework.stereotype.Component;

/**
 * MIGRATION NOTE: DBObject -> org.bson.Document (Spring Data MongoDB 4.x).
 * Persisted field names ("date", "account") unchanged - data wire-compatible.
 */
@Component
@WritingConverter
public class DataPointIdWriterConverter implements Converter<DataPointId, Document> {

	@Override
	public Document convert(DataPointId id) {

		Document object = new Document();

		object.put("date", id.getDate());
		object.put("account", id.getAccount());

		return object;
	}
}
