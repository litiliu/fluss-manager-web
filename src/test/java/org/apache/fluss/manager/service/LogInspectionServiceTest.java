package org.apache.fluss.manager.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.apache.fluss.manager.api.dto.LogInspection.BucketRange;
import org.apache.fluss.manager.api.dto.LogInspection.ReadRequest;
import org.apache.fluss.manager.config.ClusterConnectionProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.apache.fluss.client.Connection;
import org.apache.fluss.client.admin.Admin;
import org.apache.fluss.client.admin.ListOffsetsResult;
import org.apache.fluss.client.admin.OffsetSpec;
import org.apache.fluss.client.table.Table;
import org.apache.fluss.client.table.scanner.Scan;
import org.apache.fluss.client.table.scanner.ScanRecord;
import org.apache.fluss.client.table.scanner.log.LogScanner;
import org.apache.fluss.client.table.scanner.log.ScanRecords;
import org.apache.fluss.metadata.PartitionInfo;
import org.apache.fluss.metadata.Schema;
import org.apache.fluss.metadata.TableBucket;
import org.apache.fluss.metadata.TableDescriptor;
import org.apache.fluss.metadata.TableInfo;
import org.apache.fluss.metadata.TablePath;
import org.apache.fluss.record.ChangeType;
import org.apache.fluss.row.BinaryString;
import org.apache.fluss.row.GenericRow;
import org.apache.fluss.types.DataTypes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LogInspectionServiceTest {
    private static final TablePath PATH = TablePath.of("it", "events");
    private static final TableBucket BUCKET = new TableBucket(7L, 0);
    private final Connection connection = mock(Connection.class);
    private final Admin admin = mock(Admin.class);
    private final Table table = mock(Table.class);
    private final Scan scan = mock(Scan.class);
    private final LogScanner scanner = mock(LogScanner.class);
    private final ClusterConnectionProvider provider = mock(ClusterConnectionProvider.class);
    private SdkFlussMetadataService service;

    @BeforeEach
    void setUp() {
        when(provider.get("JFK")).thenReturn(connection);
        when(connection.getAdmin()).thenReturn(admin);
        when(connection.getTable(PATH)).thenReturn(table);
        when(table.newScan()).thenReturn(scan);
        when(scan.createLogScanner()).thenReturn(scanner);
        when(table.getTableInfo()).thenReturn(info(false, false, 1));
        when(admin.getTableInfo(PATH)).thenReturn(CompletableFuture.completedFuture(info(false, false, 1)));
        offsets(0, 3);
        service = new SdkFlussMetadataService(provider);
    }

    @Test
    void truncatedPageDoesNotSkipBufferedRowsAndContinuationKeepsTheOriginalEnd() throws Exception {
        ScanRecords batch = recordsWithProgress(Map.of(BUCKET, List.of(record(0), record(1), record(2), record(3))),
                Map.of(BUCKET, 4L));
        when(scanner.poll(any(Duration.class))).thenReturn(batch);

        var first = service.readChangelog("JFK", "it", "events", request("0", null, 2));
        assertThat(first.status()).isEqualTo("TRUNCATED");
        assertThat(first.rows()).extracting(row -> row.offset()).containsExactly("0", "1");
        assertThat(first.cursors().get(0).nextOffset()).isEqualTo("2");
        assertThat(first.cursors().get(0).endOffset()).isEqualTo("3");

        offsets(0, 10); // New writes must not enlarge an already captured range.
        var next = service.readChangelog("JFK", "it", "events", request("2", "3", 2));
        assertThat(next.status()).isEqualTo("COMPLETE");
        assertThat(next.rows()).extracting(row -> row.offset()).containsExactly("2");
        assertThat(next.cursors().get(0).endOffset()).isEqualTo("3");
        verify(scanner).subscribe(0, 2L);
    }

    @Test
    void rowLimitCannotAdvanceAnUnreturnedSecondBucket() {
        when(table.getTableInfo()).thenReturn(info(false, false, 2));
        when(admin.listOffsets(eq(PATH), anyList(), any(OffsetSpec.EarliestSpec.class)))
                .thenReturn(offsetsResult(Map.of(0, 0L, 1, 0L)));
        when(admin.listOffsets(eq(PATH), anyList(), any(OffsetSpec.LatestSpec.class)))
                .thenReturn(offsetsResult(Map.of(0, 1L, 1, 1L)));
        TableBucket second = new TableBucket(7L, 1);
        Map<TableBucket, List<ScanRecord>> rows = new LinkedHashMap<>();
        rows.put(BUCKET, List.of(record(0)));
        rows.put(second, List.of(record(0)));
        when(scanner.poll(any(Duration.class)))
                .thenReturn(recordsWithProgress(rows, Map.of(BUCKET, 1L, second, 1L)));

        var result = service.readChangelog("JFK", "it", "events", new ReadRequest("7",
                List.of(new BucketRange(null, 0, "0", null), new BucketRange(null, 1, "0", null)), 1, 5000));
        assertThat(result.status()).isEqualTo("TRUNCATED");
        assertThat(result.cursors()).extracting(cursor -> cursor.nextOffset()).containsExactly("1", "0");
    }

    @Test
    void emptyRangeReturnsCompleteWithoutOpeningAnyScannerOrSnapshot() {
        offsets(0, 0);
        var result = service.readChangelog("JFK", "it", "events", request("0", null, 10));
        assertThat(result.status()).isEqualTo("COMPLETE");
        assertThat(result.rows()).isEmpty();
        verify(table, never()).newScan();
    }

    @Test
    void scannerProgressCanFinishAGapWithoutMaterializedRows() {
        assumeTrue(hasScannerProgress(), "Installed client does not expose progress-only offsets");
        when(scanner.poll(any(Duration.class)))
                .thenReturn(recordsWithProgress(Map.of(), Map.of(BUCKET, 3L)));
        var result = service.readChangelog("JFK", "it", "events", request("0", null, 10));
        assertThat(result.status()).isEqualTo("COMPLETE");
        assertThat(result.rows()).isEmpty();
        assertThat(result.cursors().get(0).nextOffset()).isEqualTo("3");
    }

    @Test
    void noProgressIsTimedOutRatherThanReportedAsAnEmptyCompleteRange() throws Exception {
        when(scanner.poll(any(Duration.class))).thenAnswer(invocation -> {
            Thread.sleep(120);
            return ScanRecords.EMPTY;
        });
        var result = service.readChangelog("JFK", "it", "events", new ReadRequest("7",
                List.of(new BucketRange(null, 0, "0", "3")), 10, 100));
        assertThat(result.status()).isEqualTo("TIMED_OUT");
        assertThat(result.cursors().get(0).nextOffset()).isEqualTo("0");
        verify(scanner).close();
        verify(table).close();
        verify(admin).close();
    }

    @Test
    void primaryKeyChangelogRetainsEveryChangeTypeAndRawRow() {
        when(table.getTableInfo()).thenReturn(info(true, false, 1));
        offsets(0, 4);
        List<ScanRecord> records = List.of(
                record(0, ChangeType.INSERT), record(1, ChangeType.UPDATE_BEFORE),
                record(2, ChangeType.UPDATE_AFTER), record(3, ChangeType.DELETE));
        when(scanner.poll(any(Duration.class))).thenReturn(new ScanRecords(Map.of(BUCKET, records)));

        var result = service.readChangelog("JFK", "it", "events", request("0", null, 10));
        assertThat(result.status()).isEqualTo("COMPLETE");
        assertThat(result.rows()).extracting(row -> row.changeType())
                .containsExactly("INSERT", "UPDATE_BEFORE", "UPDATE_AFTER", "DELETE");
        assertThat(result.rows().get(0).row()).containsEntry("payload", "value-0");
    }

    @Test
    void offsetsAndRecordsPreserveValuesBeyondJavascriptIntegerPrecision() throws Exception {
        long large = 9_007_199_254_740_993L;
        offsets(large, large + 1);
        var snapshot = service.offsets("JFK", "it", "events");
        assertThat(new ObjectMapper().writeValueAsString(snapshot))
                .contains("\"earliestOffset\":\"9007199254740993\"");
        when(scanner.poll(any(Duration.class)))
                .thenReturn(new ScanRecords(Map.of(BUCKET, List.of(record(large)))));
        var result = service.readChangelog("JFK", "it", "events", request(Long.toString(large), null, 10));
        assertThat(result.rows().get(0).offset()).isEqualTo("9007199254740993");
        assertThat(result.cursors().get(0).nextOffset()).isEqualTo("9007199254740994");
    }

    @Test
    void allPartitionBucketsAreIncludedAndSubscribedWithPartitionIds() throws Exception {
        TableInfo info = info(false, true, 1);
        when(table.getTableInfo()).thenReturn(info);
        when(admin.getTableInfo(PATH)).thenReturn(CompletableFuture.completedFuture(info));
        List<PartitionInfo> partitions = java.util.stream.LongStream.rangeClosed(1, 4)
                .mapToObj(id -> {
                    PartitionInfo partition = mock(PartitionInfo.class);
                    when(partition.getPartitionId()).thenReturn(id);
                    when(partition.getPartitionName()).thenReturn("p" + id);
                    return partition;
                })
                .toList();
        when(admin.listPartitionInfos(PATH)).thenReturn(CompletableFuture.completedFuture(partitions));
        when(admin.listOffsets(eq(PATH), any(String.class), anyList(), any(OffsetSpec.EarliestSpec.class)))
                .thenReturn(offsetsResult(Map.of(0, 0L)));
        when(admin.listOffsets(eq(PATH), any(String.class), anyList(), any(OffsetSpec.LatestSpec.class)))
                .thenReturn(offsetsResult(Map.of(0, 1L)));
        var snapshot = service.offsets("JFK", "it", "events");
        assertThat(snapshot.buckets()).hasSize(4);
        when(scanner.poll(any(Duration.class))).thenReturn(new ScanRecords(
                Map.of(new TableBucket(7L, 4L, 0), List.of(record(0)))));
        var result = service.readChangelog("JFK", "it", "events", new ReadRequest("7",
                List.of(new BucketRange("4", 0, "0", null)), 10, 5000));
        assertThat(result.status()).isEqualTo("COMPLETE");
        verify(scanner).subscribe(4L, 0, 0L);
    }

    @Test
    void invalidOrExpiredRangesAndRecreatedTableAreRejected() {
        offsets(2, 5);
        assertThatThrownBy(() -> service.readChangelog("JFK", "it", "events", request("0", null, 10)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("retained offsets");
        assertThatThrownBy(() -> service.readChangelog("JFK", "it", "events", request("3", "9", 10)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.readChangelog("JFK", "it", "events", request("4", "3", 10)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.readChangelog("JFK", "it", "events", new ReadRequest("8", List.of(), 10, 5000)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Table has changed");
        assertThatThrownBy(() -> service.readChangelog("JFK", "it", "events", new ReadRequest("7",
                List.of(new BucketRange(null, 0, "2", null), new BucketRange(null, 0, "2", null)), 10, 5000)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("duplicate");
        assertThatThrownBy(() -> service.readChangelog("JFK", "it", "events", new ReadRequest("7",
                List.of(new BucketRange(null, null, "2", null)), 10, 5000)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("bucket ID");
    }

    @Test
    void pollFailureStillClosesAllRequestResources() throws Exception {
        when(scanner.poll(any(Duration.class))).thenThrow(new IllegalStateException("read failed"));
        assertThatThrownBy(() -> service.readChangelog("JFK", "it", "events", request("0", null, 10)))
                .hasMessage("read failed");
        verify(scanner).close();
        verify(table).close();
        verify(admin).close();
    }

    @Test
    void oversizedTablesAreRejectedRatherThanSilentlySampled() {
        when(admin.getTableInfo(PATH)).thenReturn(CompletableFuture.completedFuture(info(false, false, 257)));
        assertThatThrownBy(() -> service.offsets("JFK", "it", "events"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("No buckets were silently omitted");
    }

    private void offsets(long earliest, long latest) {
        when(admin.listOffsets(eq(PATH), anyList(), any(OffsetSpec.EarliestSpec.class)))
                .thenReturn(offsetsResult(Map.of(0, earliest)));
        when(admin.listOffsets(eq(PATH), anyList(), any(OffsetSpec.LatestSpec.class)))
                .thenReturn(offsetsResult(Map.of(0, latest)));
    }

    private static ListOffsetsResult offsetsResult(Map<Integer, Long> offsets) {
        Map<Integer, CompletableFuture<Long>> futures = new LinkedHashMap<>();
        offsets.forEach((bucket, offset) -> futures.put(bucket, CompletableFuture.completedFuture(offset)));
        return new ListOffsetsResult(futures);
    }

    private static ReadRequest request(String start, String end, int limit) {
        return new ReadRequest("7", List.of(new BucketRange(null, 0, start, end)), limit, 5000);
    }

    private static boolean hasScannerProgress() {
        try {
            ScanRecords.class.getMethod("consumedUpToOffset", TableBucket.class);
            return true;
        } catch (NoSuchMethodException exception) {
            return false;
        }
    }

    private static ScanRecords recordsWithProgress(
            Map<TableBucket, List<ScanRecord>> records, Map<TableBucket, Long> progress) {
        try {
            return ScanRecords.class.getConstructor(Map.class, Map.class).newInstance(records, progress);
        } catch (NoSuchMethodException exception) {
            return new ScanRecords(records);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Cannot construct scanner test result", exception);
        }
    }

    private static ScanRecord record(long offset) {
        return record(offset, ChangeType.APPEND_ONLY);
    }

    private static ScanRecord record(long offset, ChangeType changeType) {
        GenericRow row = new GenericRow(2);
        row.setField(0, 1);
        row.setField(1, BinaryString.fromString("value-" + offset));
        return new ScanRecord(offset, 1000L, changeType, row);
    }

    private static TableInfo info(boolean primaryKey, boolean partitioned, int buckets) {
        Schema.Builder builder = Schema.newBuilder().column("id", DataTypes.INT()).column("payload", DataTypes.STRING());
        if (primaryKey) builder.primaryKey("id");
        TableDescriptor.Builder descriptor = TableDescriptor.builder()
                .schema(builder.build()).distributedBy(buckets);
        if (partitioned) descriptor.partitionedBy("id");
        try {
            try {
                return (TableInfo) TableInfo.class.getMethod("of", TablePath.class, long.class,
                        int.class, TableDescriptor.class, String.class, long.class, long.class)
                        .invoke(null, PATH, 7L, 1, descriptor.build(), "", 1L, 1L);
            } catch (NoSuchMethodException exception) {
                return (TableInfo) TableInfo.class.getMethod("of", TablePath.class, long.class,
                        int.class, TableDescriptor.class, long.class, long.class)
                        .invoke(null, PATH, 7L, 1, descriptor.build(), 1L, 1L);
            }
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Cannot construct table metadata test fixture", exception);
        }
    }
}
