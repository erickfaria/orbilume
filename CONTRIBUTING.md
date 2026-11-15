# Contributing to Orbilume

Contributions are welcome for imagery workflows, accessibility, documentation, and support for JOSM releases.

## Report a problem

[Open a GitHub issue](https://github.com/erickfaria/orbilume/issues/new) with the Orbilume version, JOSM revision, Java version, operating system, provider, and steps to reproduce. For imagery problems, include the composition, scene ID, and error message if available. Use a public example AOI instead of sharing a private location or your JOSM profile.

## Build locally

Use JDK 21 and a readable JOSM JAR. The default on Debian is `/usr/share/josm/josm.jar`. The build targets Java 17.

```sh
./build.sh
JOSM_JAR=/path/to/josm.jar ./build.sh
jar tf build/orbilume.jar
```

The output is `build/orbilume.jar`. Place it in the plugins directory of a separate JOSM profile when checking changes. Open **Imagery → Orbilume** and verify the affected search, preview, or layer workflow. The PR check builds the JAR and verifies startup on the minimum and current stable JOSM versions.

## Open a pull request

Target `main` and describe the user-facing change and how you verified it. Use English for interface text and documentation. Keep locally generated files, credentials, and private mapping data out of commits. When changing a provider integration, update [Sources, licenses, and privacy](docs/Sources-and-Licenses.md) with the original documentation and any change to the data sent.

See [LICENSE](LICENSE) for the project's GPLv3 license.
