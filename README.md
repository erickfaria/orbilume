# Orbilume

Orbilume is a free and open-source JOSM plugin for discovering and exploring satellite imagery through open Earth observation services.

The experimental plugin searches the current map extent for Sentinel-2 Level-2A scenes and opens natural-color RGB imagery in a native JOSM layer. It sends the search extent and filters to Copernicus EOPF Explorer when you click **Search**. JOSM requests visible tiles after you load a scene. Orbilume has no accounts, telemetry, or OSM editing functions.

## Build and test

Use JOSM r19369 or newer and a JDK 21 installation. The plugin targets Java 17 and uses the JSON library bundled with JOSM. On Debian, the default JOSM JAR is `/usr/share/josm/josm.jar`.

```sh
./build.sh
JOSM_JAR=/path/to/josm.jar ./build.sh
./build.sh --live
```

The first two commands run offline tests and produce `build/orbilume.jar`. The optional live test requests a catalog page and sample RGB tile over a public test area near Vienna.

## Install and use

```sh
mkdir -p "${XDG_DATA_HOME:-$HOME/.local/share}/JOSM/plugins"
cp -i build/orbilume.jar "${XDG_DATA_HOME:-$HOME/.local/share}/JOSM/plugins/"
```

Restart JOSM. In **Preferences → Plugins**, enable **orbilume**, apply the change, and restart if prompted. Open **Imagery → Orbilume**.

1. Click **Use map view** to copy the viewport, or select one or more map objects and click **Use selection** to copy their bounding box. You can also paste a bounding box in west, south, east, north order using decimal degrees.
2. Set the dates and maximum scene cloud cover, then click **Search**. Copernicus filters the catalog before returning up to 20 scenes, with the least cloudy first. Scenes without valid cloud metadata are excluded.
3. Select a scene and choose **Natural color** (B04/B03/B02), **Vegetation** (B08/B04/B03), or **Soil and vegetation** (B12/B8A/B04). Click **Load imagery** to open a native layer and zoom to the searched AOI. Remove it through the Layers panel.

Cloud cover is reported for the whole scene. A low percentage can still leave cloud over the AOI. The scene cloud filter does not mask individual pixels. The provider returned HTTP 400 for the SCL asset during verification. A loaded layer displays the selected scene wherever JOSM requests visible tiles, including beyond the search box.

## Network and data handling

Search sends the displayed bounding box, dates, and cloud threshold to Copernicus. Selection uses only the enclosing rectangle. The plugin does not send the selected objects, IDs, or tags. Search uses fixed HTTPS endpoints, rejects catalog redirects, limits the response size and result count, and validates scene IDs before building tile URLs. JOSM handles visible tile requests and uses its normal imagery cache and proxy settings. Tile coordinates allow the provider to infer the viewed area.

The Raster API uses explicit reflectance asset band selection. The direct Web Mercator PNG route worked for all three compositions. An older example using `variables` returned HTTP 422, and the sample TileJSON route returned HTTP 400. [EOPF API documentation](https://explorer.eopf.copernicus.eu/software-services/developer-resources/)

## License

GNU General Public License, version 3. See [LICENSE](LICENSE). Imagery contains modified Copernicus Sentinel data and is attributed in the layer.
