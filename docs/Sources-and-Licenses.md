# Sources, licenses, and privacy

Orbilume is an independent, free and open-source JOSM plugin for collaborative mapping. It helps contributors find recent available Sentinel-2 imagery, compare acquisition dates, and verify what they see before drawing with JOSM's own tools. Check the acquisition date and scene coverage before interpreting features.

## Software and images

| Material | License or provider documentation |
| --- | --- |
| Orbilume code, original icon, and documentation text | [GNU General Public License, version 3](../LICENSE) |
| JOSM, installed separately | [JOSM license and third-party notices](https://josm.openstreetmap.de/browser/josm/trunk/LICENSE) |
| Copernicus Sentinel-2 Level-2A imagery, including documentation screenshots | [Copernicus Sentinel Data Legal Notice](https://cds.climate.copernicus.eu/licences/ec-sentinel) |
| Earth Search catalog and public Sentinel-2 Cloud-Optimized GeoTIFFs | [Element 84 Earth Search API](https://github.com/Element84/earth-search) · [AWS dataset entry, license, and citation](https://registry.opendata.aws/sentinel-2-l2a-cogs/) |
| EOPF Explorer catalog and raster tiles | [EOPF Explorer developer documentation](https://explorer.eopf.copernicus.eu/software-services/developer-resources/) · [EOPF Explorer privacy policy](https://explorer.eopf.copernicus.eu/privacy-policy) |

Both providers serve **Copernicus Sentinel-2 Level-2A**. The GPL applies to the plugin, its original icon, and its documentation text. The Sentinel Data Legal Notice governs the imagery. When sharing a modified Sentinel image, preserve the source credit and indicate the modification, for example, “Contains modified Copernicus Sentinel data 2026.” Use the year of the imagery, and cite the provider or dataset. For unmodified Sentinel data, the legal notice gives the applicable credit wording.

## What leaves JOSM

- **Search** sends the bounding rectangle, dates, and maximum scene cloud cover to the selected catalog. A selection is used to calculate the rectangle locally. Its tags, object IDs, and geometry are kept in JOSM.
- **Earth Search imagery** reads HTTPS byte ranges from the public Sentinel-2 archive for the chosen crop. Previews may also read the Sentinel-2 scene classification band. Crops are decoded locally and held in memory.
- **EOPF imagery** loads raster tiles through JOSM. Tile requests include the selected scene, composition, and tile coordinates. JOSM manages its own imagery cache and proxy settings.

Catalog and Earth Search byte-range requests identify Orbilume in their User-Agent header. Orbilume has no account system, telemetry, or upload functions. The providers receive normal network request information, including the caller's IP address. Public service availability and rate limits are controlled by each provider. Use the interactive search and crop workflow. For bulk or unattended processing, consult the provider's terms and available access methods.

## Contributing to OpenStreetMap

Orbilume provides reference imagery. It creates no map objects and publishes no edits. Drawing, validation, and submission are separate actions in JOSM under the mapper's control. When your edits rely on this imagery, record `source=Sentinel-2` in the changeset, and include any other sources you used. See the [Sentinel-2 changeset guidance](https://wiki.openstreetmap.org/wiki/Sentinel-2#Changeset_Source) and the [OpenStreetMap copyright and contribution page](https://www.openstreetmap.org/copyright). For imports or editing automation used alongside Orbilume, see the [Import Guidelines](https://wiki.openstreetmap.org/wiki/Import/Guidelines) and [Automated Edits Code of Conduct](https://wiki.openstreetmap.org/wiki/Automated_Edits_code_of_conduct).
