package org.apache.fluss.manager.api;

import org.apache.fluss.manager.api.dto.LogInspection.OffsetSnapshot;
import org.apache.fluss.manager.api.dto.LogInspection.ReadRequest;
import org.apache.fluss.manager.api.dto.LogInspection.ReadResult;
import org.apache.fluss.manager.service.FlussMetadataService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/clusters/{clusterId}/databases/{database}/tables/{table}")
public class LogInspectionController {
    private final FlussMetadataService service;

    public LogInspectionController(FlussMetadataService service) {
        this.service = service;
    }

    @GetMapping("/offsets")
    public OffsetSnapshot offsets(
            @PathVariable("clusterId") String clusterId,
            @PathVariable("database") String database,
            @PathVariable("table") String table) {
        return service.offsets(clusterId, database, table);
    }

    /** POST carries a read cursor; this operation never writes to Fluss. */
    @PostMapping("/changelog")
    public ReadResult changelog(
            @PathVariable("clusterId") String clusterId,
            @PathVariable("database") String database,
            @PathVariable("table") String table,
            @RequestBody ReadRequest request) {
        return service.readChangelog(clusterId, database, table, request);
    }
}
