package org.apache.solr.ephemeral.update;

import org.apache.solr.ephemeral.core.EphemeralCore;
import org.apache.solr.ephemeral.index.DefaultEphemeralIndexWriter;

import java.io.IOException;

public class DefaultEphemeralCoreState implements EphemeralCoreState {

    @Override
    public DefaultEphemeralIndexWriter getIndexWriter(EphemeralCore core) throws IOException {
        return null;
    }

}
