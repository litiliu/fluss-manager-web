package org.apache.fluss.manager.api.dto;

import java.util.Map;

public record CreateDatabaseRequest(String name, String comment, Map<String, String> customProperties) {}
