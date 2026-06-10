package org.apache.fluss.manager.api;

import org.apache.fluss.manager.config.ClusterConnectionProvider;
import org.apache.fluss.manager.store.AppConfigStore;
import org.apache.fluss.manager.store.ClusterConfig;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/clusters")
public class ClusterController {

    private final AppConfigStore store;
    private final ClusterConnectionProvider connectionProvider;

    public ClusterController(AppConfigStore store, ClusterConnectionProvider connectionProvider) {
        this.store = store;
        this.connectionProvider = connectionProvider;
    }

    @GetMapping
    public List<ClusterConfig> clusters() {
        return store.clusters().stream().map(ClusterController::view).toList();
    }

    @PostMapping
    public ClusterConfig create(@RequestBody ClusterConfig request) {
        return view(store.addCluster(request));
    }

    @PatchMapping("/{clusterId}")
    public ClusterConfig update(
            @PathVariable("clusterId") String clusterId, @RequestBody ClusterConfig request) {
        connectionProvider.close(clusterId);
        return view(store.updateCluster(clusterId, request));
    }

    @DeleteMapping("/{clusterId}")
    public void delete(@PathVariable("clusterId") String clusterId) {
        connectionProvider.close(clusterId);
        store.deleteCluster(clusterId);
    }

    private static ClusterConfig view(ClusterConfig cluster) {
        return new ClusterConfig(
                cluster.id(),
                cluster.name(),
                cluster.bootstrapServers(),
                cluster.securityProtocol(),
                cluster.saslMechanism(),
                cluster.saslUsername(),
                "");
    }
}
