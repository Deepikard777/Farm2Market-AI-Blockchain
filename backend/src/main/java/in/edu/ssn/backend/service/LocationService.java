package in.edu.ssn.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class LocationService {

    private static final Logger log = LoggerFactory.getLogger(LocationService.class);
    private static final String GEOAPIFY_BASE_URL = "https://api.geoapify.com/v1/geocode/autocomplete";

    @Value("${geoapify.api.key:${GEOAPIFY_API_KEY:}}")
    private String geoapifyApiKey;

    /**
     * Get location suggestions from Geoapify API
     * 
     * @param searchText The text to search for
     * @return List of location suggestions with formatted addresses
     */
    public List<Map<String, Object>> getLocationSuggestions(String searchText) throws Exception {
        List<Map<String, Object>> suggestions = new ArrayList<>();

        if (searchText == null || searchText.trim().isEmpty()) {
            return suggestions;
        }

        String apiKey = (this.geoapifyApiKey != null && !this.geoapifyApiKey.trim().isEmpty())
                ? this.geoapifyApiKey.trim()
                : System.getenv("GEOAPIFY_API_KEY");

        if (apiKey == null || apiKey.trim().isEmpty()) {
            log.warn("Geoapify API key is not configured. Please set the GEOAPIFY_API_KEY environment variable or geoapify.api.key property.");
            return suggestions;
        }

        // Encode the search text for URL
        String encodedText = URLEncoder.encode(searchText.trim(), StandardCharsets.UTF_8);

        // Build the API URL
        String urlString = GEOAPIFY_BASE_URL + "?text=" + encodedText + "&apiKey=" + apiKey.trim();
        URL url = new URL(urlString);

        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "Farm2Market-Backend/1.0");
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);

        try {
            int responseCode = connection.getResponseCode();

            if (responseCode == HttpURLConnection.HTTP_OK) {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8));

                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();

                // Parse JSON response
                ObjectMapper mapper = new ObjectMapper();
                JsonNode root = mapper.readTree(response.toString());
                JsonNode features = root.get("features");

                if (features != null && features.isArray()) {
                    for (JsonNode feature : features) {
                        JsonNode properties = feature.get("properties");
                        if (properties != null) {
                            Map<String, Object> suggestion = new HashMap<>();

                            // Extract relevant fields
                            String formatted = properties.hasNonNull("formatted") ? properties.get("formatted").asText() : "";
                            String city = properties.hasNonNull("city") ? properties.get("city").asText() : "";
                            String state = properties.hasNonNull("state") ? properties.get("state").asText() : "";
                            String country = properties.hasNonNull("country") ? properties.get("country").asText() : "";
                            String postcode = properties.hasNonNull("postcode") ? properties.get("postcode").asText() : "";

                            // Get coordinates (from geometry coordinates or properties lat/lon)
                            Double lon = null;
                            Double lat = null;
                            JsonNode geometry = feature.get("geometry");
                            if (geometry != null && geometry.hasNonNull("coordinates")) {
                                JsonNode coords = geometry.get("coordinates");
                                if (coords.isArray() && coords.size() >= 2) {
                                    lon = coords.get(0).asDouble();
                                    lat = coords.get(1).asDouble();
                                }
                            }
                            if (lon == null && properties.hasNonNull("lon")) {
                                lon = properties.get("lon").asDouble();
                            }
                            if (lat == null && properties.hasNonNull("lat")) {
                                lat = properties.get("lat").asDouble();
                            }
                            if (lon != null && lat != null) {
                                suggestion.put("longitude", lon);
                                suggestion.put("latitude", lat);
                            }

                            suggestion.put("formatted", formatted);
                            suggestion.put("city", city);
                            suggestion.put("state", state);
                            suggestion.put("country", country);
                            suggestion.put("postcode", postcode);

                            // Create a display label
                            StringBuilder label = new StringBuilder();
                            if (!city.isEmpty()) {
                                label.append(city);
                            }
                            if (!state.isEmpty()) {
                                if (label.length() > 0)
                                    label.append(", ");
                                label.append(state);
                            }
                            if (!country.isEmpty()) {
                                if (label.length() > 0)
                                    label.append(", ");
                                label.append(country);
                            }

                            suggestion.put("label", label.length() > 0 ? label.toString() : formatted);

                            suggestions.add(suggestion);
                        }
                    }
                }
            } else {
                StringBuilder errorBody = new StringBuilder();
                try (BufferedReader errReader = new BufferedReader(
                        new InputStreamReader(
                                connection.getErrorStream() != null ? connection.getErrorStream() : connection.getInputStream(),
                                StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = errReader.readLine()) != null) {
                        errorBody.append(line);
                    }
                } catch (Exception ignored) {
                }
                log.error("Geoapify API request failed with HTTP {}: {}", responseCode, errorBody);
            }
        } finally {
            connection.disconnect();
        }

        return suggestions;
    }
}

