package org.apache.fluss.manager.store;

import java.util.ArrayList;
import java.util.List;

public record AppConfig(AdminUser admin, List<ClusterConfig> clusters) {
    public AppConfig {
        clusters = clusters == null ? new ArrayList<>() : new ArrayList<>(clusters);
    }
}
