package org.apache.solr.ephemeral.update;

import org.apache.solr.ephemeral.core.EphemeralCore;
import org.apache.solr.ephemeral.index.EphemeralIndexWriter;

import java.io.IOException;

public interface EphemeralCoreState {

    // TODO-BG
    EphemeralIndexWriter getIndexWriter(EphemeralCore core) throws IOException;

}
