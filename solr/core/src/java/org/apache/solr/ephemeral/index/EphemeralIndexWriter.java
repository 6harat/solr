package org.apache.solr.ephemeral.index;

import org.apache.lucene.document.Field;
import org.apache.lucene.index.Term;

import java.io.IOException;

public interface EphemeralIndexWriter {

    long updateValues(Term term, Field... updates) throws IOException;

}
