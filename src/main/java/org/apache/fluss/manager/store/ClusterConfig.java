package org.apache.fluss.manager.store;

public record ClusterConfig(
        String id,
        String name,
        String bootstrapServers,
        String securityProtocol,
        String saslMechanism,
        String saslUsername,
        String saslPassword) {}
