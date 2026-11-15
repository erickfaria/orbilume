<p align="center">
  <img src="src/main/resources/images/orbilume/icon.svg" width="64" height="64" alt="">
</p>
<h1 align="center">Orbilume</h1>
<p align="center">Find recent available Sentinel-2 imagery and compare acquisition dates in JOSM.</p>
<p align="center">
  <a href="https://github.com/erickfaria/orbilume/releases/latest">Download</a> ·
  <a href="https://github.com/erickfaria/orbilume/wiki">Wiki</a> ·
  <a href="docs/Sources-and-Licenses.md">Sources and licenses</a> ·
  <a href="https://github.com/erickfaria/orbilume/issues">Report an issue</a>
</p>

Orbilume is a free, independent plugin for collaborative OpenStreetMap mapping. Use it to find reference imagery, check whether a feature appears on more than one date, and resolve questions before drawing or editing in JOSM. Drawing, validation, and submission stay under the mapper's control.

![Natural-color Sentinel-2 imagery displayed in the JOSM map and Layers panel](docs/images/orbilume-josm.png)

*Contains modified Copernicus Sentinel data 2026, accessed through Earth Search (Element 84).*

## Install or update

Use **JOSM r19369 or newer** and **Java 17 or newer**.

- In JOSM, open **Preferences → Plugins → Download list**. Search for **orbilume**, enable it, and restart if prompted. To upgrade an existing installation, click **Update plugins**.
- For manual installation, download [`orbilume.jar`](https://github.com/erickfaria/orbilume/releases/latest/download/orbilume.jar) and follow the [installation guide](https://github.com/erickfaria/orbilume/wiki/Installation). The release includes a SHA-256 checksum.

## First search

1. Open **Imagery → Orbilume**. Set an area of interest (AOI) from the map view, a selection, or a pasted bounding box.
2. Choose a provider, date range, and maximum scene cloud cover, then click **Search**.
3. Select a scene, choose a composition, and click **Load imagery**. With **Earth Search**, use **Preview AOI** to check cloud and coverage estimates, or select two acquisition dates and click **Compare dates**.
4. To toggle dates without moving the map, right-click the Earth Search comparison layer and choose **Switch to [date]**.

| Capability | Earth Search | EOPF Explorer |
| --- | --- | --- |
| Sentinel-2 Level-2A search | Yes | Yes |
| Natural color, near infrared, and shortwave infrared | Yes | Yes |
| Imagery in JOSM | AOI crop in Web Mercator | Tiles managed by JOSM |
| AOI cloud preview and two-date comparison | Yes | — |

The catalog's cloud percentage describes the full scene. **Preview AOI** estimates cloud, cloud shadow, and valid coverage within your rectangle.

## Documentation

- [User guide](docs/User-Guide.md) for the controls, band combinations, and provider limits.
- [Vienna walkthrough](https://github.com/erickfaria/orbilume/wiki/Mapping-Example) for a reproducible two-date comparison.
- [Troubleshooting](https://github.com/erickfaria/orbilume/wiki/Troubleshooting-and-Privacy) for empty searches, partial coverage, and missing layers.
- [Build and contribute](CONTRIBUTING.md) for GitHub issues, local builds, and validation.

## Sources and license

Orbilume displays Copernicus Sentinel-2 Level-2A imagery and shows provider credit. It has no telemetry, map editing, or upload functions. Search sends the AOI rectangle and filters to the selected provider. See [Sources, licenses, and privacy](docs/Sources-and-Licenses.md) for the original terms and what each provider receives.

The plugin is distributed under [GNU GPL v3](LICENSE). Imagery is governed by the [Copernicus Sentinel Data Legal Notice](https://cds.climate.copernicus.eu/licences/ec-sentinel).
