package org.apache.fluss.manager.api.dto;

import java.util.List;
import java.util.Map;

public record AlterTableRequest(
        List<AddColumnRequest> addColumns,
        List<ModifyColumnRequest> modifyColumns,
        List<RenameColumnRequest> renameColumns,
        List<String> dropColumns,
        Map<String, String> setOptions,
        List<String> resetOptions) {

    public record AddColumnRequest(String name, String type, String comment, String afterColumn, Boolean first) {}

    public record ModifyColumnRequest(String name, String type, String comment, String afterColumn, Boolean first) {}

    public record RenameColumnRequest(String oldName, String newName) {}
}
