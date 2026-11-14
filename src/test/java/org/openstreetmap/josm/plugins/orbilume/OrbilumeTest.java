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
        URI request = Copernicus.searchUri(bounds, start, end, 20, Copernicus.Provider.EOPF);
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
        var scenes = new Copernicus(uri -> FIXTURE).search(bounds, start, end, 20, Copernicus.Provider.EOPF);
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
        }).search(bounds, start, end, 30, Copernicus.Provider.EOPF), "Network errors propagate to the dialog");
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
        testTiffPredictor();
        check(EarthRaster.stretch(1000, .0001, -.1) == 0
                && EarthRaster.stretch(5500, .0001, -.1) == 255, "Sentinel-2 reflectance scale and offset");
        Bounds brazil = Copernicus.parseAoi("-45.6389308,-20.1919466,-45.5574714,-20.1443104");
        URI earth = Copernicus.searchUri(brazil, start, end, 20, Copernicus.Provider.EARTH_SEARCH);
        check("earth-search.aws.element84.com".equals(earth.getHost())
                && earth.getQuery().contains("collections=sentinel-2-c1-l2a"), "Earth Search collection");
        String scene = "S2B_T23KMT_20260927T131241_L2A";
        String path = "/sentinel-2-c1-l2a/23/K/MT/2026/9/" + scene + "/TCI.tif";
        String host = "https://e84-earth-search-sentinel-data.s3.us-west-2.amazonaws.com";
        check(EarthRaster.allowed(URI.create(host + path), scene, "TCI")
                && !EarthRaster.allowed(URI.create(host + path + "?extra=1"), scene, "TCI")
                && !EarthRaster.allowed(URI.create("https://example.org" + path), scene, "TCI")
                && !EarthRaster.allowed(URI.create(host + "/x/.." + path), scene, "TCI"),
                "Only expected public Earth Search assets are accepted");
        if (args.length == 1 && args[0].equals("--live-earth")) {
            var brazilScenes = new Copernicus().search(brazil, LocalDate.parse("2026-09-15"),
                    LocalDate.parse("2026-11-14"), 30, Copernicus.Provider.EARTH_SEARCH);
            check(!brazilScenes.isEmpty(), "Earth Search returns scenes for the Brazil AOI");
            for (var composite : Copernicus.Composite.values()) {
                Bounds pilot = Copernicus.parseAoi("-45.6069,-20.1737,-45.5985,-20.1674");
                var crop = EarthRaster.load(brazilScenes.get(0), composite, pilot);
                var pixels = crop.pixels();
                check(pixels.getWidth() > 20 && pixels.getHeight() > 20
                        && (pixels.getRGB(pixels.getWidth() / 2, pixels.getHeight() / 2) >>> 24) != 0,
                        "Real georeferenced crop for " + composite);
                ImageIO.write(pixels, "PNG", new java.io.File("build/earth-" + composite.name() + ".png"));
            }
        }
        if (args.length == 1 && args[0].equals("--live")) {
            live(bounds, start, end);
        }
    }

    private static void testTiffPredictor() throws IOException {
        int[][] tags = {{256, 16}, {257, 16}, {258, 16}, {259, 8}, {262, 1}, {277, 1},
                {317, 2}, {322, 16}, {323, 16}, {324, 0}, {325, 0}};
        var samples = java.nio.ByteBuffer.allocate(512).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                samples.putShort((short) (x == 0 ? 1000 + y : 10));
            }
        }
        var encoded = new java.io.ByteArrayOutputStream();
        try (var deflate = new java.util.zip.DeflaterOutputStream(encoded)) {
            deflate.write(samples.array());
        }
        int tileOffset = 8 + 2 + tags.length * 12 + 4;
        var file = java.nio.ByteBuffer.allocate(tileOffset + encoded.size()).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        file.putShort((short) 0x4949).putShort((short) 42).putInt(8).putShort((short) tags.length);
        for (int[] tag : tags) {
            boolean offset = tag[0] == 324;
            boolean size = tag[0] == 325;
            file.putShort((short) tag[0]).putShort((short) (offset || size ? 4 : 3)).putInt(1)
                    .putInt(offset ? tileOffset : size ? encoded.size() : tag[1]);
        }
        file.putInt(0).put(encoded.toByteArray());
        try (var input = new javax.imageio.stream.MemoryCacheImageInputStream(
                new java.io.ByteArrayInputStream(file.array()))) {
            var asset = new EarthRaster.Asset(null, 16, 16, 0, 0, 10, .0001, -.1);
            var raster = EarthRaster.readBand(input, asset, new java.awt.Rectangle(4, 3, 2, 2));
            check(raster.getSample(0, 0, 0) == 1043 && raster.getSample(1, 1, 0) == 1054,
                    "Cropped 16-bit TIFF reverses the horizontal predictor");
        }
    }

    private static void live(Bounds bounds, LocalDate start, LocalDate end) throws Exception {
        var catalog = new Copernicus();
        var scenes = catalog.search(bounds, start, end, 30, Copernicus.Provider.EOPF);
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
