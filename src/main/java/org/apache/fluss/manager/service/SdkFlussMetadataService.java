package org.apache.fluss.manager.service;

import org.apache.fluss.manager.api.dto.LogInspection.BucketCursor;
import org.apache.fluss.manager.api.dto.LogInspection.BucketOffset;
import org.apache.fluss.manager.api.dto.LogInspection.BucketRange;
import org.apache.fluss.manager.api.dto.LogInspection.LogRow;
import org.apache.fluss.manager.api.dto.LogInspection.OffsetSnapshot;
import org.apache.fluss.manager.api.dto.LogInspection.ReadRequest;
import org.apache.fluss.manager.api.dto.LogInspection.ReadResult;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.fluss.row.Decimal;

import org.apache.fluss.manager.api.dto.AlterTableRequest;
import org.apache.fluss.manager.api.dto.ColumnRequest;
import org.apache.fluss.manager.api.dto.CreateDatabaseRequest;
import org.apache.fluss.manager.api.dto.CreateTableRequest;
import org.apache.fluss.manager.config.ClusterConnectionProvider;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.apache.fluss.client.Connection;
import org.apache.fluss.client.admin.Admin;
import org.apache.fluss.client.admin.OffsetSpec;
import org.apache.fluss.client.lookup.LookupResult;
import org.apache.fluss.client.table.Table;
import org.apache.fluss.client.table.scanner.ScanRecord;
import org.apache.fluss.client.table.scanner.batch.BatchScanner;
import org.apache.fluss.client.table.scanner.log.LogScanner;
import org.apache.fluss.client.table.scanner.log.ScanRecords;
import org.apache.fluss.cluster.ServerNode;
import org.apache.fluss.metadata.DatabaseDescriptor;
import org.apache.fluss.metadata.PartitionInfo;
import org.apache.fluss.metadata.Schema;
import org.apache.fluss.metadata.TableBucket;
import org.apache.fluss.metadata.TableChange;
import org.apache.fluss.metadata.TableDescriptor;
import org.apache.fluss.metadata.TableInfo;
import org.apache.fluss.metadata.TablePath;
import org.apache.fluss.metadata.TableStats;
import org.apache.fluss.row.BinaryString;
import org.apache.fluss.row.InternalRow;
import org.apache.fluss.row.TimestampLtz;
import org.apache.fluss.row.TimestampNtz;
import org.apache.fluss.types.DataType;
import org.apache.fluss.types.DataTypeRoot;
import org.apache.fluss.types.DataTypes;
import org.apache.fluss.types.DecimalType;
import org.apache.fluss.types.LocalZonedTimestampType;
import org.apache.fluss.types.TimestampType;
import org.springframework.stereotype.Service;

@Service
public class SdkFlussMetadataService implements FlussMetadataService {

    private static final String DEFAULT_DATABASE_NAME = "fluss";
    private static final int DEFAULT_PREVIEW_LIMIT = 100;
    private static final int MAX_PREVIEW_LIMIT = 500;
    private static final int MAX_PREVIEW_SUBSCRIPTIONS = 256;
    private static final int MAX_PREVIEW_PARTITIONS = 3;
    private static final int EMPTY_POLL_LIMIT = 2;

    private final ClusterConnectionProvider connectionProvider;

    public SdkFlussMetadataService(ClusterConnectionProvider connectionProvider) {
        this.connectionProvider = connectionProvider;
    }

    @Override
    public Map<String, Object> health(String clusterId) {
        return withAdmin(clusterId, admin -> {
            List<String> databases = admin.listDatabases().join();
            return Map.of(
                    "status", "UP",
                    "bootstrapServers", connectionProvider.bootstrapServers(clusterId),
                    "databaseCount", databases.size());
        });
    }

    @Override
    public List<Map<String, Object>> servers(String clusterId) {
        return withAdmin(clusterId, admin -> admin.getServerNodes().join().stream().map(this::server).toList());
    }

    @Override
    public List<String> databases(String clusterId) {
        return withAdmin(clusterId, admin -> admin.listDatabases().join());
    }

    @Override
    public void createDatabase(String clusterId, CreateDatabaseRequest request) {
        withAdmin(
                clusterId,
                admin -> {
                    DatabaseDescriptor.Builder builder = DatabaseDescriptor.builder();
                    if (request.comment() != null && !request.comment().isBlank()) {
                        builder.comment(request.comment());
                    }
                    if (request.customProperties() != null) {
                        builder.customProperties(request.customProperties());
                    }
                    admin.createDatabase(request.name(), builder.build(), false).join();
                    return null;
                });
    }

    @Override
    public void dropDatabase(String clusterId, String database, boolean ignoreIfNotExists, boolean cascade) {
        if (DEFAULT_DATABASE_NAME.equals(database)) {
            throw new IllegalArgumentException(
                    "Cannot drop the default database '"
                            + database
                            + "'. The default database is required for cluster operation.");
        }
        withAdmin(
                clusterId,
                admin -> {
                    admin.dropDatabase(database, ignoreIfNotExists, cascade).join();
                    return null;
                });
    }

    @Override
    public List<String> tables(String clusterId, String database) {
        return withAdmin(clusterId, admin -> admin.listTables(database).join());
    }

    @Override
    public void createTable(String clusterId, String database, CreateTableRequest request) {
        TablePath path = TablePath.of(database, request.name());
        withAdmin(
                clusterId,
                admin -> {
                    admin.createTable(path, tableDescriptor(request), false).join();
                    return null;
                });
    }

    @Override
    public void alterTable(String clusterId, String database, String table, AlterTableRequest request) {
        TablePath path = TablePath.of(database, table);
        List<TableChange> changes = tableChanges(request);
        if (changes.isEmpty()) {
            return;
        }
        withAdmin(
                clusterId,
                admin -> {
                    admin.alterTable(path, changes, false).join();
                    return null;
                });
    }

    @Override
    public void dropTable(String clusterId, String database, String table, boolean ignoreIfNotExists) {
        TablePath path = TablePath.of(database, table);
        withAdmin(
                clusterId,
                admin -> {
                    admin.dropTable(path, ignoreIfNotExists).join();
                    return null;
                });
    }

    @Override
    public Map<String, Object> table(String clusterId, String database, String table) {
        TablePath path = TablePath.of(database, table);
        return withAdmin(clusterId, admin -> {
            TableInfo info = admin.getTableInfo(path).join();
            Map<String, Object> model = new LinkedHashMap<>();
            model.put("database", info.getTablePath().getDatabaseName());
            model.put("name", info.getTablePath().getTableName());
            model.put("tableId", info.getTableId());
            model.put("schemaId", info.getSchemaId());
            model.put("rowType", info.getRowType().toString());
            model.put("columns", columns(info.getSchema()));
            model.put("primaryKeys", info.getPrimaryKeys());
            model.put("physicalPrimaryKeys", info.getPhysicalPrimaryKeys());
            model.put("bucketKeys", info.getBucketKeys());
            model.put("partitionKeys", info.getPartitionKeys());
            model.put("numBuckets", info.getNumBuckets());
            model.put("partitioned", info.isPartitioned());
            model.put("autoPartitioned", info.isAutoPartitioned());
            model.put("comment", info.getComment().orElse(""));
            model.put("createdTime", toIsoTime(info.getCreatedTime()));
            model.put("modifiedTime", toIsoTime(info.getModifiedTime()));
            model.put("properties", info.getProperties().toMap());
            model.put("customProperties", info.getCustomProperties().toMap());
            model.put("ddl", ddl(info));
            model.put("partitions", partitions(admin, path, info));
            model.put("stats", stats(admin, path));
            return model;
        });
    }

    @Override
    public Map<String, Object> preview(String clusterId, String database, String table, int limit) {
        int previewLimit = normalizePreviewLimit(limit);
        Connection connection = connectionProvider.get(clusterId);
        TablePath path = TablePath.of(database, table);
        try (Admin admin = connection.getAdmin(); Table flussTable = connection.getTable(path)) {
            TableInfo info = flussTable.getTableInfo();
            List<PreviewSubscription> subscriptions = previewSubscriptions(admin, info, previewLimit);
            List<Map<String, Object>> rows = previewRows(flussTable, info, subscriptions, previewLimit);
            String mode = "latest-log";
            if (rows.isEmpty()) {
                rows = batchPreviewRows(flussTable, admin, info, previewLimit);
                mode = "snapshot-sample";
            }
            Map<String, Object> model = new LinkedHashMap<>();
            model.put("database", database);
            model.put("table", table);
            model.put("limit", previewLimit);
            model.put("columns", previewColumns(info));
            model.put("rows", rows);
            model.put("rowCount", rows.size());
            model.put("mode", mode);
            model.put("subscriptions", subscriptions.size());
            model.put("truncatedSubscriptions", subscriptions.size() >= MAX_PREVIEW_SUBSCRIPTIONS);
            return model;
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to preview table " + path, exception);
        }
    }

    @Override
    public Map<String, Object> query(
            String clusterId, String database, String table, String where, String orderBy, String orderDir, int limit) {
        int queryLimit = normalizePreviewLimit(limit);
        Connection connection = connectionProvider.get(clusterId);
        TablePath path = TablePath.of(database, table);
        try (Admin admin = connection.getAdmin(); Table flussTable = connection.getTable(path)) {
            TableInfo info = flussTable.getTableInfo();
            List<QueryPredicate> predicates = parseWhere(where);
            List<Map<String, Object>> rows;
            String mode;
            if (canUsePrimaryKeyLookup(info, predicates, orderBy)) {
                rows = lookupRows(flussTable, info, predicates);
                mode = "primary-key-lookup";
            } else {
                Map<String, Object> preview = preview(clusterId, database, table, Math.max(queryLimit * 5, queryLimit));
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> previewRows =
                        (List<Map<String, Object>>) preview.getOrDefault("rows", List.of());
                rows = previewRows.stream().filter(row -> matches(row, predicates)).toList();
                mode = "preview-filter";
            }
            rows = sortRows(rows, orderBy, orderDir).stream().limit(queryLimit).toList();

            Map<String, Object> model = new LinkedHashMap<>();
            model.put("database", database);
            model.put("table", table);
            model.put("where", where == null ? "" : where);
            model.put("orderBy", orderBy == null ? "" : orderBy);
            model.put("orderDir", normalizedOrderDir(orderDir));
            model.put("limit", queryLimit);
            model.put("mode", mode);
            model.put("columns", previewColumns(info));
            model.put("rows", rows);
            model.put("rowCount", rows.size());
            return model;
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to query table " + path, exception);
        }
    }

    private TableDescriptor tableDescriptor(CreateTableRequest request) {
        Schema.Builder schemaBuilder = Schema.newBuilder();
        for (ColumnRequest column : request.columns()) {
            schemaBuilder.column(column.name(), DataTypes.parse(column.type()));
        }
        if (request.primaryKeys() != null && !request.primaryKeys().isEmpty()) {
            schemaBuilder.primaryKey(request.primaryKeys());
        }
        if (request.comment() != null && !request.comment().isBlank()) {
            schemaBuilder.withComment(request.comment());
        }

        TableDescriptor.Builder builder = TableDescriptor.builder().schema(schemaBuilder.build());
        if (request.partitionKeys() != null && !request.partitionKeys().isEmpty()) {
            builder.partitionedBy(request.partitionKeys());
        }
        if (request.bucketCount() != null && request.bucketCount() > 0) {
            List<String> bucketKeys = request.bucketKeys() == null ? List.of() : request.bucketKeys();
            builder.distributedBy(request.bucketCount(), bucketKeys);
        }
        if (request.comment() != null && !request.comment().isBlank()) {
            builder.comment(request.comment());
        }
        if (request.properties() != null) {
            builder.properties(request.properties());
        }
        if (request.customProperties() != null) {
            builder.customProperties(request.customProperties());
        }
        return builder.build();
    }

    private List<TableChange> tableChanges(AlterTableRequest request) {
        List<TableChange> changes = new ArrayList<>();
        if (request.addColumns() != null) {
            request.addColumns()
                    .forEach(
                            column ->
                                    changes.add(
                                            TableChange.addColumn(
                                                    column.name(),
                                                    DataTypes.parse(column.type()),
                                                    blankToEmpty(column.comment()),
                                                    columnPosition(column.first(), column.afterColumn()))));
        }
        if (request.modifyColumns() != null) {
            request.modifyColumns()
                    .forEach(
                            column ->
                                    changes.add(
                                            TableChange.modifyColumn(
                                                    column.name(),
                                                    DataTypes.parse(column.type()),
                                                    blankToEmpty(column.comment()),
                                                    columnPosition(column.first(), column.afterColumn()))));
        }
        if (request.renameColumns() != null) {
            request.renameColumns()
                    .forEach(column -> changes.add(TableChange.renameColumn(column.oldName(), column.newName())));
        }
        if (request.dropColumns() != null) {
            request.dropColumns().forEach(column -> changes.add(TableChange.dropColumn(column)));
        }
        if (request.setOptions() != null) {
            request.setOptions().forEach((key, value) -> changes.add(TableChange.set(key, value)));
        }
        if (request.resetOptions() != null) {
            request.resetOptions().forEach(option -> changes.add(TableChange.reset(option)));
        }
        return changes;
    }

    private static TableChange.ColumnPosition columnPosition(Boolean first, String afterColumn) {
        if (Boolean.TRUE.equals(first)) {
            return TableChange.ColumnPosition.first();
        }
        if (afterColumn != null && !afterColumn.isBlank()) {
            return TableChange.ColumnPosition.after(afterColumn);
        }
        return TableChange.ColumnPosition.last();
    }

    private static String blankToEmpty(String value) {
        return value == null ? "" : value;
    }

    private <T> T withAdmin(String clusterId, AdminOperation<T> operation) {
        Connection connection = connectionProvider.get(clusterId);
        Admin admin = connection.getAdmin();
        try {
            return operation.execute(admin);
        } finally {
            try {
                admin.close();
            } catch (Exception exception) {
                throw new IllegalStateException("Failed to close Fluss admin client", exception);
            }
        }
    }

    private Map<String, Object> server(ServerNode server) {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("id", server.id());
        model.put("uid", server.uid());
        model.put("host", server.host());
        model.put("port", server.port());
        model.put("type", server.serverType().toString());
        model.put("rack", server.rack());
        return model;
    }

    private List<Map<String, Object>> columns(Schema schema) {
        return schema.getColumns().stream()
                .map(
                        column -> {
                            Map<String, Object> model = new LinkedHashMap<>();
                            model.put("id", column.getColumnId());
                            model.put("name", column.getName());
                            model.put("type", column.getDataType().toString());
                            model.put("comment", column.getComment().orElse(""));
                            model.put("aggFunction", column.getAggFunction().map(Object::toString).orElse(""));
                            return model;
                        })
                .toList();
    }

    private List<Map<String, Object>> partitions(Admin admin, TablePath path, TableInfo info) {
        if (!info.isPartitioned()) {
            return List.of();
        }
        return admin.listPartitionInfos(path).join().stream().map(this::partition).toList();
    }

    private Map<String, Object> partition(PartitionInfo partition) {
        return Map.of(
                "id", partition.getPartitionId(),
                "name", partition.getPartitionName(),
                "spec", partition.getPartitionSpec().toString());
    }

    private Map<String, Object> stats(Admin admin, TablePath path) {
        try {
            TableStats stats = admin.getTableStats(path).join();
            return Map.of("available", true, "rowCount", stats.getRowCount());
        } catch (RuntimeException exception) {
            return Map.of("available", false, "message", rootMessage(exception));
        }
    }

    private List<Map<String, Object>> previewRows(
            Table table, TableInfo info, List<PreviewSubscription> subscriptions, int previewLimit)
            throws Exception {
        if (subscriptions.isEmpty()) {
            return List.of();
        }
        try (LogScanner scanner = table.newScan().createLogScanner()) {
            subscriptions.forEach(subscription -> subscription.subscribe(scanner));
            InternalRow.FieldGetter[] getters = InternalRow.createFieldGetters(info.getRowType());
            List<PreviewRecord> records = new ArrayList<>();
            int emptyPolls = 0;
            int maxRowsToBuffer = Math.max(previewLimit, Math.min(10_000, subscriptions.size() * previewLimit));
            while (records.size() < maxRowsToBuffer && emptyPolls < EMPTY_POLL_LIMIT) {
                ScanRecords scanRecords = scanner.poll(java.time.Duration.ofSeconds(1));
                if (scanRecords.isEmpty()) {
                    emptyPolls++;
                    continue;
                }
                emptyPolls = 0;
                for (org.apache.fluss.metadata.TableBucket bucket : scanRecords.buckets()) {
                    for (ScanRecord record : scanRecords.records(bucket)) {
                        records.add(new PreviewRecord(bucket.getPartitionId(), bucket.getBucket(), record));
                    }
                }
            }
            return records.stream()
                    .sorted(
                            Comparator.comparingLong(PreviewRecord::timestamp)
                                    .thenComparingLong(PreviewRecord::offset)
                                    .reversed())
                    .limit(previewLimit)
                    .map(record -> previewRow(info, getters, record))
                    .toList();
        }
    }

    private Map<String, Object> previewRow(
            TableInfo info, InternalRow.FieldGetter[] getters, PreviewRecord previewRecord) {
        ScanRecord record = previewRecord.record();
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("_partition", previewRecord.partitionId());
        model.put("_bucket", previewRecord.bucket());
        model.put("_offset", record.logOffset());
        model.put("_timestamp", toIsoTime(record.timestamp()));
        model.put("_changeType", record.getChangeType().toString());

        InternalRow row = record.getRow();
        List<Schema.Column> columns = info.getSchema().getColumns();
        for (int i = 0; i < columns.size(); i++) {
            Object value = i < getters.length ? getters[i].getFieldOrNull(row) : null;
            model.put(columns.get(i).getName(), previewValue(value));
        }
        return model;
    }

    private List<Map<String, Object>> batchPreviewRows(
            Table table, Admin admin, TableInfo info, int previewLimit) throws Exception {
        InternalRow.FieldGetter[] getters = InternalRow.createFieldGetters(info.getRowType());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (TableBucket bucket : previewBuckets(admin, info)) {
            try (BatchScanner scanner = table.newScan().limit(previewLimit).createBatchScanner(bucket)) {
                int emptyPolls = 0;
                while (rows.size() < previewLimit && emptyPolls < EMPTY_POLL_LIMIT) {
                    org.apache.fluss.utils.CloseableIterator<InternalRow> batch =
                            scanner.pollBatch(java.time.Duration.ofSeconds(1));
                    if (batch == null) {
                        break;
                    }
                    try (batch) {
                        if (!batch.hasNext()) {
                            emptyPolls++;
                            continue;
                        }
                        emptyPolls = 0;
                        while (batch.hasNext() && rows.size() < previewLimit) {
                            rows.add(snapshotPreviewRow(info, getters, bucket, batch.next()));
                        }
                    }
                }
            } catch (RuntimeException exception) {
                if (rows.isEmpty()) {
                    throw exception;
                }
            }
            if (rows.size() >= previewLimit) {
                break;
            }
        }
        return rows;
    }

    private Map<String, Object> snapshotPreviewRow(
            TableInfo info, InternalRow.FieldGetter[] getters, TableBucket bucket, InternalRow row) {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("_partition", bucket == null ? null : bucket.getPartitionId());
        model.put("_bucket", bucket == null ? null : bucket.getBucket());
        model.put("_offset", null);
        model.put("_timestamp", null);
        model.put("_changeType", "SNAPSHOT");

        List<Schema.Column> columns = info.getSchema().getColumns();
        for (int i = 0; i < columns.size(); i++) {
            Object value = i < getters.length ? getters[i].getFieldOrNull(row) : null;
            model.put(columns.get(i).getName(), previewValue(value));
        }
        return model;
    }

    private List<TableBucket> previewBuckets(Admin admin, TableInfo info) {
        List<TableBucket> buckets = new ArrayList<>();
        if (info.isPartitioned()) {
            recentPartitions(admin, info).forEach(
                    partition -> addBuckets(buckets, info, partition.getPartitionId()));
        } else {
            addBuckets(buckets, info, null);
        }
        return buckets;
    }

    private void addBuckets(List<TableBucket> buckets, TableInfo info, Long partitionId) {
        for (int bucket = 0; bucket < info.getNumBuckets(); bucket++) {
            if (partitionId == null) {
                buckets.add(new TableBucket(info.getTableId(), bucket));
            } else {
                buckets.add(new TableBucket(info.getTableId(), partitionId, bucket));
            }
        }
    }

    private List<Map<String, String>> previewColumns(TableInfo info) {
        List<Map<String, String>> columns = new ArrayList<>();
        columns.add(Map.of("name", "_partition", "type", "metadata"));
        columns.add(Map.of("name", "_bucket", "type", "metadata"));
        columns.add(Map.of("name", "_offset", "type", "metadata"));
        columns.add(Map.of("name", "_timestamp", "type", "metadata"));
        columns.add(Map.of("name", "_changeType", "type", "metadata"));
        for (Schema.Column column : info.getSchema().getColumns()) {
            columns.add(Map.of("name", column.getName(), "type", column.getDataType().toString()));
        }
        return columns;
    }

    private List<Map<String, Object>> lookupRows(
            Table table, TableInfo info, List<QueryPredicate> predicates) {
        Map<String, QueryPredicate> predicateByColumn = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        predicates.forEach(predicate -> predicateByColumn.put(predicate.column(), predicate));
        Object[] keyValues =
                info.getPrimaryKeys().stream()
                        .map(
                                key -> {
                                    Schema.Column column = info.getSchema().getColumn(key);
                                    return typedValue(
                                            predicateByColumn.get(key).value(), column.getDataType());
                                })
                        .toArray();
        try {
            LookupResult result =
                    table.newLookup()
                            .createLookuper()
                            .lookup(org.apache.fluss.row.GenericRow.of(keyValues))
                            .join();
            InternalRow.FieldGetter[] getters = InternalRow.createFieldGetters(info.getRowType());
            return result.getRowList().stream()
                    .map(row -> snapshotPreviewRow(info, getters, null, row))
                    .toList();
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Primary key lookup failed", exception);
        }
    }

    private boolean canUsePrimaryKeyLookup(
            TableInfo info, List<QueryPredicate> predicates, String orderBy) {
        if (!info.hasPrimaryKey() || predicates.isEmpty()) {
            return false;
        }
        Map<String, QueryPredicate> predicateByColumn = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (QueryPredicate predicate : predicates) {
            if (!"=".equals(predicate.operator())) {
                return false;
            }
            predicateByColumn.put(predicate.column(), predicate);
        }
        return info.getPrimaryKeys().stream().allMatch(predicateByColumn::containsKey)
                && predicateByColumn.size() == info.getPrimaryKeys().size();
    }

    private List<Map<String, Object>> sortRows(
            List<Map<String, Object>> rows, String orderBy, String orderDir) {
        if (orderBy == null || orderBy.isBlank()) {
            return rows;
        }
        Comparator<Map<String, Object>> comparator =
                Comparator.comparing(row -> row.get(orderBy), SdkFlussMetadataService::compareValues);
        if ("DESC".equals(normalizedOrderDir(orderDir))) {
            comparator = comparator.reversed();
        }
        return rows.stream().sorted(comparator).toList();
    }

    private boolean matches(Map<String, Object> row, List<QueryPredicate> predicates) {
        return predicates.stream().allMatch(predicate -> matches(row, predicate));
    }

    private boolean matches(Map<String, Object> row, QueryPredicate predicate) {
        Object left = row.get(predicate.column());
        Object right = parseLiteral(predicate.value());
        int comparison = compareValues(left, right);
        return switch (predicate.operator()) {
            case "=" -> equalValues(left, right);
            case "!=" -> !equalValues(left, right);
            case ">" -> comparison > 0;
            case ">=" -> comparison >= 0;
            case "<" -> comparison < 0;
            case "<=" -> comparison <= 0;
            case "~" -> left != null
                    && String.valueOf(left)
                            .toLowerCase()
                            .contains(String.valueOf(right).toLowerCase());
            default -> false;
        };
    }

    private List<QueryPredicate> parseWhere(String where) {
        if (where == null || where.isBlank()) {
            return List.of();
        }
        List<QueryPredicate> predicates = new ArrayList<>();
        for (String expression : splitAnd(where)) {
            String trimmed = expression.trim();
            String operator = findOperator(trimmed);
            if (operator == null) {
                throw new IllegalArgumentException(
                        "Unsupported WHERE expression: " + trimmed);
            }
            int index = trimmed.indexOf(operator);
            String column = unquoteIdentifier(trimmed.substring(0, index).trim());
            String value = trimmed.substring(index + operator.length()).trim();
            if (column.isBlank() || value.isBlank()) {
                throw new IllegalArgumentException(
                        "Invalid WHERE expression: " + trimmed);
            }
            predicates.add(new QueryPredicate(column, operator, unquoteLiteral(value)));
        }
        return predicates;
    }

    private List<String> splitAnd(String where) {
        List<String> expressions = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuote = false;
        for (int i = 0; i < where.length(); i++) {
            char ch = where.charAt(i);
            if (ch == '\'') {
                inQuote = !inQuote;
                current.append(ch);
                continue;
            }
            if (!inQuote && regionMatchesAnd(where, i)) {
                expressions.add(current.toString());
                current.setLength(0);
                i += 4;
                continue;
            }
            current.append(ch);
        }
        expressions.add(current.toString());
        return expressions;
    }

    private static boolean regionMatchesAnd(String value, int index) {
        if (index + 5 > value.length()) {
            return false;
        }
        return value.regionMatches(true, index, " AND ", 0, 5);
    }

    private static String findOperator(String expression) {
        for (String operator : List.of(">=", "<=", "!=", "~", "=", ">", "<")) {
            if (expression.contains(operator)) {
                return operator;
            }
        }
        return null;
    }

    private static String unquoteIdentifier(String value) {
        if (value.startsWith("`") && value.endsWith("`") && value.length() >= 2) {
            return value.substring(1, value.length() - 1).replace("``", "`");
        }
        return value;
    }

    private static String unquoteLiteral(String value) {
        if (value.startsWith("'") && value.endsWith("'") && value.length() >= 2) {
            return value.substring(1, value.length() - 1).replace("''", "'");
        }
        return value;
    }

    private static Object typedValue(String value, DataType dataType) {
        Object literal = parseLiteral(value);
        if (literal == null) {
            return null;
        }
        DataTypeRoot root = dataType.getTypeRoot();
        if (root == DataTypeRoot.CHAR || root == DataTypeRoot.STRING) {
            return BinaryString.fromString(String.valueOf(literal));
        }
        if (root == DataTypeRoot.BOOLEAN) {
            return Boolean.parseBoolean(String.valueOf(literal));
        }
        if (root == DataTypeRoot.TINYINT) {
            return Byte.parseByte(String.valueOf(literal));
        }
        if (root == DataTypeRoot.SMALLINT) {
            return Short.parseShort(String.valueOf(literal));
        }
        if (root == DataTypeRoot.INTEGER || root == DataTypeRoot.DATE || root == DataTypeRoot.TIME_WITHOUT_TIME_ZONE) {
            return Integer.parseInt(String.valueOf(literal));
        }
        if (root == DataTypeRoot.BIGINT) {
            return Long.parseLong(String.valueOf(literal));
        }
        if (root == DataTypeRoot.FLOAT) {
            return Float.parseFloat(String.valueOf(literal));
        }
        if (root == DataTypeRoot.DOUBLE) {
            return Double.parseDouble(String.valueOf(literal));
        }
        if (root == DataTypeRoot.DECIMAL && dataType instanceof DecimalType decimalType) {
            return org.apache.fluss.row.Decimal.fromBigDecimal(
                    new java.math.BigDecimal(String.valueOf(literal)),
                    decimalType.getPrecision(),
                    decimalType.getScale());
        }
        if (root == DataTypeRoot.TIMESTAMP_WITHOUT_TIME_ZONE && dataType instanceof TimestampType) {
            return TimestampNtz.fromLocalDateTime(java.time.LocalDateTime.parse(String.valueOf(literal)));
        }
        if (root == DataTypeRoot.TIMESTAMP_WITH_LOCAL_TIME_ZONE && dataType instanceof LocalZonedTimestampType) {
            return TimestampLtz.fromInstant(java.time.Instant.parse(String.valueOf(literal)));
        }
        return literal;
    }

    private static Object parseLiteral(String value) {
        if (value == null || value.equalsIgnoreCase("null")) {
            return null;
        }
        if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false")) {
            return Boolean.parseBoolean(value);
        }
        try {
            if (value.matches("-?\\d+")) {
                return Long.parseLong(value);
            }
            if (value.matches("-?\\d+\\.\\d+")) {
                return Double.parseDouble(value);
            }
        } catch (NumberFormatException ignored) {
            return value;
        }
        return value;
    }

    private static int compareValues(Object left, Object right) {
        Object normalizedLeft = normalizedComparable(left);
        Object normalizedRight = normalizedComparable(right);
        if (normalizedLeft == null && normalizedRight == null) {
            return 0;
        }
        if (normalizedLeft == null) {
            return 1;
        }
        if (normalizedRight == null) {
            return -1;
        }
        if (normalizedLeft instanceof Number leftNumber && normalizedRight instanceof Number rightNumber) {
            return Double.compare(leftNumber.doubleValue(), rightNumber.doubleValue());
        }
        return String.valueOf(normalizedLeft).compareTo(String.valueOf(normalizedRight));
    }

    private static boolean equalValues(Object left, Object right) {
        Object normalizedLeft = normalizedComparable(left);
        Object normalizedRight = normalizedComparable(right);
        if (normalizedLeft instanceof Number && normalizedRight instanceof Number) {
            return compareValues(normalizedLeft, normalizedRight) == 0;
        }
        return Objects.equals(normalizedLeft, normalizedRight);
    }

    private static Object normalizedComparable(Object value) {
        if (value instanceof String stringValue) {
            return parseLiteral(stringValue);
        }
        return value;
    }

    private static String normalizedOrderDir(String orderDir) {
        return "DESC".equalsIgnoreCase(orderDir) ? "DESC" : "ASC";
    }

    private List<PreviewSubscription> previewSubscriptions(
            Admin admin, TableInfo info, int previewLimit) {
        List<Integer> buckets = new ArrayList<>();
        for (int bucket = 0; bucket < info.getNumBuckets(); bucket++) {
            buckets.add(bucket);
        }
        List<PreviewSubscription> subscriptions = new ArrayList<>();
        if (info.isPartitioned()) {
            for (PartitionInfo partition : recentPartitions(admin, info)) {
                subscriptions.addAll(
                        previewSubscriptions(
                                admin,
                                info.getTablePath(),
                                partition.getPartitionName(),
                                partition.getPartitionId(),
                                buckets,
                                previewLimit));
                if (subscriptions.size() >= MAX_PREVIEW_SUBSCRIPTIONS) {
                    return subscriptions.subList(0, MAX_PREVIEW_SUBSCRIPTIONS);
                }
            }
            return subscriptions;
        }
        return previewSubscriptions(admin, info.getTablePath(), null, null, buckets, previewLimit);
    }

    private List<PartitionInfo> recentPartitions(Admin admin, TableInfo info) {
        return admin.listPartitionInfos(info.getTablePath()).join().stream()
                .sorted(Comparator.comparing(PartitionInfo::getPartitionName).reversed())
                .limit(MAX_PREVIEW_PARTITIONS)
                .toList();
    }

    private List<PreviewSubscription> previewSubscriptions(
            Admin admin,
            TablePath path,
            String partitionName,
            Long partitionId,
            List<Integer> buckets,
            int previewLimit) {
        Map<Integer, Long> earliestOffsets =
                offsets(admin, path, partitionName, buckets, new OffsetSpec.EarliestSpec());
        Map<Integer, Long> latestOffsets =
                offsets(admin, path, partitionName, buckets, new OffsetSpec.LatestSpec());
        List<PreviewSubscription> subscriptions = new ArrayList<>();
        for (int bucket : buckets) {
            long earliest = earliestOffsets.getOrDefault(bucket, 0L);
            long latest = latestOffsets.getOrDefault(bucket, earliest);
            if (latest <= earliest) {
                continue;
            }
            long startOffset = Math.max(earliest, latest - previewLimit);
            subscriptions.add(new PreviewSubscription(partitionId, bucket, startOffset));
        }
        return subscriptions;
    }

    private Map<Integer, Long> offsets(
            Admin admin,
            TablePath path,
            String partitionName,
            List<Integer> buckets,
            OffsetSpec offsetSpec) {
        if (partitionName == null) {
            return admin.listOffsets(path, buckets, offsetSpec).all().join();
        }
        return admin.listOffsets(path, partitionName, buckets, offsetSpec).all().join();
    }

    private static int normalizePreviewLimit(int limit) {
        if (limit <= 0) {
            return DEFAULT_PREVIEW_LIMIT;
        }
        return Math.min(limit, MAX_PREVIEW_LIMIT);
    }

    private static Object previewValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BinaryString
                || value instanceof TimestampNtz
                || value instanceof TimestampLtz) {
            return value.toString();
        }
        if (value instanceof byte[] bytes) {
            return java.util.Base64.getEncoder().encodeToString(bytes);
        }
        return value;
    }


    @Override
    public OffsetSnapshot offsets(String clusterId, String database, String table) {
        return new LogInspector(connectionProvider).offsets(clusterId, database, table);
    }

    @Override
    public ReadResult readChangelog(String clusterId, String database, String table, ReadRequest request) {
        return new LogInspector(connectionProvider).read(clusterId, database, table, request);
    }

    private String ddl(TableInfo info) {
        List<String> definitions = new ArrayList<>();
        for (Schema.Column column : info.getSchema().getColumns()) {
            StringBuilder definition =
                    new StringBuilder()
                            .append("  ")
                            .append(identifier(column.getName()))
                            .append(" ")
                            .append(column.getDataType());
            column.getComment()
                    .filter(comment -> !comment.isBlank())
                    .ifPresent(comment -> definition.append(" COMMENT ").append(literal(comment)));
            definitions.add(definition.toString());
        }
        if (info.hasPrimaryKey()) {
            definitions.add(
                    "  PRIMARY KEY ("
                            + joinedIdentifiers(info.getPrimaryKeys())
                            + ") NOT ENFORCED");
        }

        StringBuilder sql =
                new StringBuilder()
                        .append("CREATE TABLE ")
                        .append(identifier(info.getTablePath().getDatabaseName()))
                        .append(".")
                        .append(identifier(info.getTablePath().getTableName()))
                        .append(" (\n")
                        .append(String.join(",\n", definitions))
                        .append("\n)");

        info.getComment()
                .filter(comment -> !comment.isBlank())
                .ifPresent(comment -> sql.append("\nCOMMENT ").append(literal(comment)));
        if (info.isPartitioned()) {
            sql.append("\nPARTITIONED BY (")
                    .append(joinedIdentifiers(info.getPartitionKeys()))
                    .append(")");
        }

        Map<String, String> options = ddlOptions(info);
        if (!options.isEmpty()) {
            sql.append("\nWITH (\n");
            List<String> optionLines = new ArrayList<>();
            options.forEach(
                    (key, value) ->
                            optionLines.add(
                                    "  " + literal(key) + " = " + literal(value)));
            sql.append(String.join(",\n", optionLines)).append("\n)");
        }
        return sql.append(";").toString();
    }

    private Map<String, String> ddlOptions(TableInfo info) {
        Map<String, String> options = new TreeMap<>(info.getProperties().toMap());
        options.putAll(info.getCustomProperties().toMap());
        if (info.getNumBuckets() > 0) {
            options.putIfAbsent("bucket.num", String.valueOf(info.getNumBuckets()));
        }
        if (info.hasBucketKey() && !info.isDefaultBucketKey()) {
            options.putIfAbsent("bucket.key", String.join(",", info.getBucketKeys()));
        }
        return options;
    }

    private static String joinedIdentifiers(List<String> names) {
        return names.stream().map(SdkFlussMetadataService::identifier).reduce((a, b) -> a + ", " + b).orElse("");
    }

    private static String identifier(String value) {
        return "`" + value.replace("`", "``") + "`";
    }

    private static String literal(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private static String toIsoTime(long epochMillis) {
        return epochMillis <= 0 ? "" : Instant.ofEpochMilli(epochMillis).toString();
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.toString() : current.getMessage();
    }

    @FunctionalInterface
    private interface AdminOperation<T> {
        T execute(Admin admin);
    }

    private record PreviewSubscription(Long partitionId, int bucket, long offset) {
        void subscribe(LogScanner scanner) {
            if (partitionId == null) {
                scanner.subscribe(bucket, offset);
            } else {
                scanner.subscribe(partitionId, bucket, offset);
            }
        }
    }

    private record PreviewRecord(Long partitionId, int bucket, ScanRecord record) {
        long timestamp() {
            return record.timestamp();
        }

        long offset() {
            return record.logOffset();
        }
    }

    private record QueryPredicate(String column, String operator, String value) {}

    /** Bounded, independent readers; all Fluss SDK access stays in this service. */
    private static final class LogInspector {
        private static final int MAX_BUCKETS = 256;
        private static final java.lang.reflect.Method SCAN_PROGRESS = scanProgressMethod();
        private final ClusterConnectionProvider connections;

        private LogInspector(ClusterConnectionProvider connections) {
            this.connections = connections;
        }

        public OffsetSnapshot offsets(String clusterId, String database, String tableName) {
            String startedAt = Instant.now().toString();
            long deadline = deadline(10_000);
            TablePath path = TablePath.of(database, tableName);
            try (Admin admin = connections.get(clusterId).getAdmin()) {
                TableInfo info = await(admin.getTableInfo(path), deadline);
                List<BucketOffset> buckets = bounds(admin, path, info, deadline).values().stream()
                        .map(b -> new BucketOffset(text(b.bucket.getPartitionId()), b.partitionName,
                                b.bucket.getBucket(), text(b.earliest), text(b.latest)))
                        .toList();
                return new OffsetSnapshot(clusterId, database, tableName, text(info.getTableId()),
                        startedAt, Instant.now().toString(), buckets);
            } catch (Exception exception) {
                throw failure(exception);
            }
        }

        public ReadResult read(String clusterId, String database, String tableName, ReadRequest request) {
            if (request == null || request.ranges() == null || request.ranges().size() > MAX_BUCKETS) {
                throw new IllegalArgumentException("Supply at most " + MAX_BUCKETS + " bucket ranges.");
            }
            int limit = bounded(request.limit(), 500, 1, 5000, "limit");
            int timeoutMs = bounded(request.timeoutMs(), 5000, 100, 30_000, "timeoutMs");
            long deadline = deadline(timeoutMs);
            Connection connection = connections.get(clusterId);
            TablePath path = TablePath.of(database, tableName);
            try (Admin admin = connection.getAdmin(); Table table = connection.getTable(path)) {
                TableInfo info = table.getTableInfo();
                if (request.tableId() != null && !request.tableId().equals(text(info.getTableId()))) {
                    throw new IllegalArgumentException("Table has changed; capture a new offset baseline.");
                }
                Map<TableBucket, Bounds> bounds = bounds(admin, path, info, deadline);
                Map<TableBucket, Cursor> cursors = ranges(request.ranges(), info, bounds);
                String capturedAt = Instant.now().toString();
                List<LogRow> rows = new ArrayList<>();
                if (!complete(cursors)) {
                    InternalRow.FieldGetter[] getters = InternalRow.createFieldGetters(info.getRowType());
                    try (LogScanner scanner = table.newScan().createLogScanner()) {
                        for (Cursor cursor : cursors.values()) {
                            if (!cursor.complete()) {
                                if (cursor.bounds.bucket.getPartitionId() == null) {
                                    scanner.subscribe(cursor.bounds.bucket.getBucket(), cursor.next);
                                } else {
                                    scanner.subscribe(cursor.bounds.bucket.getPartitionId(),
                                            cursor.bounds.bucket.getBucket(), cursor.next);
                                }
                            }
                        }
                        while (!complete(cursors) && rows.size() < limit && System.nanoTime() < deadline) {
                            long remaining = deadline - System.nanoTime();
                            if (remaining <= 0) break;
                            ScanRecords polled = scanner.poll(Duration.ofNanos(Math.min(remaining, 1_000_000_000L)));
                            consume(polled, cursors, info, getters, rows, limit);
                        }
                    }
                }
                String status = complete(cursors) ? "COMPLETE"
                        : rows.size() >= limit ? "TRUNCATED" : "TIMED_OUT";
                return new ReadResult(clusterId, database, tableName, text(info.getTableId()),
                        capturedAt, Instant.now().toString(), status, rows,
                        cursors.values().stream().map(Cursor::model).toList());
            } catch (Exception exception) {
                throw failure(exception);
            }
        }

        private static void consume(ScanRecords polled, Map<TableBucket, Cursor> cursors,
                TableInfo info, InternalRow.FieldGetter[] getters, List<LogRow> rows, int limit) {
            for (TableBucket bucket : polled.buckets()) {
                Cursor cursor = cursors.get(bucket);
                if (cursor == null || cursor.complete()) continue;
                boolean fullyConsumed = true;
                for (ScanRecord record : polled.records(bucket)) {
                    if (record.logOffset() < cursor.next) continue;
                    if (record.logOffset() >= cursor.end) {
                        cursor.next = cursor.end;
                        break;
                    }
                    if (rows.size() >= limit) {
                        fullyConsumed = false;
                        break;
                    }
                    Map<String, Object> values = new LinkedHashMap<>();
                    for (int i = 0; i < getters.length; i++) {
                        values.put(info.getSchema().getColumns().get(i).getName(),
                                value(getters[i].getFieldOrNull(record.getRow())));
                    }
                    rows.add(new LogRow(text(bucket.getPartitionId()), bucket.getBucket(),
                            text(record.logOffset()), text(record.timestamp()),
                            Instant.ofEpochMilli(record.timestamp()).toString(),
                            record.getChangeType().toString(), values));
                    cursor.next = record.logOffset() + 1;
                }
                // Scanner progress also covers gaps/control records. Never apply it past a
                // materialized row omitted because of the page limit.
                Long consumedUpTo = consumedUpTo(polled, bucket);
                if (fullyConsumed && consumedUpTo != null) {
                    cursor.next = Math.max(cursor.next, Math.min(consumedUpTo, cursor.end));
                }
            }
        }

        private static Map<TableBucket, Cursor> ranges(
                List<BucketRange> requested, TableInfo info, Map<TableBucket, Bounds> bounds) {
            Map<TableBucket, Cursor> cursors = new LinkedHashMap<>();
            for (BucketRange range : requested) {
                if (range == null) throw new IllegalArgumentException("Bucket range must not be null.");
                if (range.bucket() == null || range.bucket() < 0) {
                    throw new IllegalArgumentException("Each range must specify a non-negative bucket ID.");
                }
                Long partitionId = range.partitionId() == null ? null : offset(range.partitionId());
                TableBucket bucket = new TableBucket(info.getTableId(), partitionId, range.bucket());
                Bounds bound = bounds.get(bucket);
                if (bound == null || cursors.containsKey(bucket)) {
                    throw new IllegalArgumentException("Unknown or duplicate bucket range: " + bucket);
                }
                long start = offset(range.startOffset());
                long end = range.endOffset() == null ? bound.latest : offset(range.endOffset());
                if (start < bound.earliest || end > bound.latest || start > end) {
                    throw new IllegalArgumentException("Range must be within retained offsets ["
                            + bound.earliest + ", " + bound.latest + "] for " + bucket
                            + ". Capture a new baseline if logs have expired.");
                }
                cursors.put(bucket, new Cursor(bound, start, end));
            }
            return cursors;
        }

        private static Map<TableBucket, Bounds> bounds(
                Admin admin, TablePath path, TableInfo info, long deadline) throws Exception {
            List<PartitionInfo> partitions = info.isPartitioned()
                    ? await(admin.listPartitionInfos(path), deadline) : List.of();
            long bucketCount = (long) info.getNumBuckets() * (info.isPartitioned() ? partitions.size() : 1);
            if (bucketCount > MAX_BUCKETS) {
                throw new IllegalArgumentException("Table has " + bucketCount + " buckets; inspection supports at most "
                        + MAX_BUCKETS + ". No buckets were silently omitted.");
            }
            List<Integer> buckets = new ArrayList<>();
            for (int i = 0; i < info.getNumBuckets(); i++) buckets.add(i);
            Map<TableBucket, Bounds> result = new LinkedHashMap<>();
            if (info.isPartitioned()) {
                for (PartitionInfo partition : partitions) {
                    addBounds(admin, path, info.getTableId(), partition.getPartitionId(),
                            partition.getPartitionName(), buckets, result, deadline);
                }
            } else {
                addBounds(admin, path, info.getTableId(), null, null, buckets, result, deadline);
            }
            return result;
        }

        private static void addBounds(Admin admin, TablePath path, long tableId, Long partitionId,
                String partitionName, List<Integer> buckets, Map<TableBucket, Bounds> result, long deadline)
                throws Exception {
            CompletableFuture<Map<Integer, Long>> earliest = partitionId == null
                    ? admin.listOffsets(path, buckets, new OffsetSpec.EarliestSpec()).all()
                    : admin.listOffsets(path, partitionName, buckets, new OffsetSpec.EarliestSpec()).all();
            CompletableFuture<Map<Integer, Long>> latest = partitionId == null
                    ? admin.listOffsets(path, buckets, new OffsetSpec.LatestSpec()).all()
                    : admin.listOffsets(path, partitionName, buckets, new OffsetSpec.LatestSpec()).all();
            Map<Integer, Long> starts = await(earliest, deadline);
            Map<Integer, Long> ends = await(latest, deadline);
            for (int bucket : buckets) {
                if (!starts.containsKey(bucket) || !ends.containsKey(bucket)
                        || starts.get(bucket) < 0 || starts.get(bucket) > ends.get(bucket)) {
                    throw new IllegalStateException("Invalid offset response for bucket " + bucket);
                }
                TableBucket key = new TableBucket(tableId, partitionId, bucket);
                result.put(key, new Bounds(key, partitionName, starts.get(bucket), ends.get(bucket)));
            }
        }

        private static Object value(Object value) {
            if (value instanceof BinaryString || value instanceof TimestampNtz || value instanceof TimestampLtz
                    || value instanceof Decimal || value instanceof Long) return value.toString();
            if (value instanceof byte[] bytes) return Base64.getEncoder().encodeToString(bytes);
            return value;
        }

        private static boolean complete(Map<TableBucket, Cursor> cursors) {
            return cursors.values().stream().allMatch(Cursor::complete);
        }

        private static java.lang.reflect.Method scanProgressMethod() {
            try {
                return ScanRecords.class.getMethod("consumedUpToOffset", TableBucket.class);
            } catch (NoSuchMethodException exception) {
                return null;
            }
        }

        private static Long consumedUpTo(ScanRecords records, TableBucket bucket) {
            if (SCAN_PROGRESS == null) return null;
            try {
                return (Long) SCAN_PROGRESS.invoke(records, bucket);
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("Failed to read scanner progress", exception);
            }
        }

        private static String text(Object value) {
            return value == null ? null : value.toString();
        }

        private static long offset(String text) {
            if (text == null || !text.matches("[0-9]+")) {
                throw new IllegalArgumentException("Offsets/partition IDs must be non-negative decimal strings.");
            }
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("Offset/partition ID exceeds signed 64-bit range.");
            }
        }

        private static int bounded(Integer value, int fallback, int min, int max, String name) {
            int actual = value == null ? fallback : value;
            if (actual < min || actual > max) {
                throw new IllegalArgumentException(name + " must be between " + min + " and " + max + ".");
            }
            return actual;
        }

        private static long deadline(int timeoutMs) {
            return System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        }

        private static <T> T await(CompletableFuture<T> future, long deadline) throws Exception {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new TimeoutException("Offset capture timed out.");
            return future.get(remaining, TimeUnit.NANOSECONDS);
        }

        private static RuntimeException failure(Exception exception) {
            if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
            if (exception instanceof java.util.concurrent.ExecutionException && exception.getCause() != null) {
                return new IllegalStateException("Log inspection failed", exception.getCause());
            }
            return exception instanceof RuntimeException runtime ? runtime
                    : new IllegalStateException("Log inspection failed", exception);
        }

        private record Bounds(TableBucket bucket, String partitionName, long earliest, long latest) {}

        private static final class Cursor {
            private final Bounds bounds;
            private final long start;
            private final long end;
            private long next;

            private Cursor(Bounds bounds, long start, long end) {
                this.bounds = bounds;
                this.start = start;
                this.end = end;
                this.next = start;
            }

            private boolean complete() {
                return next >= end;
            }

            private BucketCursor model() {
                return new BucketCursor(text(bounds.bucket.getPartitionId()), bounds.partitionName,
                        bounds.bucket.getBucket(), text(start), text(end), text(next), complete());
            }
        }
    }

}
