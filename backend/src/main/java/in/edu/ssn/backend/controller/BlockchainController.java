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
 */
@RestController
@RequestMapping("/api/blockchain")
public class BlockchainController {

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
        Map<String, Object> status = new HashMap<>();
        status.put("available", blockchainService.isAvailable());
        return ResponseEntity.ok(status);
    }

    /**
     * Reconnect to the blockchain node (useful after starting Hardhat node).
     */
    @PostMapping("/reconnect")
    public ResponseEntity<Map<String, Object>> reconnect() {
        blockchainService.init();
        Map<String, Object> status = new HashMap<>();
        status.put("available", blockchainService.isAvailable());
        status.put("message", blockchainService.isAvailable()
                ? "Successfully connected to blockchain node"
                : "Failed to connect — is the Hardhat node running?");
        return ResponseEntity.ok(status);
    }

    /**
     * Verify the blockchain integrity of a product's journey.
     * Re-computes hashes from DB journey data and checks them against on-chain records.
     */
    @GetMapping("/verify/{batchId}")
    public ResponseEntity<Map<String, Object>> verifyBatch(@PathVariable String batchId) {
        Map<String, Object> result = new HashMap<>();
        result.put("batchId", batchId);

        if (!blockchainService.isAvailable()) {
            result.put("status", "unavailable");
            result.put("message", "Blockchain service is not connected");
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
            List<Map<String, Object>> journey = objectMapper.readValue(
                    journeyJson, new TypeReference<List<Map<String, Object>>>() {});

            int totalSteps = journey.size();
            int verifiedSteps = 0;
            List<Map<String, Object>> stepResults = new ArrayList<>();

            for (Map<String, Object> step : journey) {
                String type = String.valueOf(step.getOrDefault("type", step.getOrDefault("status", "")));
                String timestamp = String.valueOf(step.getOrDefault("timestamp", ""));
                String location = String.valueOf(step.getOrDefault("location", ""));
                String description = String.valueOf(step.getOrDefault("description", ""));

                String eventData = BlockchainService.buildEventData(type, timestamp, location, description);
                boolean verified = blockchainService.verifyEvent(batchId, eventData);

                Map<String, Object> stepResult = new HashMap<>();
                stepResult.put("type", type);
                stepResult.put("timestamp", timestamp);
                stepResult.put("verified", verified);
                stepResult.put("eventHash", BlockchainService.bytesToHex(
                        blockchainService.computeEventHash(batchId, eventData)));

                // Include txHash if stored in journey step
                if (step.containsKey("txHash")) {
                    stepResult.put("txHash", step.get("txHash"));
                }

                stepResults.add(stepResult);
                if (verified) verifiedSteps++;
            }

            int onChainCount = blockchainService.getEventCount(batchId);

            result.put("status", verifiedSteps == totalSteps ? "verified" : "compromised");
            result.put("totalSteps", totalSteps);
            result.put("verifiedSteps", verifiedSteps);
            result.put("onChainEventCount", onChainCount);
            result.put("steps", stepResults);
            result.put("message", verifiedSteps == totalSteps
                    ? "All journey events verified on blockchain"
                    : (totalSteps - verifiedSteps) + " event(s) could not be verified — possible tampering");

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
        Map<String, Object> result = new HashMap<>();
        result.put("batchId", batchId);

        if (!blockchainService.isAvailable()) {
            result.put("available", false);
            return ResponseEntity.ok(result);
        }

        result.put("available", true);
        result.put("onChainEventCount", blockchainService.getEventCount(batchId));
        return ResponseEntity.ok(result);
    }
    /**
     * Retroactively record all journey steps for an existing batch on the blockchain.
     * Useful when the Hardhat node was offline during batch creation.
     */
    @PostMapping("/record/{batchId}")
    public ResponseEntity<Map<String, Object>> recordExistingBatch(@PathVariable String batchId) {
        Map<String, Object> result = new HashMap<>();
        result.put("batchId", batchId);

        if (!blockchainService.isAvailable()) {
            result.put("status", "unavailable");
            result.put("message", "Blockchain service is not connected");
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
            List<Map<String, Object>> journey = objectMapper.readValue(
                    product.getJourney(), new TypeReference<List<Map<String, Object>>>() {});

            int recorded = 0;
            int skipped = 0;

            for (Map<String, Object> step : journey) {
                // Skip steps already recorded on-chain
                String existingTx = step.get("txHash") != null ? String.valueOf(step.get("txHash")) : null;
                if (existingTx != null && !existingTx.isEmpty() && !"null".equals(existingTx)) {
                    // Verify it's actually on-chain
                    String type = String.valueOf(step.getOrDefault("type", step.getOrDefault("status", "")));
                    String timestamp = String.valueOf(step.getOrDefault("timestamp", ""));
                    String location = String.valueOf(step.getOrDefault("location", ""));
                    String description = String.valueOf(step.getOrDefault("description", ""));
                    String eventData = BlockchainService.buildEventData(type, timestamp, location, description);

                    if (blockchainService.verifyEvent(batchId, eventData)) {
                        skipped++;
                        continue;
                    }
                }

                // Record this step on-chain
                String type = String.valueOf(step.getOrDefault("type", step.getOrDefault("status", "")));
                String timestamp = String.valueOf(step.getOrDefault("timestamp", ""));
                String location = String.valueOf(step.getOrDefault("location", ""));
                String description = String.valueOf(step.getOrDefault("description", ""));
                String eventData = BlockchainService.buildEventData(type, timestamp, location, description);
                String txHash = blockchainService.recordEvent(batchId, eventData);
                if (txHash != null) {
                    step.put("txHash", txHash);
                    recorded++;
                    System.out.println("[Blockchain] Backfill recorded step " + type + " for " + batchId + " tx=" + txHash);
                }
            }

            // Save updated journey with txHashes
            product.setJourney(objectMapper.writeValueAsString(journey));
            productRepository.save(product);

            result.put("status", "success");
            result.put("recorded", recorded);
            result.put("skipped", skipped);
            result.put("message", recorded + " event(s) recorded on blockchain" +
                    (skipped > 0 ? ", " + skipped + " already on-chain" : ""));

        } catch (Exception e) {
            result.put("status", "error");
            result.put("message", "Recording failed: " + e.getMessage());
        }

        return ResponseEntity.ok(result);
    }
}
