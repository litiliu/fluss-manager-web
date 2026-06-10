package org.apache.fluss.manager.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.apache.fluss.manager.config.ClusterConnectionProvider;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.apache.fluss.client.Connection;
import org.apache.fluss.client.admin.Admin;
import org.apache.fluss.client.lookup.Lookup;
import org.apache.fluss.client.lookup.LookupResult;
import org.apache.fluss.client.lookup.Lookuper;
import org.apache.fluss.client.table.Table;
import org.apache.fluss.config.Configuration;
import org.apache.fluss.metadata.Schema;
import org.apache.fluss.metadata.TableInfo;
import org.apache.fluss.metadata.TablePath;
import org.apache.fluss.row.InternalRow;
import org.apache.fluss.types.DataTypes;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SdkFlussMetadataServiceTest {

    @Test
    void fullPrimaryKeyQueryUsesPrimaryKeyLookuperWithoutLookupByEvenWithOrderBy() {
        Connection connection = mock(Connection.class);
        Admin admin = mock(Admin.class);
        Table table = mock(Table.class);
        Lookup lookup = mock(Lookup.class);
        Lookuper lookuper = mock(Lookuper.class);
        TableInfo info = tableInfo();

        when(connection.getAdmin()).thenReturn(admin);
        when(connection.getTable(TablePath.of("db", "orders"))).thenReturn(table);
        when(table.getTableInfo()).thenReturn(info);
        when(table.newLookup()).thenReturn(lookup);
        when(lookup.createLookuper()).thenReturn(lookuper);
        when(lookuper.lookup(any(InternalRow.class)))
                .thenReturn(CompletableFuture.completedFuture(new LookupResult(List.of())));
        ClusterConnectionProvider connectionProvider = mock(ClusterConnectionProvider.class);
        when(connectionProvider.get("cluster-1")).thenReturn(connection);

        SdkFlussMetadataService service = new SdkFlussMetadataService(connectionProvider);

        Map<String, Object> result =
                service.query("cluster-1", "db", "orders", "id = 'order-1'", "_timestamp", "DESC", 100);

        assertThat(result.get("mode")).isEqualTo("primary-key-lookup");
        verify(lookup, never()).lookupBy(any(List.class));
        ArgumentCaptor<InternalRow> keyCaptor = ArgumentCaptor.forClass(InternalRow.class);
        verify(lookuper).lookup(keyCaptor.capture());
        assertThat(keyCaptor.getValue().getString(0).toString()).isEqualTo("order-1");
    }

    private static TableInfo tableInfo() {
        Schema schema =
                Schema.newBuilder()
                        .column("id", DataTypes.STRING())
                        .column("status", DataTypes.STRING())
                        .primaryKey("id")
                        .build();
        return new TableInfo(
                TablePath.of("db", "orders"),
                1L,
                1,
                schema,
                List.of("id"),
                List.of(),
                1,
                new Configuration(),
                new Configuration(),
                "",
                1L,
                1L);
    }

}
