package in.edu.ssn.backend.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.edu.ssn.backend.entity.AnomalyFlagEntity;
import in.edu.ssn.backend.entity.NotificationEntity;
import in.edu.ssn.backend.entity.ProductEntity;
import in.edu.ssn.backend.entity.ScanEventEntity;
import in.edu.ssn.backend.repository.AnomalyFlagRepository;
import in.edu.ssn.backend.repository.NotificationRepository;
import in.edu.ssn.backend.repository.ProductRepository;
import in.edu.ssn.backend.repository.ScanEventRepository;
import in.edu.ssn.backend.service.AnomalyDetectionService;
import in.edu.ssn.backend.service.BlockchainService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

/**
 * REST controller for receiving scan events from Raspberry Pi edge nodes.
 *
 * Each Pi is configured with a ROLE (FARMER / TRANSPORTER / SELLER).
 * When a scan event arrives, this controller:
 * 1. Stores the raw scan event
 * 2. Updates the product's supply chain status based on the role:
 * - TRANSPORTER → status = IN_TRANSIT, transporterId/Name set
 * - SELLER → status = AT_SELLER, sellerId/Name set
 * - FARMER → records dispatch, currentLocation updated
 * 3. Appends a journey step to the product's journey
 */
@RestController
@RequestMapping("/api/edge")
public class EdgeSyncController {

    @Autowired
    private ScanEventRepository scanEventRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private BlockchainService blockchainService;

    @Autowired
    private AnomalyDetectionService anomalyDetectionService;

    @Autowired
    private AnomalyFlagRepository anomalyFlagRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    // ─── Single event sync ───────────────────────────────────────────

    @PostMapping("/sync")
    public ResponseEntity<Map<String, Object>> syncEvent(@RequestBody Map<String, String> payload) {
        System.out.println("[EdgeSync] Received scan event: " + payload);
        Map<String, Object> result = processSingleSyncEvent(payload);

        if (Boolean.TRUE.equals(result.get("badRequest"))) {
            return ResponseEntity.badRequest().body(result);
        }
        if ("rejected".equals(result.get("status"))) {
            return ResponseEntity.status(409).body(result);
        }
        return ResponseEntity.ok(result);
    }

    // ─── Batch sync (multiple events) ────────────────────────────────

    @PostMapping("/sync/batch")
    public ResponseEntity<Map<String, Object>> syncBatch(@RequestBody List<Map<String, String>> events) {
        System.out.println("[EdgeSync] Received batch of " + events.size() + " events");

        int successCount = 0;
        int failCount = 0;
        int rejectedCount = 0;
        int suspiciousCount = 0;
        int normalCount = 0;
        List<Long> syncedIds = new ArrayList<>();
        List<Map<String, Object>> eventResults = new ArrayList<>();

        for (Map<String, String> payload : events) {
            try {
                Map<String, Object> result = processSingleSyncEvent(payload);
                eventResults.add(result);

                String status = (String) result.get("status");
                String decision = (String) result.get("decision");

                if ("synced".equals(status)) {
                    successCount++;
                    if (result.get("id") instanceof Long) {
                        syncedIds.add((Long) result.get("id"));
                    }
                    if ("SUSPICIOUS".equals(decision)) {
                        suspiciousCount++;
                    } else {
                        normalCount++;
                    }
                } else if ("rejected".equals(status)) {
                    rejectedCount++;
                } else {
                    failCount++;
                }
            } catch (Exception e) {
                System.err.println("[EdgeSync] Error processing event in batch: " + e.getMessage());
                failCount++;
                Map<String, Object> errResult = new HashMap<>();
                errResult.put("status", "failed");
                errResult.put("error", e.getMessage() != null ? e.getMessage() : "Unknown error");
                errResult.put("batchId", payload.getOrDefault("batchId", ""));
                eventResults.add(errResult);
            }
        }

        Map<String, Object> response = new HashMap<>();
        response.put("synced", successCount);
        response.put("failed", failCount + rejectedCount);
        response.put("rejected", rejectedCount);
        response.put("suspicious", suspiciousCount);
        response.put("normal", normalCount);
        response.put("syncedIds", syncedIds);
        response.put("results", eventResults);

        return ResponseEntity.ok(response);
    }

    /**
     * Core processing logic for an individual scan event with full AI anomaly gating.
     * Shared by both /sync and /sync/batch endpoints.
     */
    private Map<String, Object> processSingleSyncEvent(Map<String, String> payload) {
        Map<String, Object> result = new HashMap<>();

        String batchId = payload.get("batchId");
        String nodeId = payload.get("nodeId");
        String eventType = payload.getOrDefault("eventType", "SCAN");
        String eventTime = payload.get("eventTime");
        String nodeRole = payload.getOrDefault("nodeRole", "");
        String operatorId = payload.getOrDefault("operatorId", "");
        String operatorName = payload.getOrDefault("operatorName", "");
        String location = payload.getOrDefault("location", "");
        String tempStr = payload.get("temperature");
        String humidityStr = payload.get("humidity");

        if (batchId == null || nodeId == null || eventTime == null) {
            result.put("status", "error");
            result.put("badRequest", true);
            result.put("error", "Missing required fields: batchId, nodeId, eventTime");
            if (batchId != null) result.put("batchId", batchId);
            return result;
        }

        result.put("batchId", batchId);
        result.put("nodeId", nodeId);
        result.put("nodeRole", nodeRole);
        result.put("operatorName", operatorName);
        result.put("eventTime", eventTime);

        // 1. Fetch current product for validation context
        Optional<ProductEntity> optProduct = productRepository.findById(batchId);
        ProductEntity product = optProduct.orElse(null);

        // 2. Pre-Blockchain AI Anomaly Detection
        AnomalyDetectionService.AnomalyRequest anomalyReq = new AnomalyDetectionService.AnomalyRequest();
        anomalyReq.batchId = batchId;
        anomalyReq.nodeId = nodeId;
        anomalyReq.nodeRole = nodeRole;
        anomalyReq.eventType = eventType;
        anomalyReq.eventTime = eventTime;
        anomalyReq.location = location;
        anomalyReq.operatorId = operatorId;
        anomalyReq.operatorName = operatorName;

        if (product != null) {
            anomalyReq.productStatus = product.getStatus();
            anomalyReq.productCreatedAt = product.getCreatedAt();
            if (product.getJourney() != null && !product.getJourney().isBlank()) {
                try {
                    List<?> existingJourney = objectMapper.readValue(product.getJourney(), List.class);
                    anomalyReq.existingEventCount = existingJourney.size();
                } catch (Exception ignored) {}
            }
        }

        // Fetch recent scan events for burst/duplicate detection
        List<ScanEventEntity> recentEvents = scanEventRepository.findByBatchId(batchId);
        if (recentEvents != null && !recentEvents.isEmpty()) {
            List<Map<String, Object>> recentList = new ArrayList<>();
            int startIdx = Math.max(0, recentEvents.size() - 10);
            for (int i = startIdx; i < recentEvents.size(); i++) {
                ScanEventEntity se = recentEvents.get(i);
                Map<String, Object> map = new HashMap<>();
                map.put("nodeId", se.getNodeId());
                map.put("eventType", se.getEventType());
                map.put("eventTime", se.getEventTime());
                recentList.add(map);
            }
            anomalyReq.recentScanEvents = recentList;
        }

        AnomalyDetectionService.AnomalyResult anomalyResult = anomalyDetectionService.checkAnomaly(anomalyReq);
        result.put("anomalyDecision", anomalyResult.decision);
        result.put("anomalyScore", anomalyResult.anomalyScore);
        result.put("signals", anomalyResult.signals);

        // 3. Handle REJECT Decision: Event is completely rejected before recording
        if ("REJECT".equals(anomalyResult.decision)) {
            System.err.println("[Anomaly] Event REJECTED for batch " + batchId + ": " + anomalyResult.signals);

            AnomalyFlagEntity flag = new AnomalyFlagEntity();
            flag.setId(UUID.randomUUID().toString());
            flag.setBatchId(batchId);
            flag.setNodeId(nodeId);
            flag.setNodeRole(nodeRole);
            flag.setEventType(eventType);
            flag.setEventTime(eventTime);
            flag.setLocation(location);
            flag.setOperatorId(operatorId);
            flag.setOperatorName(operatorName);
            flag.setAnomalyScore(anomalyResult.anomalyScore);
            try {
                flag.setSignals(objectMapper.writeValueAsString(anomalyResult.signals));
            } catch (Exception ignored) {}
            flag.setDecision("REJECT");
            flag.setReviewStatus("REJECTED");
            flag.setCreatedAt(Instant.now().toString());
            anomalyFlagRepository.save(flag);

            result.put("status", "rejected");
            result.put("decision", "REJECT");
            result.put("error", "Event rejected by AI anomaly detection");
            result.put("flagId", flag.getId());
            return result;
        }

        // 4. Persist the scan event
        ScanEventEntity scanEvent = new ScanEventEntity();
        scanEvent.setBatchId(batchId);
        scanEvent.setNodeId(nodeId);
        scanEvent.setEventType(eventType);
        scanEvent.setEventTime(eventTime);
        scanEvent.setReceivedAt(Instant.now().toString());
        scanEvent.setNodeRole(nodeRole);
        scanEvent.setLocation(location);
        scanEvent.setOperatorId(operatorId);
        scanEvent.setOperatorName(operatorName);
        if (tempStr != null) {
            try { scanEvent.setTemperature(Double.parseDouble(tempStr)); } catch (Exception ignored) {}
        }
        if (humidityStr != null) {
            try { scanEvent.setHumidity(Double.parseDouble(humidityStr)); } catch (Exception ignored) {}
        }

        ScanEventEntity saved = scanEventRepository.save(scanEvent);
        result.put("id", saved.getId());
        result.put("receivedAt", saved.getReceivedAt());

        // 5. Handle SUSPICIOUS Decision vs NORMAL Decision
        boolean isSuspicious = "SUSPICIOUS".equals(anomalyResult.decision);
        String statusUpdate = null;
        String txHash = null;

        if (isSuspicious) {
            System.out.println("[Anomaly] Event SUSPICIOUS for batch " + batchId + ", score=" + anomalyResult.anomalyScore + " - Skipping blockchain");

            // Persist Anomaly Flag
            AnomalyFlagEntity flag = new AnomalyFlagEntity();
            flag.setId(UUID.randomUUID().toString());
            flag.setBatchId(batchId);
            flag.setNodeId(nodeId);
            flag.setNodeRole(nodeRole);
            flag.setEventType(eventType);
            flag.setEventTime(eventTime);
            flag.setLocation(location);
            flag.setOperatorId(operatorId);
            flag.setOperatorName(operatorName);
            flag.setAnomalyScore(anomalyResult.anomalyScore);
            try {
                flag.setSignals(objectMapper.writeValueAsString(anomalyResult.signals));
            } catch (Exception ignored) {}
            flag.setDecision("SUSPICIOUS");
            flag.setReviewStatus("PENDING");
            flag.setCreatedAt(Instant.now().toString());
            anomalyFlagRepository.save(flag);

            // Notify farmer
            if (product != null && product.getFarmerId() != null) {
                NotificationEntity notif = new NotificationEntity();
                notif.setId(UUID.randomUUID().toString());
                notif.setUserId(product.getFarmerId());
                notif.setMessage("⚠️ Suspicious supply chain scan detected for batch " + batchId.substring(0, Math.min(8, batchId.length())) + ". Blockchain recording held for review.");
                notif.setType("ANOMALY_SUSPICIOUS");
                notif.setRelatedEntityId(flag.getId());
                notif.setStatus("ACTION_REQUIRED");
                notif.setCreatedAt(Instant.now().toString());
                notificationRepository.save(notif);
            }

            // Update product journey with suspicious flag and flagId, but DO NOT record on blockchain yet
            statusUpdate = updateProductForRoleWithAnomaly(batchId, nodeRole, operatorId, operatorName, location, eventTime, true, anomalyResult.anomalyScore, flag.getId());
            result.put("flagId", flag.getId());

        } else {
            // NORMAL event: updates product & records on blockchain
            statusUpdate = updateProductForRole(batchId, nodeRole, operatorId, operatorName, location, eventTime);
        }

        result.put("status", "synced");
        result.put("decision", anomalyResult.decision);
        result.put("productStatusUpdate", statusUpdate);
        result.put("txHash", txHash);

        System.out.println("[EdgeSync] Event processed id=" + saved.getId() + ", decision=" + anomalyResult.decision + ", product update: " + statusUpdate);
        return result;
    }

    // ─── Query endpoints ─────────────────────────────────────────────

    @GetMapping("/events")
    public List<ScanEventEntity> getAllEvents() {
        return scanEventRepository.findAll();
    }

    @GetMapping("/events/{batchId}")
    public List<ScanEventEntity> getEventsByBatch(@PathVariable String batchId) {
        return scanEventRepository.findByBatchId(batchId);
    }

    // ─── Core supply chain logic ─────────────────────────────────────

    /**
     * Updates the product's status, operator fields, and journey based on
     * which type of node scanned the QR code.
     *
     * Role mapping:
     * FARMER → currentLocation updated, journey step added (LOCATION type)
     * TRANSPORTER → status=IN_TRANSIT, transporterId/Name set, journey step
     * (TRANSPORT type)
     * SELLER → status=AT_SELLER, sellerId/Name/Location set, journey step (SELLER
     * type)
     */
    private String updateProductForRole(String batchId, String nodeRole,
            String operatorId, String operatorName,
            String location, String eventTime) {
        try {
            Optional<ProductEntity> optProduct = productRepository.findById(batchId);
            if (optProduct.isEmpty()) {
                System.out.println("[EdgeSync] No product found for batchId=" + batchId);
                return "product_not_found";
            }

            ProductEntity product = optProduct.get();
            String journeyType;
            String journeyStatus;
            String description;

            switch (nodeRole.toUpperCase()) {
                case "TRANSPORTER":
                    // Transporter picked up the batch → IN_TRANSIT
                    product.setStatus("IN_TRANSIT");
                    product.setTransporterId(operatorId);
                    product.setTransporterName(operatorName);
                    product.setCurrentLocation(location);

                    journeyType = "SCAN";
                    journeyStatus = "COMPLETED";
                    description = "QR scanned by transporter " + operatorName + " — batch picked up at " + location;
                    System.out.println(
                            "[EdgeSync] Product " + batchId + " → IN_TRANSIT (transporter: " + operatorName + ")");
                    break;

                case "SELLER":
                    // Seller received the batch → AT_SELLER
                    product.setStatus("AT_SELLER");
                    product.setSellerId(operatorId);
                    product.setSellerName(operatorName);
                    product.setSellerLocation(location);
                    product.setCurrentLocation(location);

                    journeyType = "SCAN";
                    journeyStatus = "COMPLETED";
                    description = "QR scanned by seller " + operatorName + " — product received at " + location;
                    System.out.println("[EdgeSync] Product " + batchId + " → AT_SELLER (seller: " + operatorName + ")");
                    break;

                case "FARMER":
                default:
                    // Farmer dispatch or general location scan
                    product.setCurrentLocation(location);

                    journeyType = "SCAN";
                    journeyStatus = "COMPLETED";
                    description = "QR scanned by farmer " + operatorName + " — dispatched from " + location;
                    System.out.println(
                            "[EdgeSync] Product " + batchId + " → dispatched from farm (" + operatorName + ")");
                    break;
            }

            // Append journey step
            appendJourneyStep(product, journeyType, journeyStatus, description,
                    operatorId, operatorName, location, eventTime, nodeRole);

            productRepository.save(product);
            return nodeRole.toLowerCase() + "_updated";

        } catch (Exception e) {
            System.err.println("[EdgeSync] Error updating product " + batchId + ": " + e.getMessage());
            return "error: " + e.getMessage();
        }
    }

    /**
     * Appends a journey step to the product's journey JSON array.
     */
    private void appendJourneyStep(ProductEntity product, String journeyType,
            String journeyStatus, String description,
            String operatorId, String operatorName,
            String location, String eventTime, String nodeRole) {
        try {
            String existingJourney = product.getJourney();

            List<Map<String, Object>> journeyList;
            if (existingJourney != null && !existingJourney.isBlank()) {
                journeyList = objectMapper.readValue(existingJourney,
                        new TypeReference<List<Map<String, Object>>>() {
                        });
            } else {
                journeyList = new ArrayList<>();
            }

            Map<String, Object> step = new HashMap<>();
            step.put("id", "scan-" + UUID.randomUUID().toString().substring(0, 8));
            step.put("timestamp", eventTime);
            step.put("type", journeyType);
            step.put("location", location);
            step.put("status", journeyStatus);
            step.put("description", description);

            // Add role-specific fields matching the JourneyStep interface
            if ("TRANSPORTER".equalsIgnoreCase(nodeRole)) {
                step.put("transporterId", operatorId);
                step.put("transporterName", operatorName);
            } else if ("SELLER".equalsIgnoreCase(nodeRole)) {
                step.put("sellerId", operatorId);
                step.put("sellerName", operatorName);
            }

            // Record on blockchain and store txHash.
            // Canonical event data is derived from the SAME fields stored in the step, so the
            // verifier reconstructs an identical type|timestamp|location|description string.
            // recordEventDetailed only yields a hash once the tx is mined AND the contract has
            // registered the event — a submitted-but-reverted tx never poisons the journey.
            if (blockchainService.isAvailable()) {
                String eventDataStr = BlockchainService.buildEventDataFromStep(step);
                BlockchainService.RecordResult outcome =
                        blockchainService.recordEventDetailed(product.getId(), eventDataStr);
                if (outcome.recorded()) {
                    step.put("txHash", outcome.txHash);
                } else if (!outcome.alreadyOnChain()) {
                    System.err.println("[EdgeSync] Blockchain record failed for batch "
                            + product.getId() + ": " + outcome.detail);
                }
            }

            journeyList.add(step);

            product.setJourney(objectMapper.writeValueAsString(journeyList));
        } catch (Exception e) {
            System.err.println("[EdgeSync] Journey update failed: " + e.getMessage());
        }
    }

    /**
     * Updates product journey with an anomaly/suspicious marker, deliberately bypassing
     * immediate blockchain recording until reviewed.
     */
    private String updateProductForRoleWithAnomaly(String batchId, String nodeRole,
            String operatorId, String operatorName,
            String location, String eventTime, boolean isSuspicious, double anomalyScore, String anomalyFlagId) {
        try {
            Optional<ProductEntity> optProduct = productRepository.findById(batchId);
            if (optProduct.isEmpty()) {
                return "product_not_found";
            }

            ProductEntity product = optProduct.get();
            String description = "[HELD FOR REVIEW - Suspicious Event] Scanned by " + operatorName + " at " + location
                    + " (Anomaly Score: " + String.format("%.2f", anomalyScore) + ")";

            String existingJourney = product.getJourney();
            List<Map<String, Object>> journeyList;
            if (existingJourney != null && !existingJourney.isBlank()) {
                journeyList = objectMapper.readValue(existingJourney,
                        new TypeReference<List<Map<String, Object>>>() {});
            } else {
                journeyList = new ArrayList<>();
            }

            Map<String, Object> step = new HashMap<>();
            step.put("id", "suspicious-" + UUID.randomUUID().toString().substring(0, 8));
            if (anomalyFlagId != null) {
                step.put("anomalyFlagId", anomalyFlagId);
            }
            step.put("timestamp", eventTime);
            step.put("type", "SCAN");
            step.put("location", location);
            step.put("status", "PENDING_REVIEW");
            step.put("description", description);
            step.put("anomalyDetected", true);
            step.put("anomalyScore", anomalyScore);
            step.put("requiresReview", true);

            if ("TRANSPORTER".equalsIgnoreCase(nodeRole)) {
                step.put("transporterId", operatorId);
                step.put("transporterName", operatorName);
            } else if ("SELLER".equalsIgnoreCase(nodeRole)) {
                step.put("sellerId", operatorId);
                step.put("sellerName", operatorName);
            }

            journeyList.add(step);
            product.setJourney(objectMapper.writeValueAsString(journeyList));
            productRepository.save(product);

            return nodeRole.toLowerCase() + "_held_for_review";
        } catch (Exception e) {
            System.err.println("[EdgeSync] Suspicious journey update failed: " + e.getMessage());
            return "error: " + e.getMessage();
        }
    }
}
