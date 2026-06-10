package org.apache.fluss.manager.api;

import org.apache.fluss.manager.api.dto.AlterTableRequest;
import org.apache.fluss.manager.api.dto.CreateDatabaseRequest;
import org.apache.fluss.manager.api.dto.CreateTableRequest;
import org.apache.fluss.manager.service.FlussMetadataService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/clusters/{clusterId}")
public class FlussController {

    private final FlussMetadataService metadataService;

    public FlussController(FlussMetadataService metadataService) {
        this.metadataService = metadataService;
    }

    @GetMapping("/health")
    public Map<String, Object> health(@PathVariable("clusterId") String clusterId) {
        return metadataService.health(clusterId);
    }

    @GetMapping("/servers")
    public List<Map<String, Object>> servers(@PathVariable("clusterId") String clusterId) {
        return metadataService.servers(clusterId);
    }

    @GetMapping("/databases")
    public List<String> databases(@PathVariable("clusterId") String clusterId) {
        return metadataService.databases(clusterId);
    }

    @PostMapping("/databases")
    public void createDatabase(
            @PathVariable("clusterId") String clusterId, @RequestBody CreateDatabaseRequest request) {
        metadataService.createDatabase(clusterId, request);
    }

    @DeleteMapping("/databases/{database}")
    public void dropDatabase(
            @PathVariable("clusterId") String clusterId,
            @PathVariable("database") String database,
            @RequestParam(name = "ignoreIfNotExists", defaultValue = "true") boolean ignoreIfNotExists,
            @RequestParam(name = "cascade", defaultValue = "false") boolean cascade) {
        metadataService.dropDatabase(clusterId, database, ignoreIfNotExists, cascade);
    }

    @GetMapping("/databases/{database}/tables")
    public List<String> tables(@PathVariable("clusterId") String clusterId, @PathVariable("database") String database) {
        return metadataService.tables(clusterId, database);
    }

    @PostMapping("/databases/{database}/tables")
    public void createTable(
            @PathVariable("clusterId") String clusterId,
            @PathVariable("database") String database,
            @RequestBody CreateTableRequest request) {
        metadataService.createTable(clusterId, database, request);
    }

    @PatchMapping("/databases/{database}/tables/{table}")
    public void alterTable(
            @PathVariable("clusterId") String clusterId,
            @PathVariable("database") String database,
            @PathVariable("table") String table,
            @RequestBody AlterTableRequest request) {
        metadataService.alterTable(clusterId, database, table, request);
    }

    @DeleteMapping("/databases/{database}/tables/{table}")
    public void dropTable(
            @PathVariable("clusterId") String clusterId,
            @PathVariable("database") String database,
            @PathVariable("table") String table,
            @RequestParam(name = "ignoreIfNotExists", defaultValue = "true") boolean ignoreIfNotExists) {
        metadataService.dropTable(clusterId, database, table, ignoreIfNotExists);
    }

    @GetMapping("/databases/{database}/tables/{table}")
    public Map<String, Object> table(
            @PathVariable("clusterId") String clusterId,
            @PathVariable("database") String database,
            @PathVariable("table") String table) {
        return metadataService.table(clusterId, database, table);
    }

    @GetMapping("/databases/{database}/tables/{table}/preview")
    public Map<String, Object> preview(
            @PathVariable("clusterId") String clusterId,
            @PathVariable("database") String database,
            @PathVariable("table") String table,
            @RequestParam(name = "limit", defaultValue = "100") int limit) {
        return metadataService.preview(clusterId, database, table, limit);
    }

    @GetMapping("/databases/{database}/tables/{table}/query")
    public Map<String, Object> query(
            @PathVariable("clusterId") String clusterId,
            @PathVariable("database") String database,
            @PathVariable("table") String table,
            @RequestParam(name = "where", defaultValue = "") String where,
            @RequestParam(name = "orderBy", defaultValue = "") String orderBy,
            @RequestParam(name = "orderDir", defaultValue = "ASC") String orderDir,
            @RequestParam(name = "limit", defaultValue = "100") int limit) {
        return metadataService.query(clusterId, database, table, where, orderBy, orderDir, limit);
    }
}
