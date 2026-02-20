package com.pulse.app.api;

import com.pulse.app.core.PulseRuntime;
import com.pulse.app.model.jvm.JvmSnapshot;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/jvm")
public class JvmMetricsController {

    @GetMapping("/snapshot")
    public ResponseEntity<JvmSnapshot> snapshot() {
        JvmSnapshot snapshot = PulseRuntime.getJvmMetrics().snapshot(PulseRuntime.getHttpCollector());
        return ResponseEntity.ok(snapshot);
    }
}
