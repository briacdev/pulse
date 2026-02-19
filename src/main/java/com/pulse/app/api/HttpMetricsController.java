package com.pulse.app.api;

import com.pulse.app.core.PulseRuntime;
import com.pulse.app.model.http.HttpSnapshot;
import com.pulse.app.model.http.HttpTraceDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/http")
public class HttpMetricsController {

    @GetMapping("/snapshot")
    public ResponseEntity<HttpSnapshot> snapshot() {
        return ResponseEntity.ok(PulseRuntime.getHttpCollector().snapshot());
    }

    @GetMapping("/trace/{id}")
    public ResponseEntity<HttpTraceDetail> trace(@PathVariable String id) {
        HttpTraceDetail detail = PulseRuntime.getHttpCollector().traceDetail(id);
        if (detail == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(detail);
    }
}
