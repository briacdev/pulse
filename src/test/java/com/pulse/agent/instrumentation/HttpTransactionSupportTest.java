package com.pulse.agent.instrumentation;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class HttpTransactionSupportTest {

    @Test
    void prepareRequestShouldReturnNullWhenRequestIsNull() {
        assertNull(HttpTransactionSupport.prepareRequest(null));
    }

    @Test
    void prepareRequestShouldKeepRequestWhenBodyIsAlreadyCached() {
        CachedBodyRequest request = new CachedBodyRequest("{\"name\":\"alice\"}".getBytes(StandardCharsets.UTF_8));
        Object prepared = HttpTransactionSupport.prepareRequest(request);
        assertSame(request, prepared);
    }

    static final class CachedBodyRequest {
        private final byte[] body;

        CachedBodyRequest(byte[] body) {
            this.body = body;
        }

        public byte[] getContentAsByteArray() {
            return body;
        }
    }
}
