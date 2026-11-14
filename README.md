# Orbilume

Orbilume is a free and open-source JOSM plugin for discovering and exploring satellite imagery through open Earth observation services.

The experimental plugin searches the current map extent for Sentinel-2 Level-2A scenes and opens natural-color RGB imagery in a native JOSM layer. It sends the search extent and filters to the selected imagery provider when you click **Search**. Orbilume has no accounts, telemetry, or OSM editing functions.

## Build and test

Use JOSM r19369 or newer and a JDK 21 installation. The plugin targets Java 17 and uses the JSON library bundled with JOSM. On Debian, the default JOSM JAR is `/usr/share/josm/josm.jar`.

```sh
./build.sh
JOSM_JAR=/path/to/josm.jar ./build.sh
./build.sh --live
./build.sh --live-earth
```

The first two commands run offline tests and produce `build/orbilume.jar`. The optional live tests verify EOPF tiles near Vienna and Earth Search crops for an agricultural AOI in Brazil.

## Install and use

```sh
mkdir -p "${XDG_DATA_HOME:-$HOME/.local/share}/JOSM/plugins"
cp -i build/orbilume.jar "${XDG_DATA_HOME:-$HOME/.local/share}/JOSM/plugins/"
```

Restart JOSM. In **Preferences → Plugins**, enable **orbilume**, apply the change, and restart if prompted. Open **Imagery → Orbilume**.

1. Click **Use map view** to copy the viewport, or select one or more map objects and click **Use selection** to copy their bounding box. You can also paste a bounding box in west, south, east, north order using decimal degrees.
2. Choose **Earth Search (Element 84)** for broad Sentinel-2 coverage, or **Copernicus EOPF Explorer** to use EOPF tiles. Set the dates and maximum scene cloud cover, then click **Search**. The selected catalog filters and sorts up to 20 scenes, with the least cloudy first. Scenes without valid cloud metadata are excluded.
3. Select a scene and choose **Natural color** (B04/B03/B02), **Vegetation** (B08/B04/B03), or **Soil and vegetation** (B12/B8A/B04). Click **Load imagery** to open a native layer and zoom to the searched AOI. Remove it through the Layers panel.

Cloud cover is reported for the whole scene. A low percentage can still leave cloud over the AOI. The scene cloud filter does not mask individual pixels. Earth Search loads a crop limited to the searched AOI, with a maximum of 2,048 pixels per side. It requires the Web Mercator (EPSG:3857) map projection. EOPF uses native tiles, so visible imagery can extend beyond the AOI.

## Network and data handling

Search sends the bounding box, dates, and cloud limit to the selected provider. Selection uses the enclosing rectangle. Object IDs and tags stay in JOSM. Earth Search uses fixed catalog and public data hosts. Before reading imagery, the plugin checks the HTTPS asset host, path, scene and band, rejects redirects and full-object responses, and limits the raster dimensions and bytes read. AWS receives the selected scene's asset paths and byte ranges. Imagery is decoded locally and held in memory. EOPF tiles are handled by JOSM and use its normal imagery cache and proxy settings. [Earth Search](https://github.com/Element84/earth-search), [Sentinel-2 open data](https://registry.opendata.aws/sentinel-2-l2a-cogs/)

The Raster API uses explicit reflectance asset band selection. The direct Web Mercator PNG route worked for all three compositions. An older example using `variables` returned HTTP 422, and the sample TileJSON route returned HTTP 400. [EOPF API documentation](https://explorer.eopf.copernicus.eu/software-services/developer-resources/)

## License

GNU General Public License, version 3. See [LICENSE](LICENSE). Imagery contains modified Copernicus Sentinel data and is attributed in the layer.
