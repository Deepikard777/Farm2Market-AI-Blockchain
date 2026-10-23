package in.edu.ssn.backend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.edu.ssn.backend.entity.ProductEntity;
import in.edu.ssn.backend.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

public class ProductTraceabilityTest {

    @Mock
    private ProductRepository productRepository;

    @InjectMocks
    private ProductController productController;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        ReflectionTestUtils.setField(productController, "objectMapper", objectMapper);
    }

    @Test
    void testGetProductTraceability_Success_StripsConfidentialFields() {
        String productId = "fe734f44-1e49-4754-9b11-52954d1195e7";

        ProductEntity entity = new ProductEntity();
        entity.setId(productId);
        entity.setProductName("Organic Basmati Rice");
        entity.setQuantity(500.0);
        entity.setUnit("kg");
        entity.setProductionLocation("Madurai, Tamil Nadu");
        entity.setFarmerName("Ramesh Kumar");
        entity.setStatus("AT_SELLER");
        entity.setCreatedAt("2026-10-20T10:00:00Z");
        entity.setCurrentLocation("Koyambedu, Chennai");
        entity.setSellerName("Green Grocers");
        entity.setSellerLocation("Chennai");
        entity.setTransporterName("FastLogistics");
        entity.setQrCode("http://localhost:5173/product/" + productId);

        // Confidential internal fields that MUST be stripped:
        entity.setFarmerId("internal-farmer-uuid-1234");
        entity.setSellerId("internal-seller-uuid-5678");
        entity.setTransporterId("internal-transporter-uuid-9999");
        entity.setFarmerPrice(45.0);
        entity.setSellerPrice(70.0);
        entity.setTransporterCharge(500.0);

        // Journey step containing confidential internal fields:
        String rawJourney = "[{" +
                "\"id\":\"step-1\"," +
                "\"type\":\"LOCATION\"," +
                "\"timestamp\":\"2026-10-20T10:00:00Z\"," +
                "\"location\":\"Madurai\"," +
                "\"description\":\"Harvested and batched\"," +
                "\"status\":\"COMPLETED\"," +
                "\"txHash\":\"0xabc123\"," +
                "\"price\":45.0," +
                "\"transporterId\":\"internal-transporter-uuid-9999\"" +
                "}]";
        entity.setJourney(rawJourney);

        when(productRepository.findById(productId)).thenReturn(Optional.of(entity));

        ResponseEntity<?> response = productController.getProductTraceability(productId);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody() instanceof Map);

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();

        // 1. Verify approved traceability fields exist
        assertEquals(productId, body.get("id"));
        assertEquals("Organic Basmati Rice", body.get("productName"));
        assertEquals(500.0, body.get("quantity"));
        assertEquals("kg", body.get("unit"));
        assertEquals("Madurai, Tamil Nadu", body.get("productionLocation"));
        assertEquals("Ramesh Kumar", body.get("farmerName"));
        assertEquals("AT_SELLER", body.get("status"));
        assertEquals("Green Grocers", body.get("sellerName"));

        // 2. Verify confidential fields are STRIPPED
        assertFalse(body.containsKey("farmerId"), "farmerId should not be exposed on public traceability");
        assertFalse(body.containsKey("sellerId"), "sellerId should not be exposed on public traceability");
        assertFalse(body.containsKey("transporterId"), "transporterId should not be exposed on public traceability");
        assertFalse(body.containsKey("farmerPrice"), "farmerPrice must not be exposed to consumers");
        assertFalse(body.containsKey("sellerPrice"), "sellerPrice must not be exposed as private commercial field");
        assertFalse(body.containsKey("transporterCharge"), "transporterCharge must not be exposed");

        // 3. Verify journey steps are sanitized
        assertTrue(body.containsKey("journey"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> journey = (List<Map<String, Object>>) body.get("journey");
        assertEquals(1, journey.size());
        Map<String, Object> cleanStep = journey.get(0);
        assertEquals("LOCATION", cleanStep.get("type"));
        assertEquals("Madurai", cleanStep.get("location"));
        assertEquals("0xabc123", cleanStep.get("txHash"));

        assertFalse(cleanStep.containsKey("price"), "Journey step pricing must be stripped");
        assertFalse(cleanStep.containsKey("transporterId"), "Journey step transporterId must be stripped");
    }

    @Test
    void testGetProductTraceability_NotFound_Returns404() {
        String nonExistentId = "non-existent-batch-id";
        when(productRepository.findById(nonExistentId)).thenReturn(Optional.empty());

        ResponseEntity<?> response = productController.getProductTraceability(nonExistentId);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertNotNull(response.getBody());

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertEquals("Product not found", body.get("error"));
        assertEquals(nonExistentId, body.get("id"));
    }
}
