package org.apache.fluss.manager.api.dto;

import java.util.List;
import java.util.Map;

public record CreateTableRequest(
        String name,
        List<ColumnRequest> columns,
        List<String> primaryKeys,
        List<String> partitionKeys,
        List<String> bucketKeys,
        Integer bucketCount,
        String comment,
        Map<String, String> properties,
        Map<String, String> customProperties) {}
