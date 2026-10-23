package in.edu.ssn.backend.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.edu.ssn.backend.entity.ProductEntity;
import in.edu.ssn.backend.repository.ProductRepository;
import in.edu.ssn.backend.repository.SellerRequestRepository;
import in.edu.ssn.backend.repository.BookingRepository;
import in.edu.ssn.backend.repository.NotificationRepository;
import in.edu.ssn.backend.repository.TransporterRequestRepository;
import in.edu.ssn.backend.service.BlockchainService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private SellerRequestRepository sellerRequestRepository;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private TransporterRequestRepository transporterRequestRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private BlockchainService blockchainService;

    @Autowired
    private in.edu.ssn.backend.service.AnomalyDetectionService anomalyDetectionService;

    @Autowired
    private in.edu.ssn.backend.repository.AnomalyFlagRepository anomalyFlagRepository;

    @GetMapping
    public List<Map<String, Object>> getAllProducts() {
        System.out.println("Received get all products request");
        List<ProductEntity> products = productRepository.findAll();
        System.out.println("Found " + products.size() + " products");
        return products.stream()
                .map(this::convertToMap)
                .collect(Collectors.toList());
    }

    @PostMapping
    public Map<String, Object> createProduct(@RequestBody Map<String, Object> productMap) {
        System.out.println("Received create product request: " + productMap);
        try {
            ProductEntity entity = convertToEntity(productMap);
            if (entity.getId() == null || entity.getId().isEmpty()) {
                entity.setId(UUID.randomUUID().toString());
            }
            System.out.println("Saving entity: " + entity);
            ProductEntity saved = productRepository.save(Objects.requireNonNull(entity));
            System.out.println("Saved entity: " + saved);

            // Record batch creation on blockchain
            if (blockchainService.isAvailable() && saved.getJourney() != null) {
                try {
                    // Read the actual journey step from DB to ensure hash consistency
                    List<Map<String, Object>> journey = objectMapper.readValue(
                            saved.getJourney(),
                            new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});
                    if (!journey.isEmpty()) {
                        Map<String, Object> step = journey.get(0);
                        // The description is part of the canonical event data, so it MUST be
                        // persisted here or verification can never reproduce the recorded hash.
                        if (step.get("description") == null
                                || String.valueOf(step.get("description")).isBlank()) {
                            step.put("description", "Batch created by " + saved.getFarmerName());
                        }

                        // Single source of truth for canonical event data (type|timestamp|location|description)
                        String eventData = BlockchainService.buildEventDataFromStep(step);
                        BlockchainService.RecordResult outcome =
                                blockchainService.recordEventDetailed(saved.getId(), eventData);

                        if (outcome.recorded()) {
                            step.put("txHash", outcome.txHash);
                            System.out.println("[Blockchain] Batch creation recorded, tx=" + outcome.txHash);
                        } else {
                            // Never store a hash that is not backed by the live chain.
                            System.out.println("[Blockchain] Batch creation not recorded: " + outcome.detail);
                        }

                        saved.setJourney(objectMapper.writeValueAsString(journey));
                        productRepository.save(saved);
                    }
                } catch (Exception ex) {
                    System.err.println("[Blockchain] Failed to record batch creation: " + ex.getMessage());
                }
            }

            return convertToMap(saved);
        } catch (Exception e) {
            e.printStackTrace();
            throw e;
        }
    }

    @GetMapping("/{id}")
    public Map<String, Object> getProduct(@PathVariable String id) {
        return productRepository.findById(id)
                .map(this::convertToMap)
                .orElse(null);
    }

    @PutMapping("/{id}")
    public Map<String, Object> updateProduct(@PathVariable String id, @RequestBody Map<String, Object> update) {
        return productRepository.findById(id).map(existing -> {
            // Count existing journey steps for comparison
            int existingJourneySize = 0;
            if (existing.getJourney() != null && !existing.getJourney().isBlank()) {
                try {
                    List<?> existingJourney = objectMapper.readValue(existing.getJourney(), List.class);
                    existingJourneySize = existingJourney.size();
                } catch (Exception ignored) {}
            }

            // Update existing entity with new values
            Map<String, Object> existingMap = convertToMap(existing);
            existingMap.putAll(update);

            ProductEntity updatedEntity = convertToEntity(existingMap);
            updatedEntity.setId(Objects.requireNonNull(id));

            ProductEntity saved = productRepository.save(updatedEntity);

            // Record new journey steps on blockchain
            if (blockchainService.isAvailable() && saved.getJourney() != null) {
                try {
                    List<Map<String, Object>> journey = objectMapper.readValue(
                            saved.getJourney(),
                            new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});

                    boolean modified = false;
                    // Only process steps that are new (beyond the old journey length)
                    for (int i = existingJourneySize; i < journey.size(); i++) {
                        Map<String, Object> step = journey.get(i);

                        String type = BlockchainService.stepString(step, "type");
                        String timestamp = BlockchainService.stepString(step, "timestamp");
                        String location = BlockchainService.stepString(step, "location");
                        String description = BlockchainService.stepString(step, "description");

                        // Respect an existing AI review hold (or a reviewer rejection): a step already
                        // parked for review must never be pushed to the chain through this path.
                        if (isHeldForReview(step) || isRejectedByReview(step)) {
                            System.out.println("[Anomaly] Step already held/rejected by review for " + id
                                    + " — refusing to record via product update");
                            continue;
                        }

                        // Pre-blockchain anomaly check for manual updates
                        in.edu.ssn.backend.service.AnomalyDetectionService.AnomalyRequest req =
                                new in.edu.ssn.backend.service.AnomalyDetectionService.AnomalyRequest();
                        req.batchId = saved.getId();
                        req.eventType = type;
                        req.eventTime = timestamp;
                        req.location = location;
                        req.productStatus = saved.getStatus();
                        req.productCreatedAt = saved.getCreatedAt();
                        req.existingEventCount = existingJourneySize;

                        in.edu.ssn.backend.service.AnomalyDetectionService.AnomalyResult anom =
                                anomalyDetectionService.checkAnomaly(req);

                        if ("SUSPICIOUS".equals(anom.decision) || "REJECT".equals(anom.decision)) {
                            System.out.println("[Anomaly] Journey update flagged as " + anom.decision + " for " + id + ", skipping blockchain");
                            step.put("anomalyDetected", true);
                            step.put("anomalyScore", anom.anomalyScore);

                            boolean isReject = "REJECT".equals(anom.decision);
                            if (isReject) {
                                // Terminal: the reviewer workflow discarded this event; it is never committed.
                                step.put("requiresReview", false);
                                step.put("anomalyReviewed", true);
                                step.put("anomalyDecision", "REJECTED");
                            } else {
                                step.put("requiresReview", true);
                            }

                            // Persist the audit flag so a SUSPICIOUS event can actually be approved
                            // later via POST /api/anomalies/{id}/approve. Without this the step would
                            // be stuck in PENDING_REVIEW with nothing to review.
                            if (step.get("anomalyFlagId") == null) {
                                try {
                                    in.edu.ssn.backend.entity.AnomalyFlagEntity flag =
                                            new in.edu.ssn.backend.entity.AnomalyFlagEntity();
                                    flag.setId(java.util.UUID.randomUUID().toString());
                                    flag.setBatchId(saved.getId());
                                    flag.setEventType(type);
                                    flag.setEventTime(timestamp);
                                    flag.setLocation(location);
                                    flag.setAnomalyScore(anom.anomalyScore);
                                    flag.setSignals(objectMapper.writeValueAsString(anom.signals));
                                    flag.setDecision(anom.decision);
                                    flag.setReviewStatus(isReject ? "REJECTED" : "PENDING");
                                    flag.setCreatedAt(java.time.Instant.now().toString());
                                    anomalyFlagRepository.save(flag);
                                    step.put("anomalyFlagId", flag.getId());
                                } catch (Exception fe) {
                                    System.err.println("[Anomaly] Failed to persist anomaly flag: " + fe.getMessage());
                                }
                            }

                            modified = true;
                            continue;
                        }

                        // A txHash stored in PostgreSQL is NOT proof the event exists on the
                        // current chain — Hardhat is ephemeral and restarts invalidate old hashes.
                        // Ask the contract itself before deciding to skip.
                        String eventData = BlockchainService.buildEventData(type, timestamp, location, description);
                        if (blockchainService.verifyEvent(saved.getId(), eventData)) {
                            continue; // genuinely already recorded on the active chain
                        }

                        // Record (idempotent) and store the real transaction hash.
                        BlockchainService.RecordResult outcome =
                                blockchainService.recordEventDetailed(saved.getId(), eventData);
                        if (outcome.recorded()) {
                            step.put("txHash", outcome.txHash);
                            modified = true;
                            System.out.println("[Blockchain] New journey step recorded for " + id + " tx=" + outcome.txHash);
                        } else if (!outcome.alreadyOnChain()) {
                            System.out.println("[Blockchain] Journey step not recorded for " + id + ": " + outcome.detail);
                        }
                    }

                    if (modified) {
                        saved.setJourney(objectMapper.writeValueAsString(journey));
                        productRepository.save(saved);
                    }
                } catch (Exception ex) {
                    System.err.println("[Blockchain] Failed to record journey update: " + ex.getMessage());
                }
            }

            return convertToMap(saved);
        }).orElse(null);
    }

    @DeleteMapping("/{id}")
    @org.springframework.transaction.annotation.Transactional
    public Map<String, String> deleteProduct(@PathVariable String id) {
        // Cascading delete: remove all related data
        try {
            // 1. Delete all seller requests for this product
            sellerRequestRepository.deleteByProductId(id);
            System.out.println("Deleted seller requests for product: " + id);

            // 2. Delete all transporter requests for this product
            transporterRequestRepository.deleteByProductId(id);
            System.out.println("Deleted transporter requests for product: " + id);

            // 3. Delete all bookings for this product (batchId = productId)
            bookingRepository.deleteByBatchId(id);
            System.out.println("Deleted bookings for product: " + id);

            // 4. Delete all notifications related to this product
            notificationRepository.deleteByRelatedEntityId(id);
            System.out.println("Deleted notifications for product: " + id);

            // 5. Finally, delete the product itself
            productRepository.deleteById(id);
            System.out.println("Deleted product: " + id);

            Map<String, String> response = new HashMap<>();
            response.put("message", "Product and all related data deleted successfully");
            return response;
        } catch (Exception e) {
            System.err.println("Error deleting product " + id + ": " + e.getMessage());
            e.printStackTrace();
            Map<String, String> response = new HashMap<>();
            response.put("error", "Failed to delete product: " + e.getMessage());
            return response;
        }
    }

    @DeleteMapping("/all")
    public Map<String, String> deleteAllProducts() {
        productRepository.deleteAll();
        Map<String, String> response = new HashMap<>();
        response.put("message", "All products deleted successfully");
        return response;
    }

    private ProductEntity convertToEntity(Map<String, Object> map) {
        Map<String, Object> mapCopy = new HashMap<>(map);
        Object journeyObj = mapCopy.remove("journey");

        ProductEntity entity = objectMapper.convertValue(mapCopy, ProductEntity.class);

        if (journeyObj != null) {
            try {
                if (journeyObj instanceof String) {
                    entity.setJourney((String) journeyObj);
                } else {
                    entity.setJourney(objectMapper.writeValueAsString(journeyObj));
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return entity;
    }

    /**
     * True when a journey step is intentionally parked for AI anomaly review and therefore must
     * not be committed to the blockchain by any automated path.
     */
    private boolean isHeldForReview(Map<String, Object> step) {
        if (step == null) return false;
        String status = BlockchainService.stepString(step, "status");
        if ("PENDING_REVIEW".equalsIgnoreCase(status)) return true;
        if (Boolean.TRUE.equals(step.get("requiresReview"))) return true;
        return Boolean.TRUE.equals(step.get("anomalyDetected"))
                && !Boolean.TRUE.equals(step.get("anomalyReviewed"));
    }

    /** True when the anomaly review workflow discarded this step (distinct from a normal REJECTED status). */
    private boolean isRejectedByReview(Map<String, Object> step) {
        if (step == null) return false;
        Object decision = step.get("anomalyDecision");
        return decision != null && "REJECTED".equalsIgnoreCase(String.valueOf(decision));
    }

    private Map<String, Object> convertToMap(ProductEntity entity) {
        Map<String, Object> map = objectMapper.convertValue(entity, new TypeReference<Map<String, Object>>() {
        });
        // Handle journey string -> List
        if (entity.getJourney() != null) {
            try {
                map.put("journey", objectMapper.readValue(entity.getJourney(), List.class));
            } catch (Exception e) {
                // If parsing fails, leave as string or empty list
                e.printStackTrace();
            }
        }
        return map;
    }
}
