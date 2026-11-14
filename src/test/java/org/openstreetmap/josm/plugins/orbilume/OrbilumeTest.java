package org.openstreetmap.josm.plugins.orbilume;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.jar.JarFile;
import javax.imageio.ImageIO;
import org.openstreetmap.gui.jmapviewer.tilesources.TemplatedTMSTileSource;
import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.spi.preferences.Config;
import org.openstreetmap.josm.spi.preferences.MemoryPreferences;

public final class OrbilumeTest {
    private static final String FIXTURE = """
            {"type":"FeatureCollection","features":[
              {"id":"S2A_A","properties":{"datetime":"2026-09-25T10:00:00Z","platform":"sentinel-2a","eo:cloud_cover":12.5}},
              {"id":"S2B_B","properties":{"datetime":"2026-09-26T10:00:00Z","eo:cloud_cover":null}},
              {"id":"S2C_C","properties":{"datetime":"2026-09-27T10:00:00Z","eo:cloud_cover":99}}
            ]}
            """;

    public static void main(String[] args) throws Exception {
        Config.setPreferencesInstance(new MemoryPreferences());
        Bounds bounds = new Bounds(48.1, 16.35, 48.3, 16.5);
        URI request = Copernicus.searchUri(bounds, LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-30"));
        check(request.getQuery().contains("bbox=16.3500000,48.1000000,16.5000000,48.3000000"),
                "STAC bbox uses longitude, latitude order");
        check(request.getQuery().contains("limit=20") && request.getQuery().contains("datetime=2026-09-01"),
                "Search has a bounded page and date interval");
        try {
            Copernicus.bbox(new Bounds(0, 0, 0, 0));
            throw new AssertionError("Zero-area bounds accepted");
        } catch (IllegalArgumentException expected) {
            check(true, "Zero-area bounds rejected");
        }
        var scenes = new Copernicus(uri -> FIXTURE).search(request, 20);
        check(scenes.size() == 2 && scenes.get(0).clouds() == null && scenes.get(1).clouds() == 12.5,
                "STAC metadata, missing cloud cover, filtering and ordering");
        check(scenes.get(1).acquired().toString().startsWith("2026-09-25")
                && scenes.get(1).platform().equals("sentinel-2a")
                && scenes.get(1).attribution().endsWith("2026"), "Date, mission and attribution");
        check(Copernicus.parse("{\"type\":\"FeatureCollection\",\"features\":[]}").isEmpty(),
                "Empty catalog results");
        for (String invalid : new String[]{"<html>", "{}", FIXTURE.replace("S2A_A", "../outside")}) {
            expectIOException(() -> Copernicus.parse(invalid), "Invalid catalog response");
        }
        expectIOException(() -> new Copernicus(uri -> {
            throw new IOException("offline");
        }).search(request, 30), "Network errors propagate to the dialog");
        var imagery = OrbilumePlugin.imageryInfo(scenes.get(1));
        check(imagery.getImageryType() == org.openstreetmap.josm.data.imagery.ImageryInfo.ImageryType.TMS,
                "JOSM selects a native TMS layer");
        var tiles = new TemplatedTMSTileSource(imagery);
        String tile = tiles.getTileUrl(11, 1117, 710);
        check(tile.contains("/tiles/WebMercatorQuad/11/1117/710.png")
                && URI.create(tile).getQuery().contains("assets=reflectance|bands=b04,b03,b02"),
                "JOSM expands the XYZ template with explicit RGB");
        check(imagery.getAttributionText(11, null, null).contains("Copernicus"), "Native layer attribution");
        try (JarFile jar = new JarFile("build/orbilume.jar")) {
            var metadata = jar.getManifest().getMainAttributes();
            String plugin = metadata.getValue("Plugin-Class");
            check("19369".equals(metadata.getValue("Plugin-Mainversion"))
                    && jar.getEntry(plugin.replace('.', '/') + ".class") != null
                    && jar.getEntry("LICENSE") != null, "Installable JOSM plugin package");
        }
        if (args.length == 1 && args[0].equals("--live")) {
            live(request);
        }
    }

    private static void live(URI request) throws Exception {
        var catalog = new Copernicus();
        var scenes = catalog.search(request, 100);
        check(!scenes.isEmpty(), "Live STAC search");
        IOException lastFailure = null;
        for (var scene : scenes.stream().limit(3).toList()) {
            var tiles = new TemplatedTMSTileSource(OrbilumePlugin.imageryInfo(scene));
            try {
                var tile = tiles.latLonToTileXY(48.2, 16.42, 11);
                int x = (int) Math.floor(tile.getX());
                int y = (int) Math.floor(tile.getY());
                var nw = tiles.tileXYToLatLon(x, y, 11);
                var se = tiles.tileXYToLatLon(x + 1, y + 1, 11);
                check(nw.getLat() >= 48.2 && se.getLat() <= 48.2
                        && nw.getLon() <= 16.42 && se.getLon() >= 16.42,
                        "JOSM Web Mercator tile covers the sample point");
                URI uri = URI.create(tiles.getTileUrl(11, x, y));
                var response = HttpClient.newHttpClient().send(
                        HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).GET().build(),
                        HttpResponse.BodyHandlers.ofByteArray());
                var png = response.statusCode() == 200
                        ? ImageIO.read(new java.io.ByteArrayInputStream(response.body())) : null;
                if (png != null && png.getWidth() == 256 && png.getHeight() == 256
                        && (png.getRGB(128, 128) >>> 24) > 0) {
                    check(true, "Live RGB PNG decoded from native JOSM URL for " + scene.id());
                    return;
                }
                lastFailure = new IOException("Tile HTTP " + response.statusCode() + " or empty imagery for " + scene.id());
            } catch (IOException e) {
                lastFailure = e;
            }
        }
        throw new IOException("No valid Copernicus sample tile found.", lastFailure);
    }

    private static void check(boolean condition, String name) {
        if (!condition) {
            throw new AssertionError(name);
        }
        System.out.println("PASS " + name);
    }

    @FunctionalInterface
    private interface Check {
        void run() throws IOException, InterruptedException;
    }

    private static void expectIOException(Check test, String name) throws InterruptedException {
        try {
            test.run();
            throw new AssertionError(name);
        } catch (IOException expected) {
            check(true, name);
        }
    }
}
