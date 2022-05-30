package org.apache.solr.ephemeral.core;

import org.apache.solr.ephemeral.update.DefaultEphemeralCoreState;
import org.apache.solr.ephemeral.update.EphemeralCoreState;

public class EphemeralCore {

    private final EphemeralCoreState coreState;

    public EphemeralCore() {
        coreState = new DefaultEphemeralCoreState();
    }

    public EphemeralCoreState getCoreState() {
        return coreState;
    }
}
