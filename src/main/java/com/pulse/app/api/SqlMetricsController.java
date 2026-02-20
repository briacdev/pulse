package com.pulse.app.api;

import com.pulse.app.core.PulseRuntime;
import com.pulse.app.model.SqlSnapshot;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/sql")
public class SqlMetricsController {

    @GetMapping("/snapshot")
    public ResponseEntity<SqlSnapshot> snapshot() {
        return ResponseEntity.ok(PulseRuntime.getCollector().snapshot());
    }

    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> config() {
        var config = PulseRuntime.getConfig();
        return ResponseEntity.ok(Map.of(
                "port", config.port(),
                "retentionMs", config.retentionMs(),
                "sampleRate", config.sampleRate(),
                "slowQueryThresholdMs", config.slowQueryThresholdMs(),
                "slowHttpThresholdMs", config.slowHttpThresholdMs(),
                "bindAddress", config.bindAddress(),
                "appName", PulseRuntime.monitoredAppName()
        ));
    }
}
