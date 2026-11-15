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
import javax.net.ssl.HttpsURLConnection;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import org.openstreetmap.josm.data.Bounds;

final class Copernicus {
    private static final String HOST = "https://api.explorer.eopf.copernicus.eu";
    private static final String COLLECTION = "sentinel-2-l2a";
    private static final int LIMIT = 20;
    static final String USER_AGENT = "Orbilume (+https://github.com/erickfaria/orbilume)";

    enum Provider {
        EARTH_SEARCH("Earth Search (Element 84)", "https://earth-search.aws.element84.com/v1/search", "sentinel-2-c1-l2a"),
        EOPF("Copernicus EOPF Explorer", HOST + "/stac/search", COLLECTION);

        final String label;
        final String endpoint;
        final String collection;

        Provider(String label, String endpoint, String collection) {
            this.label = label;
            this.endpoint = endpoint;
            this.collection = collection;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    enum Composite {
        NATURAL("Natural color", "b04,b03,b02", "0,1"),
        VEGETATION("Vegetation (near infrared)", "b08,b04,b03", "0,0.45"),
        SWIR("Soil and vegetation (SWIR)", "b12,b8a,b04", "0,0.45");

        private final String label;
        private final String bands;
        private final String range;

        Composite(String label, String bands, String range) {
            this.label = label;
            this.bands = bands;
            this.range = range;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    record Scene(String id, Instant acquired, String platform, Double clouds, Provider provider,
                 String crs, Map<String, EarthRaster.Asset> assets) {
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
        fetch = uri -> {
            HttpsURLConnection connection = (HttpsURLConnection) uri.toURL().openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(15000);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty("Accept", "application/geo+json, application/json");
            try {
                int status = connection.getResponseCode();
                if (status == 429) {
                    throw new IOException("Imagery catalog is rate-limiting requests. Wait before searching again.");
                }
                if (status != 200) {
                    throw new IOException("Imagery catalog returned HTTP " + status + ".");
                }
                try (InputStream body = connection.getInputStream()) {
                    byte[] bytes = body.readNBytes(4_000_001);
                    if (bytes.length > 4_000_000) {
                        throw new IOException("Imagery catalog response is too large.");
                    }
                    return new String(bytes, StandardCharsets.UTF_8);
                }
            } finally {
                connection.disconnect();
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

    static Bounds parseAoi(String text) {
        if (text.length() > 128) {
            throw new IllegalArgumentException("Enter west, south, east, north in decimal degrees.");
        }
        String[] parts = text.strip().split(",", -1);
        if (parts.length != 4) {
            throw new IllegalArgumentException("Enter west, south, east, north in decimal degrees.");
        }
        double[] values = new double[4];
        for (int i = 0; i < values.length; i++) {
            String part = parts[i].strip();
            if (!part.matches("[+-]?[0-9]{1,3}(\\.[0-9]{1,12})?")) {
                throw new IllegalArgumentException("Use decimal degrees with a period and comma separators.");
            }
            values[i] = Double.parseDouble(part);
        }
        Bounds bounds = new Bounds(values[1], values[0], values[3], values[2], false);
        bbox(bounds);
        return bounds;
    }

    static URI searchUri(Bounds bounds, LocalDate start, LocalDate end, int maxCloud, Provider provider) {
        if (end.isBefore(start)) {
            throw new IllegalArgumentException("End date must be on or after the start date.");
        }
        if (maxCloud < 0 || maxCloud > 100) {
            throw new IllegalArgumentException("Cloud cover must be from 0 to 100.");
        }
        String interval = start + "T00:00:00Z/" + end + "T23:59:59.999999999Z";
        String query = "{\"eo:cloud_cover\":{\"lte\":" + maxCloud + "}}";
        return URI.create(provider.endpoint + "?collections=" + provider.collection + "&bbox=" + bbox(bounds)
                + "&datetime=" + encode(interval) + "&limit=" + LIMIT
                + "&query=" + encode(query) + "&sortby=" + encode("+properties.eo:cloud_cover"));
    }

    List<Scene> search(Bounds bounds, LocalDate start, LocalDate end, int maxCloud, Provider provider)
            throws IOException, InterruptedException {
        return parse(fetch.get(searchUri(bounds, start, end, maxCloud, provider)), provider).stream()
                .filter(scene -> scene.clouds() != null && scene.clouds() <= maxCloud)
                .sorted(Comparator.comparing(Scene::clouds)
                        .thenComparing(Scene::acquired, Comparator.reverseOrder()).thenComparing(Scene::id))
                .toList();
    }

    static List<Scene> parse(String response) throws IOException {
        return parse(response, Provider.EOPF);
    }

    static List<Scene> parse(String response, Provider provider) throws IOException {
        try (var reader = Json.createReader(new StringReader(response))) {
            JsonObject collection = reader.readObject();
            JsonArray features = collection.getJsonArray("features");
            if (!"FeatureCollection".equals(collection.getString("type", "")) || features == null || features.size() > LIMIT) {
                throw new IllegalArgumentException("Expected a STAC FeatureCollection.");
            }
            List<Scene> scenes = new ArrayList<>();
            for (JsonValue feature : features) {
                JsonObject item = feature.asJsonObject();
                if (!provider.collection.equals(item.getString("collection", ""))) {
                    continue;
                }
                String id = item.getString("id");
                if (!validId(id)) {
                    throw new IllegalArgumentException("Invalid STAC item ID.");
                }
                JsonObject properties = item.getJsonObject("properties");
                Instant acquired = Instant.parse(properties.getString("datetime"));
                JsonValue mission = properties.get("platform");
                String platform = mission instanceof jakarta.json.JsonString str
                        && str.getString().matches("sentinel-2[a-z]") ? str.getString() : "Sentinel-2";
                JsonValue value = properties.get("eo:cloud_cover");
                Double clouds = value instanceof JsonNumber number ? number.doubleValue() : null;
                if (clouds != null && !(clouds >= 0 && clouds <= 100)) {
                    clouds = null;
                }
                if (provider == Provider.EARTH_SEARCH) {
                    String crs = properties.getString("proj:code", "");
                    if (crs.isEmpty() && properties.get("proj:epsg") instanceof JsonNumber epsg) {
                        crs = "EPSG:" + epsg.intValueExact();
                    }
                    if (crs.matches("EPSG:32[67](0[1-9]|[1-5][0-9]|60)")) {
                        var assets = EarthRaster.assets(item.getJsonObject("assets"), id);
                        if (assets.containsKey("visual")) {
                            scenes.add(new Scene(id, acquired, platform, clouds, provider, crs, assets));
                        }
                    }
                } else {
                    scenes.add(new Scene(id, acquired, platform, clouds, provider, "", Map.of()));
                }
            }
            return List.copyOf(scenes);
        } catch (RuntimeException e) {
            throw new IOException("Imagery catalog returned an invalid STAC response.", e);
        } catch (LinkageError e) {
            // Debian r19369 omits the Parsson error-message resource bundle.
            if (e instanceof ExceptionInInitializerError
                    && e.getCause() instanceof java.util.MissingResourceException
                    || e instanceof NoClassDefFoundError && e.getMessage() != null
                    && e.getMessage().contains("org.eclipse.parsson.JsonMessages")) {
                throw new IOException("Imagery catalog returned an invalid STAC response.", e);
            }
            throw e;
        }
    }

    private static boolean validId(String id) {
        return id.matches("[A-Za-z0-9_-][A-Za-z0-9_.-]{0,220}");
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    static String tileTemplate(String id, Composite composite) {
        if (!validId(id)) {
            throw new IllegalArgumentException("Invalid STAC item ID.");
        }
        // The live Raster OpenAPI and STAC xyz link use the assets syntax.
        return HOST + "/raster/collections/" + COLLECTION + "/items/" + id
                + "/tiles/WebMercatorQuad/{zoom}/{x}/{y}.png"
                + "?assets=" + encode("reflectance|bands=" + composite.bands) + "&rescale=" + encode(composite.range)
                + "&color_formula=gamma%20rgb%201.3%2C%20sigmoidal%20rgb%206%200.1%2C%20saturation%201.2";
    }
}
