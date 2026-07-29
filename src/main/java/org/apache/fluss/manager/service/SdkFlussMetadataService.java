package org.apache.fluss.manager.service;

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
                    try (org.apache.fluss.utils.CloseableIterator<InternalRow> batch =
                            scanner.pollBatch(java.time.Duration.ofSeconds(1))) {
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
}
