package in.edu.ssn.backend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.edu.ssn.backend.entity.AnomalyFlagEntity;
import in.edu.ssn.backend.entity.NotificationEntity;
import in.edu.ssn.backend.entity.ProductEntity;
import in.edu.ssn.backend.repository.AnomalyFlagRepository;
import in.edu.ssn.backend.repository.NotificationRepository;
import in.edu.ssn.backend.repository.ProductRepository;
import in.edu.ssn.backend.service.BlockchainService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

/**
 * REST controller for querying and reviewing AI-generated anomaly flags.
 *
 * Endpoints:
 *   GET  /api/anomalies                  → list all flags (optionally filter by batchId/status)
 *   GET  /api/anomalies/{id}             → single flag
 *   POST /api/anomalies/{id}/approve     → approve: commits event to blockchain & marks APPROVED
 *   POST /api/anomalies/{id}/reject      → permanently reject the flagged event
 *   GET  /api/anomalies/batch/{batchId}  → flags for a specific batch
 */
@RestController
@RequestMapping("/api/anomalies")
public class AnomalyController {

    @Autowired
    private AnomalyFlagRepository anomalyFlagRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private BlockchainService blockchainService;

    @Autowired
    private ObjectMapper objectMapper;

    // ── Query endpoints ───────────────────────────────────────────────

    @GetMapping
    public ResponseEntity<List<AnomalyFlagEntity>> getAllFlags(
            @RequestParam(required = false) String status) {
        List<AnomalyFlagEntity> flags = (status != null)
                ? anomalyFlagRepository.findByReviewStatus(status.toUpperCase())
                : anomalyFlagRepository.findAll();
        return ResponseEntity.ok(flags);
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getFlag(@PathVariable String id) {
        return anomalyFlagRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/batch/{batchId}")
    public ResponseEntity<List<AnomalyFlagEntity>> getFlagsForBatch(
            @PathVariable String batchId,
            @RequestParam(required = false) String status) {
        List<AnomalyFlagEntity> flags = (status != null)
                ? anomalyFlagRepository.findByBatchIdAndReviewStatus(batchId, status.toUpperCase())
                : anomalyFlagRepository.findByBatchId(batchId);
        return ResponseEntity.ok(flags);
    }

    // ── Review actions ────────────────────────────────────────────────

    /**
     * Approve a flagged event:
     *  1. Locate the existing PENDING_REVIEW journey step belonging to the flag
     *  2. Record the event on the blockchain exactly ONCE
     *  3. Update the existing journey step in-place (no duplicate step appended)
     *  4. Update product role fields if applicable
     *  5. Notify the farmer
     *  6. Mark flag as APPROVED
     */
    @PostMapping("/{id}/approve")
    public ResponseEntity<Map<String, Object>> approveFlag(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, String> payload) {

        Optional<AnomalyFlagEntity> optFlag = anomalyFlagRepository.findById(id);
        if (optFlag.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        AnomalyFlagEntity flag = optFlag.get();
        Map<String, Object> result = new HashMap<>();

        if (!"PENDING".equals(flag.getReviewStatus())) {
            result.put("error", "Flag is already " + flag.getReviewStatus());
            return ResponseEntity.badRequest().body(result);
        }

        // Verify product exists and locate original pending journey step before committing
        Optional<ProductEntity> optProduct = productRepository.findById(flag.getBatchId());
        if (optProduct.isEmpty()) {
            result.put("error", "Product not found for batchId: " + flag.getBatchId());
            return ResponseEntity.badRequest().body(result);
        }
        ProductEntity product = optProduct.get();

        // Check if journey step exists before calling blockchain
        List<Map<String, Object>> journeyList;
        try {
            String existingJourney = product.getJourney();
            if (existingJourney == null || existingJourney.isBlank()) {
                result.put("error", "Product has no journey data");
                return ResponseEntity.badRequest().body(result);
            }
            journeyList = objectMapper.readValue(
                    existingJourney,
                    new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            result.put("error", "Failed to parse product journey: " + e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }

        Map<String, Object> targetStep = findPendingJourneyStep(journeyList, flag);
        if (targetStep == null) {
            result.put("error", "Original pending journey step could not be found for anomaly flag " + id);
            return ResponseEntity.badRequest().body(result);
        }

        String reviewedBy = (payload != null) ? payload.getOrDefault("reviewedBy", "admin") : "admin";

        String stepType = "SCAN";
        String stepDesc = "[Reviewed & Approved] Scanned by " + flag.getOperatorName()
                + " at " + flag.getLocation()
                + " | Originally flagged with anomaly score " + String.format("%.2f", flag.getAnomalyScore());

        // 1. Update the EXISTING journey step in place (never append a duplicate step).
        targetStep.put("type", stepType);
        targetStep.put("description", stepDesc);
        targetStep.put("timestamp", flag.getEventTime());
        targetStep.put("location", flag.getLocation());
        targetStep.put("status", "COMPLETED");
        targetStep.put("anomalyReviewed", true);
        targetStep.put("anomalyDecision", "APPROVED");
        targetStep.put("requiresReview", false);
        targetStep.put("anomalyDetected", true);

        // 2. Derive canonical event data FROM the persisted step. This guarantees the hash written
        //    here is byte-for-byte the hash the verification endpoint will later reconstruct from
        //    the same journey step — one source of truth, no drift.
        String eventData = BlockchainService.buildEventDataFromStep(targetStep);

        // 3. Commit exactly once. recordEventDetailed is idempotent, so a retry can never
        //    double-write or trigger the contract's "Duplicate event" revert.
        String txHash = null;
        if (blockchainService.isAvailable()) {
            BlockchainService.RecordResult outcome =
                    blockchainService.recordEventDetailed(flag.getBatchId(), eventData);
            if (outcome.isOnChain()) {
                txHash = outcome.txHash;
            } else {
                System.err.println("[Anomaly] Approval approved but not recorded on-chain: " + outcome.detail);
            }
        }
        if (txHash != null) {
            targetStep.put("txHash", txHash);
        }

        // Update product role fields if applicable
        if ("TRANSPORTER".equalsIgnoreCase(flag.getNodeRole())) {
            product.setStatus("IN_TRANSIT");
            if (flag.getOperatorId() != null) product.setTransporterId(flag.getOperatorId());
            if (flag.getOperatorName() != null) product.setTransporterName(flag.getOperatorName());
            if (flag.getLocation() != null) product.setCurrentLocation(flag.getLocation());
        } else if ("SELLER".equalsIgnoreCase(flag.getNodeRole())) {
            product.setStatus("AT_SELLER");
            if (flag.getOperatorId() != null) product.setSellerId(flag.getOperatorId());
            if (flag.getOperatorName() != null) product.setSellerName(flag.getOperatorName());
            if (flag.getLocation() != null) product.setSellerLocation(flag.getLocation());
            if (flag.getLocation() != null) product.setCurrentLocation(flag.getLocation());
        } else if ("FARMER".equalsIgnoreCase(flag.getNodeRole())) {
            if (flag.getLocation() != null) product.setCurrentLocation(flag.getLocation());
        }

        try {
            product.setJourney(objectMapper.writeValueAsString(journeyList));
            productRepository.save(product);
        } catch (Exception e) {
            System.err.println("[Anomaly] Failed to serialize updated journey: " + e.getMessage());
            result.put("error", "Failed to update product journey: " + e.getMessage());
            return ResponseEntity.internalServerError().body(result);
        }

        // Notify farmer
        if (product.getFarmerId() != null) {
            NotificationEntity notification = new NotificationEntity();
            notification.setId(UUID.randomUUID().toString());
            notification.setUserId(product.getFarmerId());
            notification.setMessage("A previously flagged supply-chain event for batch "
                    + flag.getBatchId().substring(0, Math.min(8, flag.getBatchId().length()))
                    + " has been reviewed and APPROVED. It is now recorded on the blockchain.");
            notification.setType("ANOMALY_APPROVED");
            notification.setRelatedEntityId(flag.getId());
            notification.setStatus("UNREAD");
            notification.setCreatedAt(Instant.now().toString());
            notificationRepository.save(notification);
        }

        // Update flag status
        flag.setReviewStatus("APPROVED");
        flag.setReviewedBy(reviewedBy);
        flag.setReviewedAt(Instant.now().toString());
        flag.setApprovedTxHash(txHash);
        anomalyFlagRepository.save(flag);

        result.put("status", "approved");
        result.put("flagId", flag.getId());
        result.put("batchId", flag.getBatchId());
        result.put("txHash", txHash);
        result.put("message", txHash != null
                ? "Event approved and recorded on blockchain (tx=" + txHash + ")"
                : "Event approved. Blockchain unavailable — record manually when node is online.");
        return ResponseEntity.ok(result);
    }

    /**
     * Reject a flagged event: mark as REJECTED and notify farmer.
     */
    @PostMapping("/{id}/reject")
    public ResponseEntity<Map<String, Object>> rejectFlag(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, String> payload) {

        Optional<AnomalyFlagEntity> optFlag = anomalyFlagRepository.findById(id);
        if (optFlag.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        AnomalyFlagEntity flag = optFlag.get();
        Map<String, Object> result = new HashMap<>();

        if (!"PENDING".equals(flag.getReviewStatus())) {
            result.put("error", "Flag is already " + flag.getReviewStatus());
            return ResponseEntity.badRequest().body(result);
        }

        String reviewedBy = (payload != null) ? payload.getOrDefault("reviewedBy", "admin") : "admin";
        String reason = (payload != null) ? payload.getOrDefault("reason", "Rejected by reviewer") : "Rejected by reviewer";

        flag.setReviewStatus("REJECTED");
        flag.setReviewedBy(reviewedBy);
        flag.setReviewedAt(Instant.now().toString());
        anomalyFlagRepository.save(flag);

        // Notify farmer and update journey step if product exists
        Optional<ProductEntity> optProduct = productRepository.findById(flag.getBatchId());
        optProduct.ifPresent(product -> {
            try {
                if (product.getJourney() != null && !product.getJourney().isBlank()) {
                    List<Map<String, Object>> journeyList = objectMapper.readValue(
                            product.getJourney(),
                            new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});
                    Map<String, Object> targetStep = findPendingJourneyStep(journeyList, flag);
                    if (targetStep != null) {
                        targetStep.put("status", "REJECTED");
                        targetStep.put("anomalyReviewed", true);
                        targetStep.put("anomalyDecision", "REJECTED");
                        targetStep.put("requiresReview", false);
                        targetStep.put("description", "[Rejected by Reviewer] " + reason);
                        product.setJourney(objectMapper.writeValueAsString(journeyList));
                        productRepository.save(product);
                    }
                }
            } catch (Exception e) {
                System.err.println("[Anomaly] Failed to update rejected step in journey: " + e.getMessage());
            }

            NotificationEntity notification = new NotificationEntity();
            notification.setId(UUID.randomUUID().toString());
            notification.setUserId(product.getFarmerId());
            notification.setMessage("A suspicious supply-chain event for batch "
                    + flag.getBatchId().substring(0, Math.min(8, flag.getBatchId().length()))
                    + " has been REJECTED: " + reason);
            notification.setType("ANOMALY_REJECTED");
            notification.setRelatedEntityId(flag.getId());
            notification.setStatus("UNREAD");
            notification.setCreatedAt(Instant.now().toString());
            notificationRepository.save(notification);
        });

        result.put("status", "rejected");
        result.put("flagId", flag.getId());
        result.put("batchId", flag.getBatchId());
        result.put("message", "Event rejected and discarded.");
        return ResponseEntity.ok(result);
    }

    // ── Statistics endpoint ───────────────────────────────────────────

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        List<AnomalyFlagEntity> all = anomalyFlagRepository.findAll();
        long pending  = all.stream().filter(f -> "PENDING".equals(f.getReviewStatus())).count();
        long approved = all.stream().filter(f -> "APPROVED".equals(f.getReviewStatus())).count();
        long rejected = all.stream().filter(f -> "REJECTED".equals(f.getReviewStatus())).count();

        Map<String, Object> stats = new HashMap<>();
        stats.put("total", all.size());
        stats.put("pending", pending);
        stats.put("approved", approved);
        stats.put("rejected", rejected);
        return ResponseEntity.ok(stats);
    }

    // ── Private helpers ───────────────────────────────────────────────

    /**
     * Locate the original pending journey step corresponding to an anomaly flag.
     * Checks anomalyFlagId first, then falls back to pending status and timestamp match.
     */
    private Map<String, Object> findPendingJourneyStep(List<Map<String, Object>> journeyList, AnomalyFlagEntity flag) {
        if (journeyList == null || flag == null) return null;

        // 1. Primary match: anomalyFlagId
        for (Map<String, Object> step : journeyList) {
            Object flagIdObj = step.get("anomalyFlagId");
            if (flagIdObj != null && flag.getId().equals(String.valueOf(flagIdObj))) {
                return step;
            }
        }

        // 2. Fallback match: pending status and matching eventTime
        for (Map<String, Object> step : journeyList) {
            boolean isPending = "PENDING_REVIEW".equalsIgnoreCase(String.valueOf(step.get("status")))
                    || Boolean.TRUE.equals(step.get("requiresReview"));
            boolean matchesTime = Objects.equals(step.get("timestamp"), flag.getEventTime());
            if (isPending && matchesTime) {
                return step;
            }
        }

        return null;
    }
}
