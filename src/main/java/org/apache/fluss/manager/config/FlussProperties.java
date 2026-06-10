package org.apache.fluss.manager.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "fluss")
public record FlussProperties(
        String bootstrapServers,
        String securityProtocol,
        String saslMechanism,
        String saslUsername,
        String saslPassword) {}
