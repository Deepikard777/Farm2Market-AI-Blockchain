package in.edu.ssn.backend.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.edu.ssn.backend.entity.ProductEntity;
import in.edu.ssn.backend.entity.ScanEventEntity;
import in.edu.ssn.backend.repository.ProductRepository;
import in.edu.ssn.backend.repository.ScanEventRepository;
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

    // ─── Single event sync ───────────────────────────────────────────

    @PostMapping("/sync")
    public ResponseEntity<Map<String, Object>> syncEvent(@RequestBody Map<String, String> payload) {
        System.out.println("[EdgeSync] Received scan event: " + payload);

        String batchId = payload.get("batchId");
        String nodeId = payload.get("nodeId");
        String eventType = payload.getOrDefault("eventType", "SCAN");
        String eventTime = payload.get("eventTime");
        String nodeRole = payload.getOrDefault("nodeRole", "");
        String operatorId = payload.getOrDefault("operatorId", "");
        String operatorName = payload.getOrDefault("operatorName", "");
        String location = payload.getOrDefault("location", "");

        if (batchId == null || nodeId == null || eventTime == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Missing required fields: batchId, nodeId, eventTime"));
        }

        // 1. Persist the scan event
        ScanEventEntity scanEvent = new ScanEventEntity();
        scanEvent.setBatchId(batchId);
        scanEvent.setNodeId(nodeId);
        scanEvent.setEventType(eventType);
        scanEvent.setEventTime(eventTime);
        scanEvent.setReceivedAt(Instant.now().toString());

        ScanEventEntity saved = scanEventRepository.save(scanEvent);

        // 2. Update product status + fields based on node role
        String statusUpdate = updateProductForRole(batchId, nodeRole, operatorId, operatorName, location, eventTime);

        // 3. Record on blockchain
        String txHash = null;
        if (blockchainService.isAvailable()) {
            String eventDataStr = BlockchainService.buildEventData(
                    nodeRole, eventTime, location,
                    "Scanned by " + operatorName + " at " + location);
            txHash = blockchainService.recordEvent(batchId, eventDataStr);
        }

        Map<String, Object> response = new HashMap<>();
        response.put("id", saved.getId());
        response.put("batchId", saved.getBatchId());
        response.put("nodeId", saved.getNodeId());
        response.put("nodeRole", nodeRole);
        response.put("operatorName", operatorName);
        response.put("eventTime", saved.getEventTime());
        response.put("receivedAt", saved.getReceivedAt());
        response.put("status", "synced");
        response.put("productStatusUpdate", statusUpdate);
        response.put("txHash", txHash);

        System.out.println("[EdgeSync] Event stored id=" + saved.getId() + ", product update: " + statusUpdate + ", txHash: " + txHash);
        return ResponseEntity.ok(response);
    }

    // ─── Batch sync (multiple events) ────────────────────────────────

    @PostMapping("/sync/batch")
    public ResponseEntity<Map<String, Object>> syncBatch(@RequestBody List<Map<String, String>> events) {
        System.out.println("[EdgeSync] Received batch of " + events.size() + " events");

        int successCount = 0;
        int failCount = 0;
        List<Long> syncedIds = new ArrayList<>();

        for (Map<String, String> payload : events) {
            try {
                String batchId = payload.get("batchId");
                String nodeId = payload.get("nodeId");
                String eventType = payload.getOrDefault("eventType", "SCAN");
                String eventTime = payload.get("eventTime");
                String nodeRole = payload.getOrDefault("nodeRole", "");
                String operatorId = payload.getOrDefault("operatorId", "");
                String operatorName = payload.getOrDefault("operatorName", "");
                String location = payload.getOrDefault("location", "");

                if (batchId == null || nodeId == null || eventTime == null) {
                    failCount++;
                    continue;
                }

                ScanEventEntity scanEvent = new ScanEventEntity();
                scanEvent.setBatchId(batchId);
                scanEvent.setNodeId(nodeId);
                scanEvent.setEventType(eventType);
                scanEvent.setEventTime(eventTime);
                scanEvent.setReceivedAt(Instant.now().toString());

                ScanEventEntity saved = scanEventRepository.save(scanEvent);
                syncedIds.add(saved.getId());

                updateProductForRole(batchId, nodeRole, operatorId, operatorName, location, eventTime);
                successCount++;
            } catch (Exception e) {
                System.err.println("[EdgeSync] Error: " + e.getMessage());
                failCount++;
            }
        }

        Map<String, Object> response = new HashMap<>();
        response.put("synced", successCount);
        response.put("failed", failCount);
        response.put("syncedIds", syncedIds);

        return ResponseEntity.ok(response);
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

            // Record on blockchain and store txHash
            if (blockchainService.isAvailable()) {
                String eventDataStr = BlockchainService.buildEventData(
                        journeyType, eventTime, location, description);
                String txHash = blockchainService.recordEvent(product.getId(), eventDataStr);
                if (txHash != null) {
                    step.put("txHash", txHash);
                }
            }

            journeyList.add(step);

            product.setJourney(objectMapper.writeValueAsString(journeyList));
        } catch (Exception e) {
            System.err.println("[EdgeSync] Journey update failed: " + e.getMessage());
        }
    }
}
