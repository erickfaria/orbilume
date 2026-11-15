# Orbilume

Orbilume is a free, open-source JOSM plugin for finding and viewing Sentinel-2 Level-2A imagery. Search an area of interest, select a scene, and open it as a native JOSM imagery layer.

![Sentinel-2 natural-color imagery displayed in a native Orbilume layer in JOSM](docs/images/orbilume-josm.png)

*Contains modified Copernicus Sentinel data 2026, accessed through Earth Search (Element 84).*

## Install

Use JOSM **r19369 or later** with **Java 17 or later**. Download `orbilume.jar` from the [GitHub release](https://github.com/erickfaria/orbilume/releases/latest). Keep the filename unchanged and place it in JOSM's plugins directory. On a standard Debian installation, that directory is `${XDG_DATA_HOME:-$HOME/.local/share}/JOSM/plugins`.

Restart JOSM, enable **orbilume** under **Preferences → Plugins**, and restart again if prompted. Then open **Imagery → Orbilume**.

## Quick start

1. Click **Use map view** or **Use selection** to set the area of interest. You can also paste a bounding box in **west, south, east, north** order using decimal degrees.
2. Choose **Earth Search (Element 84)** or **Copernicus EOPF Explorer**. Set the date range and maximum scene cloud cover, then click **Search**.
3. Select a scene and choose **Natural color**, **Vegetation (near infrared)**, or **Soil and vegetation (SWIR)**. Click **Load imagery**.

Cloud cover is measured across the full scene. Check the loaded image for clouds within your area. Orbilume displays imagery and has no telemetry, OSM editing, or upload features.

See the [user guide](docs/User-Guide.md) for band combinations, provider limits, troubleshooting, and network behavior.

## Build from source

Use JDK 21 and a compatible JOSM JAR. The build targets Java 17.

```sh
./build.sh
JOSM_JAR=/path/to/josm.jar ./build.sh
```

The plugin is written to `build/orbilume.jar`.

## License

Orbilume is licensed under [GNU GPL v3](LICENSE). Imagery is provided under its source terms and attributed in the JOSM layer.
