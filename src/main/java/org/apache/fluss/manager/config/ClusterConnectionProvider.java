package org.apache.fluss.manager.config;

import org.apache.fluss.manager.store.AppConfigStore;
import org.apache.fluss.manager.store.ClusterConfig;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.fluss.client.Connection;
import org.apache.fluss.client.ConnectionFactory;
import org.apache.fluss.config.Configuration;
import org.springframework.stereotype.Component;

@Component
public class ClusterConnectionProvider {

    private final AppConfigStore store;
    private final Map<String, Connection> connections = new ConcurrentHashMap<>();

    public ClusterConnectionProvider(AppConfigStore store) {
        this.store = store;
    }

    public Connection get(String clusterId) {
        return connections.computeIfAbsent(clusterId, this::createConnection);
    }

    public String bootstrapServers(String clusterId) {
        return cluster(clusterId).bootstrapServers();
    }

    public void close(String clusterId) {
        Connection connection = connections.remove(clusterId);
        if (connection != null) {
            try {
                connection.close();
            } catch (Exception exception) {
                throw new IllegalStateException("Failed to close Fluss connection for cluster " + clusterId, exception);
            }
        }
    }

    @PreDestroy
    void closeAll() {
        connections.keySet().forEach(this::close);
    }

    private Connection createConnection(String clusterId) {
        return ConnectionFactory.createConnection(configuration(cluster(clusterId)));
    }

    private ClusterConfig cluster(String clusterId) {
        return store.cluster(clusterId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown cluster: " + clusterId));
    }

    private static Configuration configuration(ClusterConfig cluster) {
        Configuration configuration = new Configuration();
        configuration.setString("bootstrap.servers", cluster.bootstrapServers());
        setIfPresent(configuration, "client.security.protocol", cluster.securityProtocol());
        setIfPresent(configuration, "client.security.sasl.mechanism", cluster.saslMechanism());
        setIfPresent(configuration, "client.security.sasl.username", cluster.saslUsername());
        setIfPresent(configuration, "client.security.sasl.password", cluster.saslPassword());
        return configuration;
    }

    private static void setIfPresent(Configuration configuration, String key, String value) {
        if (value != null && !value.isBlank()) {
            configuration.setString(key, value);
        }
    }
}
