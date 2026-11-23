package com.sixbean.testpike.core.datafactory.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.audit.domain.component.OperatorContext;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.datafactory.api.dto.DataFactoryInfo;
import com.sixbean.testpike.core.datafactory.api.dto.DataFactoryQuery;
import com.sixbean.testpike.core.datafactory.domain.service.DataFactoryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Data-factory internal door contract : the ai-service
 * resource-pool segment reads this door WITHOUT an operator header, and the
 * list service ignores the operator (permissions are sealed admin-side, see
 * class javadoc) — a missing header must fold to system operator 0 exactly
 * like the occupy/release doors already do in this controller, never unfold
 * into an unboxing NPE that the global handler reports as a 500 "busy" page.
 */
class DataFactoryInternalControllerTest {

    private final DataFactoryService service = mock(DataFactoryService.class);
    private final DataFactoryInternalController controller =
            new DataFactoryInternalController(service);

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    private void requestWithOperator(String operatorHeaderOrNull) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (operatorHeaderOrNull != null) {
            request.addHeader(OperatorContext.OPERATOR_ID_HEADER, operatorHeaderOrNull);
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @Test
    void list_without_operator_header_folds_to_system_operator_zero() {
        requestWithOperator(null);
        when(service.list(anyLong(), any())).thenReturn(new PageResult<>(List.of(), 0));

        assertDoesNotThrow(() -> controller.list(3L, null, null, 1, 20, null, null));

        ArgumentCaptor<Long> operator = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<DataFactoryQuery> query = ArgumentCaptor.forClass(DataFactoryQuery.class);
        verify(service).list(operator.capture(), query.capture());
        assertEquals(0L, operator.getValue().longValue(),
                "absent header must record system operator 0 (occupy-door precedent)");
        assertEquals(3L, query.getValue().projectId());
    }

    @Test
    void list_with_operator_header_forwards_the_resolved_operator() {
        requestWithOperator("7");
        when(service.list(anyLong(), any())).thenReturn(new PageResult<>(List.of(), 0));

        Response<PageResult<DataFactoryInfo>> got =
                controller.list(3L, "kw", 2, 1, 20, null, null);

        assertTrue(got.isSuccess());
        verify(service).list(7L, new DataFactoryQuery(3L, "kw", 2, 1, 20, null, null));
    }
}
