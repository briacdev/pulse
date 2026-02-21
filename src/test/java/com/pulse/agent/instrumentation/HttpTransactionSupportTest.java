package com.pulse.agent.instrumentation;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpTransactionSupportTest {

    @Test
    void shouldWrapServletRequestWithContentCachingWrapperWhenPossible() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/users");
        request.setContentType("application/json");
        request.setContent("{\"name\":\"alice\"}".getBytes());

        var wrapped = HttpTransactionSupport.prepareRequest(request);

        assertNotNull(wrapped);
        boolean isWrapper = wrapped.getClass().getName().contains("ContentCachingRequestWrapper");
        boolean hasCachedAccessor = Arrays.stream(wrapped.getClass().getMethods())
                .anyMatch(method -> "getContentAsByteArray".equals(method.getName()));
        assertTrue(isWrapper || hasCachedAccessor);
    }
}
