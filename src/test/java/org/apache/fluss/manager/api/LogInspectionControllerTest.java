package org.apache.fluss.manager.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.apache.fluss.manager.api.dto.LogInspection.BucketCursor;
import org.apache.fluss.manager.api.dto.LogInspection.BucketOffset;
import org.apache.fluss.manager.api.dto.LogInspection.OffsetSnapshot;
import org.apache.fluss.manager.api.dto.LogInspection.ReadRequest;
import org.apache.fluss.manager.api.dto.LogInspection.ReadResult;
import org.apache.fluss.manager.service.FlussMetadataService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class LogInspectionControllerTest {
    private static final String BASE = "/api/clusters/JFK/databases/it/tables/events";
    private final FlussMetadataService service = mock(FlussMetadataService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new LogInspectionController(service))
            .setControllerAdvice(new ApiExceptionHandler()).build();

    @Test
    void offsetApiSerializesTheFullDecimalPrecision() throws Exception {
        when(service.offsets("JFK", "it", "events")).thenReturn(new OffsetSnapshot(
                "JFK", "it", "events", "7", "start", "sample",
                List.of(new BucketOffset(null, null, 0, "9007199254740993", "9007199254740994"))));
        mvc.perform(get(BASE + "/offsets")).andExpect(status().isOk())
                .andExpect(jsonPath("$.buckets[0].earliestOffset").value("9007199254740993"));
    }

    @Test
    void readOnlyPostPreservesRangeStringsAndReturnsContinuationStatus() throws Exception {
        when(service.readChangelog(eq("JFK"), eq("it"), eq("events"), any(ReadRequest.class)))
                .thenAnswer(invocation -> {
                    ReadRequest request = invocation.getArgument(3);
                    var range = request.ranges().get(0);
                    return new ReadResult("JFK", "it", "events", request.tableId(), "captured", "finished",
                            "TIMED_OUT", List.of(), List.of(new BucketCursor(null, null, 0,
                            range.startOffset(), range.endOffset(), range.startOffset(), false)));
                });
        mvc.perform(post(BASE + "/changelog").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tableId":"7","ranges":[{"bucket":0,"startOffset":"9007199254740993",
                                "endOffset":"9007199254740999"}],"limit":500,"timeoutMs":1000}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("TIMED_OUT"))
                .andExpect(jsonPath("$.cursors[0].nextOffset").value("9007199254740993"))
                .andExpect(jsonPath("$.cursors[0].endOffset").value("9007199254740999"));
    }

    @Test
    void invalidReadRangesUseTheExistingBadRequestContract() throws Exception {
        when(service.readChangelog(eq("JFK"), eq("it"), eq("events"), any(ReadRequest.class)))
                .thenThrow(new IllegalArgumentException("Range has expired"));
        mvc.perform(post(BASE + "/changelog").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tableId\":\"7\",\"ranges\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Range has expired"));
    }
}
