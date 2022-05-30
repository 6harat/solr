package org.apache.solr.ephemeral.index;

import org.apache.lucene.document.Field;
import org.apache.lucene.index.DocValuesType;
import org.apache.lucene.index.Term;
import org.apache.solr.ephemeral.index.command.EphemeralUpdate;
import org.apache.solr.ephemeral.index.command.NumericEphemeralUpdate;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class DefaultEphemeralIndexWriter implements EphemeralIndexWriter {
    private final Map<String, Object> db;

    public DefaultEphemeralIndexWriter() {
        db = new ConcurrentHashMap<>();
    }

    @Override
    public long updateValues(Term term, Field... updates) throws IOException {
        //ensureOpen();
        EphemeralUpdate[] ephemeralUpdates = buildValuesUpdate(term, updates);
        return -1;
    }

    private EphemeralUpdate[] buildValuesUpdate(Term term, Field... updates) {
        EphemeralUpdate[] ephemeralUpdates = new EphemeralUpdate[updates.length];
        for (int i = 0; i < updates.length; i++) {
            final Field f = updates[i];
            final DocValuesType dvType = f.fieldType().docValuesType();
            if (dvType == null) {
                throw new NullPointerException(
                        "DocValuesType must not be null (field: \"" + f.name() + "\")");
            }
            if (dvType == DocValuesType.NONE) {
                throw new IllegalArgumentException(
                        "can only update NUMERIC or BINARY fields! field=" + f.name());
            }

            switch (dvType) {
                case NUMERIC:
                    Long value = (Long) f.numericValue();
                    ephemeralUpdates[i] = new NumericEphemeralUpdate(term, f.name(), value);
                    break;
                case BINARY:
                case NONE:
                case SORTED:
                case SORTED_NUMERIC:
                case SORTED_SET:
                default:
                    throw new IllegalArgumentException(
                            "can only update NUMERIC or BINARY fields: field=" + f.name() + ", type=" + dvType);
            }
        }
        return ephemeralUpdates;
    }

}
