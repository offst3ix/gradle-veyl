# gradle-veyl

Gradle plugin for [Veyl](https://veyl.st3ix.net) — upload your project JAR for **server-side** Java bytecode obfuscation during the build.

**Plugin ID:** `net.st3ix.veyl`  
**Latest:** `1.0.9`  
**Docs:** [Getting started](http://docs.st3ix.net/docs/plugins/veyl/getting-started)

## Requirements

- Java 21+
- A Veyl account (`veyl login` via the [Veyl CLI](http://docs.st3ix.net/docs/plugins/veyl/getting-started))

Obfuscation runs on the Veyl worker. This plugin does not ship a local obfuscator runtime.

## Apply

`settings.gradle.kts`:

```kotlin
pluginManagement {
  repositories {
    gradlePluginPortal()
    mavenCentral()
  }
}
```

`build.gradle.kts`:

```kotlin
plugins {
  java
  id("net.st3ix.veyl") version "1.0.9"
}

veyl {
  rename = true
  aesStrings = true
  keep("com.example.Main")
  // keepFile = file("veyl.rules")
}
```

Groovy:

```groovy
plugins {
  id 'java'
  id 'net.st3ix.veyl' version '1.0.9'
}

veyl {
  rename = true
  aesStrings = true
  keep 'com.example.Main'
}
```

Then:

```bash
./gradlew clean build
```

The plugin hooks `jar` (and `shadowJar` when present), uploads the archive, and can replace the output with the obfuscated JAR.

## Options

| Property | Default | Notes |
|----------|---------|--------|
| `enabled` | `true` | Turn the task off |
| `replaceJar` | `true` | Replace the archive with the obfuscated file |
| `rename` | `true` | Identifier rename |
| `aesStrings` | `false` | AES-GCM string vault |
| `hiddenNumbers` | `false` | Number vault |
| `hiddenCalls` | `false` | MethodHandle call gates |
| `controlFlow` | `false` | State-machine CFG |
| `keeps` / `keep("…")` | — | Classes that must stay readable |
| `keepFile` | — | Rules file (`veyl.rules`) |
| `suggestKeeps` | `true` | Suggest keeps from plugin metadata |

Full keep-rule docs: [Keep rules](http://docs.st3ix.net/docs/plugins/veyl/keep-rules)

## CLI shortcut

With the Veyl CLI installed:

```bash
veyl login
veyl add
veyl build
```

`veyl add` wires Portal resolution by default (`--local` uses `mavenLocal()` for maintainers).

## Develop / publish (maintainers)

```bash
./gradlew :gradle-plugin:publishToMavenLocal
./gradlew :gradle-plugin:publishPlugins
```

Portal credentials go in `~/.gradle/gradle.properties` only (`gradle.publish.key` / `gradle.publish.secret`) — never commit them.

## License

MIT — see [LICENSE](LICENSE).
