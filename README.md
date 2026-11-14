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

Restart JOSM. In **Preferences → Plugins**, enable **orbilume**, apply the change, and restart if prompted. Open a map layer and zoom to the area of interest. Select **Imagery → Orbilume**, set the date and cloud filters, and click **Search**. Select a scene and click **Load imagery**. Remove it through the JOSM Layers panel.

The cloud filter is applied to the first 20 catalog results. Scenes with missing cloud metadata are marked unknown and included. Cloud coverage applies to the full scene. The Raster API uses the explicit B04, B03 and B02 reflectance asset selection. During testing, the example using `variables` returned HTTP 422, and the sample TileJSON route returned HTTP 400. The direct Web Mercator RGB tile and reflectance info routes responded successfully. [EOPF API documentation](https://explorer.eopf.copernicus.eu/software-services/developer-resources/)

## License

GNU General Public License, version 3. See [LICENSE](LICENSE). Imagery contains modified Copernicus Sentinel data and is attributed in the layer.
