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
        LocalDate start = LocalDate.parse("2026-09-01");
        LocalDate end = LocalDate.parse("2026-09-30");
        URI request = Copernicus.searchUri(bounds, start, end, 20);
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
        check(request.getQuery().contains("query={\"eo:cloud_cover\":{\"lte\":20}}")
                && request.getQuery().contains("sortby=+properties.eo:cloud_cover"),
                "Cloud threshold and sort are sent to the catalog");
        check(Copernicus.bbox(Copernicus.parseAoi("16.35, 48.1, 16.5, 48.3")).equals(Copernicus.bbox(bounds)),
                "Pasted AOI preserves coordinate order");
        for (String input : new String[]{"20,1,10,2", "5,2,10,1", "5,1,10,90.1",
                "NaN,1,10,2", "5,1,10,2&limit=1000", "5,1,10,1"}) {
            try {
                Copernicus.parseAoi(input);
                throw new AssertionError("Invalid AOI accepted: " + input);
            } catch (IllegalArgumentException expected) {
                check(true, "Invalid AOI rejected");
            }
        }
        var scenes = new Copernicus(uri -> FIXTURE).search(bounds, start, end, 20);
        check(scenes.size() == 1 && scenes.get(0).clouds() == 12.5
                && Copernicus.parse(FIXTURE).get(1).clouds() == null,
                "Cloud filter excludes unknown coverage and enforces threshold locally");
        check(scenes.get(0).acquired().toString().startsWith("2026-09-25")
                && scenes.get(0).platform().equals("sentinel-2a")
                && scenes.get(0).attribution().endsWith("2026"), "Date, mission and attribution");
        check(Copernicus.parse("{\"type\":\"FeatureCollection\",\"features\":[]}").isEmpty(),
                "Empty catalog results");
        for (String invalid : new String[]{"<html>", "{}", FIXTURE.replace("S2A_A", "../outside")}) {
            expectIOException(() -> Copernicus.parse(invalid), "Invalid catalog response");
        }
        expectIOException(() -> new Copernicus(uri -> {
            throw new IOException("offline");
        }).search(bounds, start, end, 30), "Network errors propagate to the dialog");
        var imagery = OrbilumePlugin.imageryInfo(scenes.get(0), Copernicus.Composite.NATURAL);
        check(imagery.getImageryType() == org.openstreetmap.josm.data.imagery.ImageryInfo.ImageryType.TMS,
                "JOSM selects a native TMS layer");
        var tiles = new TemplatedTMSTileSource(imagery);
        String tile = tiles.getTileUrl(11, 1117, 710);
        check(tile.contains("/tiles/WebMercatorQuad/11/1117/710.png")
                && URI.create(tile).getQuery().contains("assets=reflectance|bands=b04,b03,b02"),
                "JOSM expands the XYZ template with explicit RGB");
        check(URI.create(Copernicus.tileTemplate("scene", Copernicus.Composite.VEGETATION)
                .replace("{zoom}", "11").replace("{x}", "1117").replace("{y}", "710"))
                .getQuery().contains("bands=b08,b04,b03"), "Vegetation band order");
        check(OrbilumePlugin.imageryInfo(scenes.get(0), Copernicus.Composite.SWIR).getUrl()
                .contains("bands%3Db12%2Cb8a%2Cb04"), "SWIR band order");
        check(imagery.getAttributionText(11, null, null).contains("Copernicus"), "Native layer attribution");
        try (JarFile jar = new JarFile("build/orbilume.jar")) {
            var metadata = jar.getManifest().getMainAttributes();
            String plugin = metadata.getValue("Plugin-Class");
            check("19369".equals(metadata.getValue("Plugin-Mainversion"))
                    && jar.getEntry(plugin.replace('.', '/') + ".class") != null
                    && jar.getEntry("LICENSE") != null, "Installable JOSM plugin package");
        }
        if (args.length == 1 && args[0].equals("--live")) {
            live(bounds, start, end);
        }
    }

    private static void live(Bounds bounds, LocalDate start, LocalDate end) throws Exception {
        var catalog = new Copernicus();
        var scenes = catalog.search(bounds, start, end, 30);
        check(!scenes.isEmpty(), "Live STAC search");
        IOException lastFailure = null;
        for (var scene : scenes.stream().limit(3).toList()) {
            var tiles = new TemplatedTMSTileSource(OrbilumePlugin.imageryInfo(scene, Copernicus.Composite.NATURAL));
            try {
                var tile = tiles.latLonToTileXY(48.2, 16.42, 11);
                int x = (int) Math.floor(tile.getX());
                int y = (int) Math.floor(tile.getY());
                var nw = tiles.tileXYToLatLon(x, y, 11);
                var se = tiles.tileXYToLatLon(x + 1, y + 1, 11);
                check(nw.getLat() >= 48.2 && se.getLat() <= 48.2
                        && nw.getLon() <= 16.42 && se.getLon() >= 16.42,
                        "JOSM Web Mercator tile covers the sample point");
                for (var composite : Copernicus.Composite.values()) {
                    var source = new TemplatedTMSTileSource(OrbilumePlugin.imageryInfo(scene, composite));
                    URI uri = URI.create(source.getTileUrl(11, x, y));
                    var response = HttpClient.newHttpClient().send(
                            HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).GET().build(),
                            HttpResponse.BodyHandlers.ofByteArray());
                    var png = response.statusCode() == 200
                            ? ImageIO.read(new java.io.ByteArrayInputStream(response.body())) : null;
                    if (png == null || png.getWidth() != 256 || png.getHeight() != 256
                            || (png.getRGB(128, 128) >>> 24) == 0) {
                        throw new IOException("Tile HTTP " + response.statusCode() + " or empty imagery for " + scene.id());
                    }
                    check(true, "Live PNG decoded for " + composite + " • " + scene.id());
                }
                return;
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
