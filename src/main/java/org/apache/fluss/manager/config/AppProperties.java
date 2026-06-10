package org.apache.fluss.manager.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(String configPath, String adminUsername, String adminPassword) {}
