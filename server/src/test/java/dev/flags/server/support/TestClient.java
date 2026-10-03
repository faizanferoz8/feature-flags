package dev.flags.server.support;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Plain HTTP against the running application, so the tests see what a real client sees. */
public class TestClient {

    public record Response(int status, JsonNode body, HttpHeaders headers) {}

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpClient client = HttpClient.newHttpClient();
    private final String baseUrl;

    public TestClient(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public Response get(String path, String bearer) {
        return send("GET", path, bearer, null, Map.of());
    }

    public Response get(String path, String bearer, Map<String, String> headers) {
        return send("GET", path, bearer, null, headers);
    }

    public Response post(String path, String bearer, Object body) {
        return send("POST", path, bearer, body, Map.of());
    }

    public Response put(String path, String bearer, Object body) {
        return send("PUT", path, bearer, body, Map.of());
    }

    public Response delete(String path, String bearer) {
        return send("DELETE", path, bearer, null, Map.of());
    }

    private Response send(String method, String path, String bearer, Object body, Map<String, String> headers) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path));
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        headers.forEach(request::header);
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            String json = body instanceof String raw ? raw : JSON.writeValueAsString(body);
            request.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(json));
        }
        try {
            HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            String text = response.body();
            JsonNode parsed = text == null || text.isBlank() ? JSON.nullNode() : JSON.readTree(text);
            return new Response(response.statusCode(), parsed, response.headers());
        } catch (IOException e) {
            throw new AssertionError(method + " " + path + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
