package org.apache.fluss.manager.api.dto;

import java.util.List;
import java.util.Map;

/** Offsets are decimal strings so browsers can preserve the full 64-bit value. */
public final class LogInspection {
    private LogInspection() {}

    public record BucketOffset(
            String partitionId, String partitionName, int bucket,
            String earliestOffset, String latestOffset) {}

    public record OffsetSnapshot(
            String clusterId, String database, String table, String tableId,
            String startedAt, String sampledAt, List<BucketOffset> buckets) {}

    public record BucketRange(String partitionId, Integer bucket, String startOffset, String endOffset) {}

    public record ReadRequest(String tableId, List<BucketRange> ranges, Integer limit, Integer timeoutMs) {}

    public record BucketCursor(
            String partitionId, String partitionName, int bucket,
            String startOffset, String endOffset, String nextOffset, boolean complete) {}

    public record LogRow(
            String partitionId, int bucket, String offset, String timestampMillis,
            String timestamp, String changeType, Map<String, Object> row) {}

    public record ReadResult(
            String clusterId, String database, String table, String tableId,
            String capturedAt, String finishedAt, String status,
            List<LogRow> rows, List<BucketCursor> cursors) {}
}
