package org.openstreetmap.josm.plugins.orbilume;

import jakarta.json.JsonArray;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStreamImpl;
import javax.net.ssl.HttpsURLConnection;
import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.tools.Logging;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.projection.Projection;
import org.openstreetmap.josm.data.projection.Projections;

final class EarthRaster {
    private static final String HOST = "e84-earth-search-sentinel-data.s3.us-west-2.amazonaws.com";
    private static final int MAX_PIXELS = 2048;
    private static final Map<String, String> FILES = Map.of("visual", "TCI", "nir", "B08",
            "green", "B03", "red", "B04", "nir08", "B8A", "swir22", "B12", "scl", "SCL");

    record Asset(URI uri, int width, int height, double west, double north, double pixel,
                 double scale, double offset) {
        int x(EastNorth point) {
            return (int) Math.floor((point.east() - west) / pixel);
        }

        int y(EastNorth point) {
            return (int) Math.floor((north - point.north()) / pixel);
        }
    }

    record Image(BufferedImage pixels, Bounds bounds) {}

    record CloudCoverage(int clouds, int shadows, int valid, int samples) {
        double cloudPercent() { return 100.0 * clouds / valid; }
        double shadowPercent() { return 100.0 * shadows / valid; }
        double coveragePercent() { return 100.0 * valid / samples; }
    }

    record Preview(Image image, CloudCoverage coverage) {}

    static Map<String, Asset> assets(JsonObject source, String scene) {
        if (source == null) {
            return Map.of();
        }
        Map<String, Asset> result = new HashMap<>();
        for (var file : FILES.entrySet()) {
            try {
                var asset = parseAsset(source.getJsonObject(file.getKey()), scene, file.getValue());
                if (asset != null) {
                    result.put(file.getKey(), asset);
                }
            } catch (IllegalArgumentException | ArithmeticException | ClassCastException | NullPointerException e) {
                // An invalid optional asset cannot invalidate the other bands of a scene.
                Logging.debug(e);
            }
        }
        return Map.copyOf(result);
    }

    private static Asset parseAsset(JsonObject item, String scene, String filename) {
        if (item == null) {
            return null;
        }
        URI uri = URI.create(item.getString("href"));
        if (!allowed(uri, scene, filename)) {
            return null;
        }
        JsonArray shape = item.getJsonArray("proj:shape");
        JsonArray transform = item.getJsonArray("proj:transform");
        if (shape == null || shape.size() < 2 || transform == null || transform.size() < 6) {
            return null;
        }
        int height = shape.getJsonNumber(0).intValueExact();
        int width = shape.getJsonNumber(1).intValueExact();
        double pixel = number(transform, 0);
        double west = number(transform, 2);
        double north = number(transform, 5);
        if (width <= 0 || height <= 0 || width > 12000 || height > 12000
                || (pixel != 10 && pixel != 20) || number(transform, 1) != 0
                || number(transform, 3) != 0 || number(transform, 4) != -pixel
                || !(west >= 0 && west <= 1_000_000 && north >= 0 && north <= 10_000_000)) {
            return null;
        }
        JsonArray bands = item.getJsonArray("raster:bands");
        if (bands == null || bands.isEmpty()) {
            return null;
        }
        JsonObject band = bands.getJsonObject(0);
        double scale = band.get("scale") instanceof JsonNumber n ? n.doubleValue() : 1;
        double offset = band.get("offset") instanceof JsonNumber n ? n.doubleValue() : 0;
        if (!(scale > 0 && scale <= 1 && Math.abs(offset) <= 1)) {
            return null;
        }
        return new Asset(uri, width, height, west, north, pixel, scale, offset);
    }

    static boolean allowed(URI uri, String scene, String band) {
        return "https".equals(uri.getScheme()) && HOST.equals(uri.getHost())
                && uri.getPort() == -1 && uri.getRawUserInfo() == null && uri.getRawQuery() == null
                && uri.getRawFragment() == null && uri.getRawPath() != null
                && uri.getRawPath().matches("/sentinel-2-c1-l2a/[0-9]{1,2}/[A-Z]/[A-Z]{2}/[0-9]{4}/[0-9]{1,2}/"
                        + java.util.regex.Pattern.quote(scene + "/" + band + ".tif"));
    }

    private static double number(JsonArray array, int index) {
        return array.getJsonNumber(index).doubleValue();
    }

    static Image load(Copernicus.Scene scene, Copernicus.Composite composite, Bounds bounds) throws IOException {
        return render(scene, composite, bounds, false).image();
    }

    static Preview preview(Copernicus.Scene scene, Bounds bounds) throws IOException {
        return render(scene, Copernicus.Composite.NATURAL, bounds, true);
    }

    private static Preview render(Copernicus.Scene scene, Copernicus.Composite composite, Bounds bounds,
                                  boolean preview) throws IOException {
        if (bounds.getMaxLat() > 85 || bounds.getMinLat() < -85) {
            throw new IOException("The imagery crop must be within Web Mercator latitude limits.");
        }
        Projection web = Projections.getProjectionByCode("EPSG:3857");
        Projection utm = Projections.getProjectionByCode(scene.crs());
        EastNorth nw = web.latlon2eastNorth(new LatLon(bounds.getMaxLat(), bounds.getMinLon()));
        EastNorth se = web.latlon2eastNorth(new LatLon(bounds.getMinLat(), bounds.getMaxLon()));
        int resolution = composite == Copernicus.Composite.SWIR ? 20 : 10;
        int width = (int) Math.ceil((se.east() - nw.east()) / resolution);
        int height = (int) Math.ceil((nw.north() - se.north()) / resolution);
        if (width <= 0 || height <= 0 || width > MAX_PIXELS || height > MAX_PIXELS) {
            throw new IOException("Zoom in or use a smaller AOI (up to 2,048 pixels per side).");
        }
        if (preview) {
            double factor = Math.min(0.5, 384.0 / Math.max(width, height));
            width = Math.max(1, (int) Math.ceil(width * factor));
            height = Math.max(1, (int) Math.ceil(height * factor));
        }
        double dx = (se.east() - nw.east()) / width;
        double dy = (nw.north() - se.north()) / height;
        var keys = switch (composite) {
            case NATURAL -> List.of("visual");
            case VEGETATION -> List.of("nir", "red", "green");
            case SWIR -> List.of("swir22", "nir08", "red");
        };

        // Precompute the exact UTM location of each output pixel in the bounded crop.
        double[] eastings = new double[width * height];
        double[] northings = new double[width * height];
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        for (int row = 0; row < height; row++) {
            checkInterrupted();
            for (int col = 0; col < width; col++) {
                int i = row * width + col;
                var projected = utm.latlon2eastNorth(web.eastNorth2latlon(
                        new EastNorth(nw.east() + (col + .5) * dx, nw.north() - (row + .5) * dy)));
                eastings[i] = projected.east();
                northings[i] = projected.north();
                minX = Math.min(minX, eastings[i]);
                maxX = Math.max(maxX, eastings[i]);
                minY = Math.min(minY, northings[i]);
                maxY = Math.max(maxY, northings[i]);
            }
        }
        var inputs = new ArrayList<Window>();
        for (String key : keys) {
            Asset asset = scene.assets().get(key);
            if (asset == null) {
                throw new IOException("This scene is missing the " + key + " band.");
            }
            inputs.add(read(asset, region(asset, minX, minY, maxX, maxY), keys.size() == 1 ? 3 : 1));
        }
        CloudCoverage coverage = null;
        Asset scl = scene.assets().get("scl");
        if (preview && scl != null) {
            try {
                var mask = readTiff(scl, region(scl, minX, minY, maxX, maxY), 1);
                int clouds = 0, shadows = 0, valid = 0;
                for (int i = 0; i < eastings.length; i++) {
                    int code = mask.raw(eastings[i], northings[i], 0);
                    if (code >= 2 && code <= 11) {
                        valid++;
                        if (code == 3) {
                            shadows++;
                        } else if (code >= 8 && code <= 10) {
                            clouds++;
                        }
                    }
                }
                coverage = new CloudCoverage(clouds, shadows, valid, eastings.length);
            } catch (IOException e) {
                checkInterrupted();
                Logging.debug(e);
            }
        }
        int[] pixels = new int[eastings.length];
        boolean visible = false;
        for (int row = 0; row < height; row++) {
            checkInterrupted();
            for (int i = row * width; i < (row + 1) * width; i++) {
                int rgb = 0;
                for (int band = 0; band < 3; band++) {
                    boolean natural = keys.size() == 1;
                    int value = inputs.get(natural ? 0 : band).sample(eastings[i], northings[i], natural ? band : 0);
                    if (value < 0) {
                        rgb = -1;
                        break;
                    }
                    rgb = (rgb << 8) | value;
                }
                if (rgb >= 0 && (keys.size() != 1 || rgb != 0)) {
                    pixels[i] = 0xff000000 | rgb;
                    visible = true;
                }
            }
        }
        if (!visible) {
            throw new IOException("The selected scene has no image pixels in this AOI.");
        }
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, width, height, pixels, 0, width);
        return new Preview(new Image(image, bounds), coverage);
    }

    private static Rectangle region(Asset asset, double minX, double minY, double maxX, double maxY)
            throws IOException {
        int left = Math.max(0, asset.x(new EastNorth(minX, minY)));
        int top = Math.max(0, asset.y(new EastNorth(minX, maxY)));
        int right = Math.min(asset.width(), asset.x(new EastNorth(maxX, maxY)) + 1);
        int bottom = Math.min(asset.height(), asset.y(new EastNorth(maxX, minY)) + 1);
        if (right <= left || bottom <= top) {
            throw new IOException("The scene does not cover the selected AOI.");
        }
        if (right - left > 4608 || bottom - top > 4608) {
            throw new IOException("The source window is too large for a local crop.");
        }
        return new Rectangle(left, top, right - left, bottom - top);
    }

    private record Window(Asset asset, Rectangle area, Raster raster, boolean natural) {
        int sample(double east, double north, int band) {
            int value = raw(east, north, band);
            return natural || value < 0 ? value : value == 0 ? -1 : stretch(value, asset.scale(), asset.offset());
        }

        int raw(double east, double north, int band) {
            int x = (int) Math.floor((east - asset.west()) / asset.pixel());
            int y = (int) Math.floor((asset.north() - north) / asset.pixel());
            if (!area.contains(x, y)) {
                return -1;
            }
            return raster.getSample(x - area.x, y - area.y, band);
        }
    }

    static int stretch(int raw, double scale, double offset) {
        double fraction = (raw * scale + offset) / .45;
        return (int) Math.round(255 * Math.max(0, Math.min(1, fraction)));
    }

    private static Window read(Asset asset, Rectangle region, int bands) throws IOException {
        if (bands == 1) {
            try (var input = new RangeStream(asset.uri())) {
                return new Window(asset, region, readBand(input, asset, region), false);
            }
        }
        return readTiff(asset, region, bands);
    }

    private static Window readTiff(Asset asset, Rectangle region, int bands) throws IOException {
        ImageReader reader = ImageIO.getImageReadersByFormatName("TIFF").next();
        try (var input = new RangeStream(asset.uri())) {
            reader.setInput(input);
            if (reader.getWidth(0) != asset.width() || reader.getHeight(0) != asset.height()) {
                throw new IOException("TIFF dimensions do not match the catalog.");
            }
            var parameter = reader.getDefaultReadParam();
            parameter.setSourceRegion(region);
            Raster raster = reader.read(0, parameter).getRaster();
            if (raster.getNumBands() != bands) {
                throw new IOException("Unexpected number of TIFF bands.");
            }
            return new Window(asset, region, raster, bands == 3);
        } finally {
            reader.dispose();
        }
    }

    static Raster readBand(javax.imageio.stream.ImageInputStream input, Asset asset, Rectangle region) throws IOException {
        // JDK TIFF ImageIO cannot reverse the horizontal predictor on 16-bit samples.
        int order = input.readUnsignedShort();
        if (order == 0x4949) {
            input.setByteOrder(java.nio.ByteOrder.LITTLE_ENDIAN);
        } else if (order != 0x4d4d) {
            throw new IOException("Invalid TIFF byte order.");
        }
        if (input.readUnsignedShort() != 42) {
            throw new IOException("Expected a classic tiled TIFF.");
        }
        input.seek(input.readUnsignedInt());
        int entries = input.readUnsignedShort();
        if (entries > 128) {
            throw new IOException("Too many TIFF directory entries.");
        }
        Map<Integer, long[]> tags = new HashMap<>();
        for (int i = 0; i < entries; i++) {
            int tag = input.readUnsignedShort();
            int type = input.readUnsignedShort();
            long count = input.readUnsignedInt();
            long start = input.getStreamPosition();
            if (switch (tag) {
                case 256, 257, 258, 259, 262, 277, 284, 317, 322, 323, 324, 325, 339 -> true;
                default -> false;
            }) {
                int size = type == 3 ? 2 : type == 4 ? 4 : 0;
                if (size == 0 || count < 1 || count > 4096) {
                    throw new IOException("Unsupported TIFF directory entry.");
                }
                if (count * size > 4) {
                    input.seek(input.readUnsignedInt());
                }
                long[] values = new long[(int) count];
                for (int j = 0; j < count; j++) {
                    values[j] = size == 2 ? input.readUnsignedShort() : input.readUnsignedInt();
                }
                tags.put(tag, values);
            }
            input.seek(start + 4);
        }
        long compression = tag(tags, 259, 1);
        long predictor = tag(tags, 317, 1);
        if (tag(tags, 256, 0) != asset.width() || tag(tags, 257, 0) != asset.height()
                || tag(tags, 258, 0) != 16 || tag(tags, 262, 0) != 1 || tag(tags, 277, 1) != 1
                || tag(tags, 284, 1) != 1 || tag(tags, 339, 1) != 1
                || (compression != 8 && compression != 32946) || (predictor != 1 && predictor != 2)) {
            throw new IOException("Expected a deflate-compressed, unsigned 16-bit Sentinel-2 band.");
        }
        long tw = tag(tags, 322, 0), th = tag(tags, 323, 0);
        if (tw < 16 || tw > 1024 || th < 16 || th > 1024) {
            throw new IOException("Unsupported Sentinel-2 TIFF tile dimensions.");
        }
        int across = (asset.width() + (int) tw - 1) / (int) tw;
        int down = (asset.height() + (int) th - 1) / (int) th;
        long[] offsets = tags.get(324), lengths = tags.get(325);
        if (offsets == null || lengths == null || offsets.length != across * down || lengths.length != offsets.length) {
            throw new IOException("Invalid TIFF tile index.");
        }
        var raster = Raster.createInterleavedRaster(java.awt.image.DataBuffer.TYPE_USHORT,
                region.width, region.height, 1, null);
        int tileWidth = (int) tw, tileHeight = (int) th;
        int bytes = tileWidth * tileHeight * 2;
        for (int y = region.y / tileHeight; y <= (region.y + region.height - 1) / tileHeight; y++) {
            for (int x = region.x / tileWidth; x <= (region.x + region.width - 1) / tileWidth; x++) {
                int index = y * across + x;
                long compressed = lengths[index];
                if (offsets[index] <= 0 || compressed < 1 || compressed > bytes + 65536) {
                    throw new IOException("Invalid TIFF compressed tile range.");
                }
                input.seek(offsets[index]);
                byte[] encoded = new byte[(int) compressed];
                input.readFully(encoded);
                try (var inflater = new java.util.zip.InflaterInputStream(new java.io.ByteArrayInputStream(encoded))) {
                    byte[] decoded = inflater.readNBytes(bytes + 1);
                    if (decoded.length != bytes) {
                        throw new IOException("Invalid TIFF decoded tile size.");
                    }
                    var samples = java.nio.ByteBuffer.wrap(decoded).order(input.getByteOrder()).asShortBuffer();
                    Rectangle tile = new Rectangle(x * tileWidth, y * tileHeight, tileWidth, tileHeight);
                    Rectangle overlap = region.intersection(tile);
                    for (int row = overlap.y; row < overlap.y + overlap.height; row++) {
                        int running = 0;
                        int start = (row - tile.y) * tileWidth;
                        for (int col = tile.x; col < overlap.x + overlap.width; col++) {
                            int raw = Short.toUnsignedInt(samples.get(start + col - tile.x));
                            running = predictor == 2 ? (running + raw) & 0xffff : raw;
                            if (col >= overlap.x) {
                                raster.setSample(col - region.x, row - region.y, 0, running);
                            }
                        }
                    }
                }
            }
        }
        return raster;
    }

    private static long tag(Map<Integer, long[]> tags, int key, long fallback) {
        long[] values = tags.get(key);
        return values == null ? fallback : values[0];
    }

    private static void checkInterrupted() throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new IOException("Imagery load cancelled.");
        }
    }

    private static final class RangeStream extends ImageInputStreamImpl {
        private static final int BLOCK = 256 * 1024;
        private static final int MAX_BLOCKS = 128;
        private static final java.util.regex.Pattern CONTENT_RANGE =
                java.util.regex.Pattern.compile("bytes ([0-9]+)-([0-9]+)/([0-9]+)");
        private final URI uri;
        private final Map<Long, byte[]> cache = new HashMap<>();
        private long size = -1;
        private String etag;

        RangeStream(URI uri) {
            this.uri = uri;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) == -1 ? -1 : Byte.toUnsignedInt(one[0]);
        }

        @Override
        public int read(byte[] dst, int off, int len) throws IOException {
            checkClosed();
            java.util.Objects.checkFromIndexSize(off, len, dst.length);
            bitOffset = 0;
            if (len == 0) {
                return 0;
            }
            if (size >= 0 && streamPos >= size) {
                return -1;
            }
            long start = streamPos / BLOCK * BLOCK;
            byte[] block = cache.get(start);
            if (block == null) {
                if (cache.size() >= MAX_BLOCKS || start > 1_000_000_000L) {
                    throw new IOException("Imagery range limit reached. Use a smaller AOI.");
                }
                block = getBlock(start);
                cache.put(start, block);
            }
            int i = (int) (streamPos - start);
            if (i >= block.length) {
                return -1;
            }
            int n = Math.min(len, block.length - i);
            System.arraycopy(block, i, dst, off, n);
            streamPos += n;
            return n;
        }

        @Override
        public long length() {
            return size;
        }

        private byte[] getBlock(long start) throws IOException {
            checkInterrupted();
            HttpsURLConnection conn = (HttpsURLConnection) uri.toURL().openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(12000);
            conn.setRequestProperty("Range", "bytes=" + start + "-" + (start + BLOCK - 1));
            if (etag != null) {
                conn.setRequestProperty("If-Match", etag);
            }
            try {
                int code = conn.getResponseCode();
                if (code == 429) {
                    throw new IOException("Imagery host is rate-limiting requests. Wait before loading another crop.");
                }
                if (code != 206) {
                    throw new IOException("Imagery host did not return a partial TIFF response (HTTP " + code + ").");
                }
                String currentEtag = conn.getHeaderField("ETag");
                if (currentEtag == null || (etag != null && !etag.equals(currentEtag))) {
                    throw new IOException("Imagery object changed while reading the crop.");
                }
                etag = currentEtag;
                String header = conn.getHeaderField("Content-Range");
                var match = CONTENT_RANGE.matcher(header == null ? "" : header);
                if (!match.matches()) {
                    throw new IOException("Imagery host returned an invalid byte range.");
                }
                long low = Long.parseLong(match.group(1));
                long high = Long.parseLong(match.group(2));
                long total = Long.parseLong(match.group(3));
                if (low != start || high < low || high >= total || high - low >= BLOCK
                        || (size >= 0 && size != total)) {
                    throw new IOException("Imagery host returned an unexpected byte range.");
                }
                try (var body = conn.getInputStream()) {
                    int count = (int) (high - low + 1);
                    byte[] block = body.readNBytes(count + 1);
                    if (block.length != count) {
                        throw new IOException("Imagery byte range was incomplete.");
                    }
                    size = total;
                    return block;
                }
            } catch (NumberFormatException e) {
                throw new IOException("Imagery host returned an invalid byte range.", e);
            } finally {
                conn.disconnect();
            }
        }
    }
}
