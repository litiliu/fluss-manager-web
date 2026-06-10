package org.apache.fluss.manager.store;

import org.apache.fluss.manager.config.AppProperties;
import org.apache.fluss.manager.config.FlussProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class AppConfigStore {

    private final AppProperties appProperties;
    private final FlussProperties flussProperties;
    private final PasswordEncoder passwordEncoder;
    private final ObjectMapper objectMapper;
    private AppConfig config;

    public AppConfigStore(
            AppProperties appProperties,
            FlussProperties flussProperties,
            PasswordEncoder passwordEncoder) {
        this.appProperties = appProperties;
        this.flussProperties = flussProperties;
        this.passwordEncoder = passwordEncoder;
        this.objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }

    @PostConstruct
    void init() {
        Path path = configPath();
        if (Files.exists(path)) {
            try {
                config = objectMapper.readValue(path.toFile(), AppConfig.class);
                return;
            } catch (IOException exception) {
                throw new IllegalStateException("Failed to read app config " + path, exception);
            }
        }
        config =
                new AppConfig(
                        new AdminUser(
                                appProperties.adminUsername(),
                                passwordEncoder.encode(appProperties.adminPassword())),
                        defaultClusters());
        save();
        if ("admin".equals(appProperties.adminUsername()) && "admin".equals(appProperties.adminPassword())) {
            System.err.println(
                    "WARNING: Fluss Manager Web initialized with default admin/admin credentials. "
                            + "Set ADMIN_USERNAME and ADMIN_PASSWORD for production.");
        }
    }

    public synchronized AppConfig snapshot() {
        return new AppConfig(config.admin(), config.clusters());
    }

    public synchronized List<ClusterConfig> clusters() {
        return new ArrayList<>(config.clusters());
    }

    public synchronized Optional<ClusterConfig> cluster(String id) {
        return config.clusters().stream().filter(cluster -> cluster.id().equals(id)).findFirst();
    }

    public synchronized ClusterConfig addCluster(ClusterConfig request) {
        ClusterConfig cluster =
                new ClusterConfig(
                        blankToDefault(request.id(), UUID.randomUUID().toString()),
                        request.name(),
                        request.bootstrapServers(),
                        request.securityProtocol(),
                        request.saslMechanism(),
                        request.saslUsername(),
                        request.saslPassword());
        List<ClusterConfig> clusters = new ArrayList<>(config.clusters());
        clusters.add(cluster);
        config = new AppConfig(config.admin(), clusters);
        save();
        return cluster;
    }

    public synchronized ClusterConfig updateCluster(String id, ClusterConfig request) {
        List<ClusterConfig> clusters = new ArrayList<>(config.clusters());
        for (int index = 0; index < clusters.size(); index++) {
            ClusterConfig existing = clusters.get(index);
            if (!existing.id().equals(id)) {
                continue;
            }
            ClusterConfig updated =
                    new ClusterConfig(
                            existing.id(),
                            request.name(),
                            request.bootstrapServers(),
                            request.securityProtocol(),
                            request.saslMechanism(),
                            request.saslUsername(),
                            request.saslPassword() == null || request.saslPassword().isBlank()
                                    ? existing.saslPassword()
                                    : request.saslPassword());
            clusters.set(index, updated);
            config = new AppConfig(config.admin(), clusters);
            save();
            return updated;
        }
        throw new IllegalArgumentException("Cluster not found: " + id);
    }

    public synchronized void deleteCluster(String id) {
        List<ClusterConfig> clusters =
                config.clusters().stream().filter(cluster -> !cluster.id().equals(id)).toList();
        config = new AppConfig(config.admin(), clusters);
        save();
    }

    private List<ClusterConfig> defaultClusters() {
        if (flussProperties.bootstrapServers() == null || flussProperties.bootstrapServers().isBlank()) {
            return List.of();
        }
        return List.of(
                new ClusterConfig(
                        "default",
                        "Default",
                        flussProperties.bootstrapServers(),
                        flussProperties.securityProtocol(),
                        flussProperties.saslMechanism(),
                        flussProperties.saslUsername(),
                        flussProperties.saslPassword()));
    }

    private synchronized void save() {
        Path path = configPath();
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            objectMapper.writeValue(path.toFile(), config);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to write app config " + path, exception);
        }
    }

    private Path configPath() {
        return Path.of(appProperties.configPath()).toAbsolutePath();
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
