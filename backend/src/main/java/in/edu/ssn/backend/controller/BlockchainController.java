package in.edu.ssn.backend.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.edu.ssn.backend.entity.ProductEntity;
import in.edu.ssn.backend.repository.ProductRepository;
import in.edu.ssn.backend.service.BlockchainService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * REST controller for blockchain verification of supply chain events.
 *
 * <h3>Ground rules</h3>
 * <ul>
 *   <li>A txHash stored in PostgreSQL is <strong>never</strong> taken as proof that the event
 *       exists on the current chain. The Hardhat dev chain is ephemeral, so stored hashes go
 *       stale every time {@code npx hardhat node} is restarted.</li>
 *   <li>Per-step state is derived only from the live contract: {@code verified},
 *       {@code pending_review}, {@code rejected}, {@code unrecorded} or {@code tampered}.</li>
 *   <li>"tampered" is reserved for the case where a step claims an on-chain commitment whose
 *       transaction genuinely executed on <em>this</em> chain, yet the expected event hash is not
 *       registered — i.e. a real cryptographic mismatch. A missing/absent record is merely
 *       "unrecorded", never tampering.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/blockchain")
public class BlockchainController {

    // Per-step classifications
    private static final String STEP_VERIFIED = "verified";
    private static final String STEP_PENDING_REVIEW = "pending_review";
    private static final String STEP_REJECTED = "rejected";
    private static final String STEP_UNRECORDED = "unrecorded";
    private static final String STEP_TAMPERED = "tampered";

    @Autowired
    private BlockchainService blockchainService;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * Check if the blockchain service is available.
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        boolean ready = blockchainService.isAvailable();
        status.put("available", ready);
        status.put("nodeReachable", blockchainService.isNodeReachable());
        status.put("contractDeployed", blockchainService.isContractDeployed());
        status.put("contractAddress", blockchainService.getContractAddress());
        status.put("message", ready
                ? "Blockchain ready"
                : blockchainService.getUnavailableReason());
        return ResponseEntity.ok(status);
    }

    /**
     * Reconnect to the blockchain node (useful after starting/restarting Hardhat).
     */
    @PostMapping("/reconnect")
    public ResponseEntity<Map<String, Object>> reconnect() {
        blockchainService.init();
        Map<String, Object> status = new LinkedHashMap<>();
        boolean ready = blockchainService.isAvailable();
        status.put("available", ready);
        status.put("nodeReachable", blockchainService.isNodeReachable());
        status.put("contractDeployed", blockchainService.isContractDeployed());
        status.put("contractAddress", blockchainService.getContractAddress());
        status.put("message", ready
                ? "Successfully connected to blockchain node"
                : blockchainService.getUnavailableReason());
        return ResponseEntity.ok(status);
    }

    /**
     * Verify the blockchain integrity of a product's journey.
     * Re-computes canonical hashes from DB journey data and checks them against on-chain records.
     */
    @GetMapping("/verify/{batchId}")
    public ResponseEntity<Map<String, Object>> verifyBatch(@PathVariable String batchId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("batchId", batchId);

        if (!blockchainService.isAvailable()) {
            result.put("status", "unavailable");
            result.put("blockchainAvailable", false);
            result.put("message", blockchainService.getUnavailableReason());
            return ResponseEntity.ok(result);
        }

        // Load product from DB
        Optional<ProductEntity> optProduct = productRepository.findById(batchId);
        if (optProduct.isEmpty()) {
            result.put("status", "error");
            result.put("message", "Product not found");
            return ResponseEntity.ok(result);
        }

        ProductEntity product = optProduct.get();
        String journeyJson = product.getJourney();

        if (journeyJson == null || journeyJson.isBlank()) {
            result.put("status", "no_journey");
            result.put("message", "No journey data to verify");
            return ResponseEntity.ok(result);
        }

        try {
            List<Map<String, Object>> journey = parseJourney(journeyJson);
            result.put("blockchainAvailable", true);
            result.put("contractAddress", blockchainService.getContractAddress());

            int totalSteps = journey.size();
            int verifiedSteps = 0;
            int pendingReview = 0;
            int unrecorded = 0;
            int tampered = 0;
            int rejected = 0;
            List<Map<String, Object>> stepResults = new ArrayList<>();

            for (Map<String, Object> step : journey) {
                String type = BlockchainService.stepString(step, "type");
                String timestamp = BlockchainService.stepString(step, "timestamp");
                String location = BlockchainService.stepString(step, "location");
                String description = BlockchainService.stepString(step, "description");
                String eventData = BlockchainService.buildEventData(type, timestamp, location, description);

                String stepStatus = classifyStep(batchId, step, eventData);
                boolean verified = STEP_VERIFIED.equals(stepStatus);
                if (verified) verifiedSteps++;
                switch (stepStatus) {
                    case STEP_PENDING_REVIEW -> pendingReview++;
                    case STEP_UNRECORDED -> unrecorded++;
                    case STEP_TAMPERED -> tampered++;
                    case STEP_REJECTED -> rejected++;
                    default -> { /* verified */ }
                }

                Map<String, Object> stepResult = new LinkedHashMap<>();
                stepResult.put("type", type);
                stepResult.put("timestamp", timestamp);
                stepResult.put("location", location);
                stepResult.put("description", description);
                stepResult.put("verified", verified);
                stepResult.put("stepStatus", stepStatus);
                stepResult.put("eventHash", BlockchainService.bytesToHex(
                        blockchainService.computeEventHash(batchId, eventData)));
                stepResult.put("requiresReview", isPendingReview(step));

                // Include the stored txHash, plus whether that tx still exists on THIS chain.
                String storedTx = rawString(step.get("txHash"));
                if (storedTx != null) {
                    stepResult.put("txHash", storedTx);
                    stepResult.put("txConfirmedOnCurrentChain",
                            blockchainService.isTransactionConfirmed(storedTx));
                }

                stepResults.add(stepResult);
            }

            int onChainCount = blockchainService.getEventCount(batchId);

            String status = resolveStatus(tampered, unrecorded, pendingReview, totalSteps);

            result.put("status", status);
            result.put("totalSteps", totalSteps);
            result.put("verifiedSteps", verifiedSteps);
            result.put("onChainEventCount", onChainCount);
            result.put("pendingReviewCount", pendingReview);
            result.put("unrecordedCount", unrecorded);
            result.put("tamperedCount", tampered);
            result.put("rejectedCount", rejected);
            result.put("steps", stepResults);
            result.put("message", resolveMessage(status, verifiedSteps, totalSteps,
                    unrecorded, pendingReview, tampered));

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", "Verification failed: " + e.getMessage());
        }

        return ResponseEntity.ok(result);
    }

    /**
     * Get the number of on-chain events for a batch.
     */
    @GetMapping("/events/{batchId}")
    public ResponseEntity<Map<String, Object>> getEvents(@PathVariable String batchId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("batchId", batchId);

        if (!blockchainService.isAvailable()) {
            result.put("available", false);
            result.put("message", blockchainService.getUnavailableReason());
            return ResponseEntity.ok(result);
        }

        result.put("available", true);
        result.put("onChainEventCount", blockchainService.getEventCount(batchId));
        return ResponseEntity.ok(result);
    }

    /**
     * Record all journey steps for an existing batch on the blockchain.
     *
     * <p>Handles every recovery case:
     * <ul>
     *   <li>CASE A — no txHash → record it.</li>
     *   <li>CASE B — txHash valid on the CURRENT chain → skip (already recorded).</li>
     *   <li>CASE C — stale txHash from a previous Hardhat session → re-record and update the
     *       existing journey step with the new txHash (never a duplicate step).</li>
     *   <li>CASE D — PENDING_REVIEW (AI anomaly) → never auto-recorded, reported separately.</li>
     *   <li>CASE E — anomaly-approved event → recorded exactly once using the same canonical
     *       event data the verifier reconstructs.</li>
     * </ul>
     * The final response embeds a fresh verification so the caller sees the true end state.
     */
    @PostMapping("/record/{batchId}")
    public ResponseEntity<Map<String, Object>> recordExistingBatch(@PathVariable String batchId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("batchId", batchId);

        if (!blockchainService.isAvailable()) {
            result.put("status", "unavailable");
            result.put("message", blockchainService.getUnavailableReason());
            return ResponseEntity.ok(result);
        }

        Optional<ProductEntity> optProduct = productRepository.findById(batchId);
        if (optProduct.isEmpty()) {
            result.put("status", "error");
            result.put("message", "Product not found");
            return ResponseEntity.ok(result);
        }

        ProductEntity product = optProduct.get();
        if (product.getJourney() == null || product.getJourney().isBlank()) {
            result.put("status", "error");
            result.put("message", "No journey data to record");
            return ResponseEntity.ok(result);
        }

        try {
            List<Map<String, Object>> journey = parseJourney(product.getJourney());

            int recorded = 0;
            int alreadyOnChain = 0;
            int pendingReview = 0;
            int rejectedCount = 0;
            int failed = 0;
            boolean journeyModified = false;

            for (Map<String, Object> step : journey) {
                // CASE D — an event held for AI review is never committed automatically.
                if (isPendingReview(step)) {
                    System.out.println("[Blockchain] Skipping pending-review step in batch " + batchId);
                    pendingReview++;
                    continue;
                }

                // A reviewer-rejected event must never reach the ledger.
                if (isAnomalyRejected(step)) {
                    rejectedCount++;
                    continue;
                }

                String eventData = BlockchainService.buildEventDataFromStep(step);

                // CASE B/C — the contract's own ledger decides, never the stored txHash.
                if (blockchainService.verifyEvent(batchId, eventData)) {
                    alreadyOnChain++;
                    continue;
                }

                // CASE A/C/E — record it now (idempotent write).
                BlockchainService.RecordResult outcome =
                        blockchainService.recordEventDetailed(batchId, eventData);

                if (outcome.recorded()) {
                    // Update the EXISTING journey step — never append a duplicate.
                    step.put("txHash", outcome.txHash);
                    journeyModified = true;
                    recorded++;
                    System.out.println("[Blockchain] Recorded step ("
                            + BlockchainService.stepString(step, "type") + ") for " + batchId
                            + " tx=" + outcome.txHash);
                } else if (outcome.alreadyOnChain()) {
                    alreadyOnChain++;
                } else {
                    failed++;
                    System.out.println("[Blockchain] Could not record step for " + batchId
                            + ": " + outcome.detail);
                }
            }

            if (journeyModified) {
                product.setJourney(objectMapper.writeValueAsString(journey));
                productRepository.save(product);
            }

            int skipped = alreadyOnChain + rejectedCount;
            String status = (failed == 0) ? "success" : (recorded > 0 ? "partial" : "error");

            result.put("status", status);
            result.put("recorded", recorded);
            result.put("skipped", skipped);
            result.put("alreadyOnChain", alreadyOnChain);
            result.put("pendingReview", pendingReview);
            result.put("rejected", rejectedCount);
            result.put("failed", failed);
            result.put("message", buildRecordMessage(recorded, alreadyOnChain, pendingReview,
                    rejectedCount, failed));

            // Fresh verification so the caller sees the true post-record state.
            result.put("verification", verifyBatch(batchId).getBody());

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", "Recording failed: " + e.getMessage());
        }

        return ResponseEntity.ok(result);
    }

    // ── Step classification ──────────────────────────────────────────

    private String classifyStep(String batchId, Map<String, Object> step, String eventData) {
        // 1. Intentionally held for AI review → never "tampering".
        if (isPendingReview(step)) return STEP_PENDING_REVIEW;

        // 2. Rejected by a reviewer → intentionally absent from the ledger.
        if (isAnomalyRejected(step)) return STEP_REJECTED;

        // 3. Present on the CURRENT chain with a matching hash → verified.
        if (blockchainService.verifyEvent(batchId, eventData)) return STEP_VERIFIED;

        // 4. Absent from the chain. Distinguish stale pointer from real mismatch:
        //    - txHash whose receipt no longer exists (chain was reset) → just unrecorded
        //    - txHash that really executed on THIS chain but didn't register this hash → tampered
        String storedTx = rawString(step.get("txHash"));
        if (storedTx != null && blockchainService.isTransactionConfirmed(storedTx)) {
            return STEP_TAMPERED;
        }

        return STEP_UNRECORDED;
    }

    /**
     * A step is pending AI review when it is explicitly PENDING_REVIEW, flagged as requiring
     * review, or detected-but-not-yet-reviewed.
     */
    private boolean isPendingReview(Map<String, Object> step) {
        if (step == null) return false;
        String status = BlockchainService.stepString(step, "status");
        if ("PENDING_REVIEW".equalsIgnoreCase(status)) return true;
        if (Boolean.TRUE.equals(step.get("requiresReview"))) return true;
        return Boolean.TRUE.equals(step.get("anomalyDetected"))
                && !Boolean.TRUE.equals(step.get("anomalyReviewed"));
    }

    /**
     * A step discarded by the anomaly review workflow (distinct from a legitimate
     * transporter/seller REJECTED status, which is unrelated to anomaly review).
     */
    private boolean isAnomalyRejected(Map<String, Object> step) {
        if (step == null) return false;
        Object decision = step.get("anomalyDecision");
        return decision != null && "REJECTED".equalsIgnoreCase(String.valueOf(decision));
    }

    private String resolveStatus(int tampered, int unrecorded, int pendingReview, int totalSteps) {
        if (totalSteps == 0) return "no_journey";
        if (tampered > 0) return "tampered";
        if (unrecorded > 0) return "pending_recording";
        if (pendingReview > 0) return "pending_review";
        return "verified";
    }

    private String resolveMessage(String status, int verifiedSteps, int totalSteps,
                                  int unrecorded, int pendingReview, int tampered) {
        return switch (status) {
            case "verified" -> "All journey events verified on blockchain";
            case "pending_recording" -> unrecorded == totalSteps
                    ? "Pending On-Chain Recording"
                    : unrecorded + " of " + totalSteps
                      + " event(s) pending on-chain recording";
            case "pending_review" -> "⚠ Event Held for AI Review — Blockchain commitment pending approval";
            case "tampered" -> tampered + " event(s) failed cryptographic verification — possible tampering ("
                    + verifiedSteps + "/" + totalSteps + " verified)";
            case "no_journey" -> "No journey data to verify";
            default -> "Blockchain not available";
        };
    }

    private String buildRecordMessage(int recorded, int alreadyOnChain, int pendingReview,
                                      int rejected, int failed) {
        List<String> parts = new ArrayList<>();
        parts.add(recorded + " event(s) recorded on blockchain");
        if (alreadyOnChain > 0) parts.add(alreadyOnChain + " already on-chain");
        if (pendingReview > 0) parts.add(pendingReview + " held for AI review");
        if (rejected > 0) parts.add(rejected + " rejected by review");
        if (failed > 0) parts.add(failed + " failed to record");
        return String.join(", ", parts);
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private List<Map<String, Object>> parseJourney(String journeyJson) throws Exception {
        return objectMapper.readValue(journeyJson, new TypeReference<List<Map<String, Object>>>() {});
    }

    /** Normalised non-empty txHash string, or null when absent/blank/"null". */
    private String rawString(Object value) {
        if (value == null) return null;
        String s = String.valueOf(value).trim();
        if (s.isEmpty() || "null".equalsIgnoreCase(s)) return null;
        return s;
    }
}
