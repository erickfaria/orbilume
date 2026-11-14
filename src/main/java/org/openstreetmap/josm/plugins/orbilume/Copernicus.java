package org.openstreetmap.josm.plugins.orbilume;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.openstreetmap.josm.data.Bounds;

final class Copernicus {
    private static final String HOST = "https://api.explorer.eopf.copernicus.eu";
    private static final String COLLECTION = "sentinel-2-l2a";
    private static final int LIMIT = 20;

    record Scene(String id, Instant acquired, String platform, Double clouds) {
        String attribution() {
            return "Contains modified Copernicus Sentinel data " + acquired.atOffset(ZoneOffset.UTC).getYear();
        }

        @Override
        public String toString() {
            String cloudText = clouds == null ? "unknown" : String.format(Locale.ROOT, "%.1f%%", clouds);
            return acquired.atOffset(ZoneOffset.UTC).toLocalDate() + " • " + platform
                    + " • Cloud cover: " + cloudText + " • " + id;
        }
    }

    @FunctionalInterface
    interface Fetch {
        String get(URI uri) throws IOException, InterruptedException;
    }

    private final Fetch fetch;

    Copernicus() {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
        fetch = uri -> {
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20))
                    .header("Accept", "application/geo+json, application/json").GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                if (response.statusCode() != 200) {
                    throw new IOException("Copernicus catalog returned HTTP " + response.statusCode() + ".");
                }
                byte[] bytes = body.readNBytes(4_000_001);
                if (bytes.length > 4_000_000) {
                    throw new IOException("Copernicus catalog response is too large.");
                }
                return new String(bytes, StandardCharsets.UTF_8);
            }
        };
    }

    Copernicus(Fetch fetch) {
        this.fetch = fetch;
    }

    static String bbox(Bounds bounds) {
        double west = bounds.getMinLon();
        double south = bounds.getMinLat();
        double east = bounds.getMaxLon();
        double north = bounds.getMaxLat();
        if (!(west >= -180 && east <= 180 && west < east
                && south >= -90 && north <= 90 && south < north)) {
            throw new IllegalArgumentException("Zoom into a map area that does not cross the antimeridian.");
        }
        return String.format(Locale.ROOT, "%.7f,%.7f,%.7f,%.7f", west, south, east, north);
    }

    static URI searchUri(Bounds bounds, LocalDate start, LocalDate end) {
        if (end.isBefore(start)) {
            throw new IllegalArgumentException("End date must be on or after the start date.");
        }
        String interval = start + "T00:00:00Z/" + end + "T23:59:59.999999999Z";
        return URI.create(HOST + "/stac/search?collections=" + COLLECTION + "&bbox=" + bbox(bounds)
                + "&datetime=" + URLEncoder.encode(interval, StandardCharsets.UTF_8) + "&limit=" + LIMIT);
    }

    List<Scene> search(URI uri, int maxCloud) throws IOException, InterruptedException {
        return parse(fetch.get(uri)).stream()
                .filter(scene -> scene.clouds() == null || scene.clouds() <= maxCloud)
                .sorted(Comparator.comparing(Scene::acquired).reversed().thenComparing(Scene::id))
                .toList();
    }

    static List<Scene> parse(String response) throws IOException {
        try (var reader = Json.createReader(new StringReader(response))) {
            JsonObject collection = reader.readObject();
            JsonArray features = collection.getJsonArray("features");
            if (!"FeatureCollection".equals(collection.getString("type", "")) || features == null) {
                throw new IllegalArgumentException("Expected a STAC FeatureCollection.");
            }
            List<Scene> scenes = new ArrayList<>();
            for (JsonValue feature : features) {
                JsonObject item = feature.asJsonObject();
                String id = item.getString("id");
                if (!validId(id)) {
                    throw new IllegalArgumentException("Invalid STAC item ID.");
                }
                JsonObject properties = item.getJsonObject("properties");
                Instant acquired = Instant.parse(properties.getString("datetime"));
                String platform = properties.getString("platform", "Sentinel-2");
                JsonValue value = properties.get("eo:cloud_cover");
                Double clouds = value instanceof JsonNumber number ? number.doubleValue() : null;
                if (clouds != null && !(clouds >= 0 && clouds <= 100)) {
                    clouds = null;
                }
                scenes.add(new Scene(id, acquired, platform, clouds));
            }
            return List.copyOf(scenes);
        } catch (RuntimeException e) {
            throw new IOException("Copernicus returned an invalid STAC response.", e);
        } catch (LinkageError e) {
            // Debian r19369 omits the Parsson error-message resource bundle.
            if (e instanceof ExceptionInInitializerError
                    && e.getCause() instanceof java.util.MissingResourceException
                    || e instanceof NoClassDefFoundError && e.getMessage() != null
                    && e.getMessage().contains("org.eclipse.parsson.JsonMessages")) {
                throw new IOException("Copernicus returned an invalid STAC response.", e);
            }
            throw e;
        }
    }

    private static boolean validId(String id) {
        return id.matches("[A-Za-z0-9_-][A-Za-z0-9_.-]{0,220}");
    }

    static String tileTemplate(String id) {
        if (!validId(id)) {
            throw new IllegalArgumentException("Invalid STAC item ID.");
        }
        // The live Raster OpenAPI and STAC xyz link use the assets syntax.
        return HOST + "/raster/collections/" + COLLECTION + "/items/" + id
                + "/tiles/WebMercatorQuad/{zoom}/{x}/{y}.png"
                + "?assets=reflectance%7Cbands%3Db04%2Cb03%2Cb02&rescale=0%2C1"
                + "&color_formula=gamma%20rgb%201.3%2C%20sigmoidal%20rgb%206%200.1%2C%20saturation%201.2";
    }
}
