package in.edu.ssn.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

/**
 * Calls the Flask ML microservice's /detect-anomaly endpoint before a supply-chain
 * event is committed to the blockchain.
 *
 * Decision levels:
 *   NORMAL     (score < 0.5)  → proceed normally
 *   SUSPICIOUS (0.5 ≤ score < 0.9) → flag in DB, skip blockchain until reviewed
 *   REJECT     (score ≥ 0.9)  → discard event entirely
 *
 * Fail-safe: if Flask is unavailable, returns NORMAL so the supply chain is never
 * blocked by ML-service downtime.
 */
@Service
public class AnomalyDetectionService {

    @Value("${ml.service.url:http://localhost:5001}")
    private String mlServiceUrl;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ── Public API ────────────────────────────────────────────────────

    /**
     * Full anomaly check result returned to callers.
     */
    public static class AnomalyResult {
        public final String decision;          // NORMAL | SUSPICIOUS | REJECT
        public final double anomalyScore;      // 0.0 – 1.0
        public final List<String> signals;     // human-readable signal descriptions
        public final boolean requiresReview;

        public AnomalyResult(String decision, double anomalyScore, List<String> signals) {
            this.decision = decision;
            this.anomalyScore = anomalyScore;
            this.signals = signals;
            this.requiresReview = "SUSPICIOUS".equals(decision);
        }

        /** Convenience: is this event safe to record on-chain? */
        public boolean isNormal() { return "NORMAL".equals(decision); }
    }

    /**
     * Payload we send to Flask for evaluation.
     */
    public static class AnomalyRequest {
        public String batchId;
        public String nodeId;
        public String nodeRole;
        public String eventType;
        public String eventTime;
        public String location;
        public String operatorId;
        public String operatorName;
        public String productStatus;          // current product status in DB
        public String productCreatedAt;       // batch creation time for timestamp validation
        public int    existingEventCount;     // number of events already in journey
        public List<Map<String, Object>> recentScanEvents; // last N scan_events from DB
    }

    /**
     * Main entry point. Calls Flask; falls back to NORMAL on any error.
     */
    public AnomalyResult checkAnomaly(AnomalyRequest request) {
        try {
            String requestBody = objectMapper.writeValueAsString(request);

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(mlServiceUrl + "/detect-anomaly"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(
                    httpRequest, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                @SuppressWarnings("unchecked")
                Map<String, Object> body = objectMapper.readValue(response.body(), Map.class);
                return parseFlaskResponse(body);
            } else {
                System.err.println("[Anomaly] Flask returned HTTP " + response.statusCode() + " — defaulting to NORMAL");
                return normalResult("Flask returned non-200 status");
            }

        } catch (Exception e) {
            System.err.println("[Anomaly] Detection service unavailable: " + e.getMessage() + " — defaulting to NORMAL (fail-safe)");
            return normalResult("Detection service unavailable");
        }
    }

    // ── Private helpers ───────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private AnomalyResult parseFlaskResponse(Map<String, Object> body) {
        String decision = (String) body.getOrDefault("decision", "NORMAL");
        double score = body.get("anomaly_score") instanceof Number
                ? ((Number) body.get("anomaly_score")).doubleValue()
                : 0.0;
        List<String> signals = body.get("signals") instanceof List
                ? (List<String>) body.get("signals")
                : Collections.emptyList();
        return new AnomalyResult(decision, score, signals);
    }

    private AnomalyResult normalResult(String reason) {
        return new AnomalyResult("NORMAL", 0.0, List.of("Fail-safe: " + reason));
    }
}
