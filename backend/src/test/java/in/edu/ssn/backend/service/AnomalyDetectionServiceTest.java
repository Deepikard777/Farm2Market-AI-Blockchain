package in.edu.ssn.backend.service;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class AnomalyDetectionServiceTest {

    @Test
    void testFallbackPolicySuspiciousWhenServiceDown() {
        AnomalyDetectionService service = new AnomalyDetectionService();
        // Point to an invalid port to simulate ML microservice downtime
        ReflectionTestUtils.setField(service, "mlServiceUrl", "http://127.0.0.1:59999");
        ReflectionTestUtils.setField(service, "fallbackPolicy", "SUSPICIOUS");

        AnomalyDetectionService.AnomalyRequest req = new AnomalyDetectionService.AnomalyRequest();
        req.batchId = "BATCH-TEST-001";
        req.nodeRole = "TRANSPORTER";
        req.eventType = "SCAN";
        req.eventTime = "2026-10-20T12:00:00Z";

        AnomalyDetectionService.AnomalyResult result = service.checkAnomaly(req);

        assertNotNull(result);
        assertEquals("SUSPICIOUS", result.decision);
        assertTrue(result.requiresReview);
        assertTrue(result.anomalyScore >= 0.5);
        assertFalse(result.signals.isEmpty());
        assertTrue(result.signals.get(0).contains("Fallback-security-hold"));
    }

    @Test
    void testFallbackPolicyNormalWhenConfiguredPermissive() {
        AnomalyDetectionService service = new AnomalyDetectionService();
        ReflectionTestUtils.setField(service, "mlServiceUrl", "http://127.0.0.1:59999");
        ReflectionTestUtils.setField(service, "fallbackPolicy", "NORMAL");

        AnomalyDetectionService.AnomalyRequest req = new AnomalyDetectionService.AnomalyRequest();
        req.batchId = "BATCH-TEST-002";
        req.nodeRole = "TRANSPORTER";
        req.eventType = "SCAN";
        req.eventTime = "2026-10-20T12:00:00Z";

        AnomalyDetectionService.AnomalyResult result = service.checkAnomaly(req);

        assertNotNull(result);
        assertEquals("NORMAL", result.decision);
        assertFalse(result.requiresReview);
        assertEquals(0.0, result.anomalyScore);
    }
}
