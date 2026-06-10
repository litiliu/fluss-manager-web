package org.apache.fluss.manager.service;

import org.apache.fluss.manager.api.dto.AlterTableRequest;
import org.apache.fluss.manager.api.dto.CreateDatabaseRequest;
import org.apache.fluss.manager.api.dto.CreateTableRequest;
import java.util.List;
import java.util.Map;

public interface FlussMetadataService {

    Map<String, Object> health(String clusterId);

    List<Map<String, Object>> servers(String clusterId);

    List<String> databases(String clusterId);

    void createDatabase(String clusterId, CreateDatabaseRequest request);

    void dropDatabase(String clusterId, String database, boolean ignoreIfNotExists, boolean cascade);

    List<String> tables(String clusterId, String database);

    void createTable(String clusterId, String database, CreateTableRequest request);

    void alterTable(String clusterId, String database, String table, AlterTableRequest request);

    void dropTable(String clusterId, String database, String table, boolean ignoreIfNotExists);

    Map<String, Object> table(String clusterId, String database, String table);

    Map<String, Object> preview(String clusterId, String database, String table, int limit);

    Map<String, Object> query(
            String clusterId, String database, String table, String where, String orderBy, String orderDir, int limit);
}
