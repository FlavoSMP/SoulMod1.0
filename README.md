# Soul SMP mod

A Fabric mod for Minecraft 1.21.11.

**Author:** FlavoSMP

## Scripts

On first launch, the mod copies `SoulHost.js` and `SoulHearts.js` to
`config/soulsmpmod/scripts`. `SoulHost.js` is the script manager; it loads
`SoulHearts.js`. Add more `.js` files there and load them from `SoulHost.js`.

Scripts run with Rhino's Java interoperability, so they can access Minecraft,
Fabric, and Java classes through `Packages`. Only run scripts you trust; they
have the same permissions as the game process.

## Building

```bash
./gradlew build
```

The mod JAR will be located in `build/libs/`

## Development

Use Java 21+ and Gradle 8.0 or higher.

```bash
./gradlew genSources
```

To generate IDE files and run the development environment.

## Installation

1. Install Fabric Loader
2. Place the mod JAR in your `mods` folder
3. Start the game

## License

MIT
