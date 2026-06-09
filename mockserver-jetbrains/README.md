# MockServer JetBrains Plugin (Initial Scaffold)

> **Status: Initial scaffold** — this plugin provides basic MockServer integration for IntelliJ-based IDEs. More features (expectation management, log viewing, inline test support) are planned for follow-up iterations.

## Features

- **Open MockServer Dashboard** — launches `http://localhost:1080/mockserver/dashboard` in your default browser
- **Start MockServer (Docker)** — runs a MockServer container (`docker run -d --rm -p 1080:1080 mockserver/mockserver:7.0.0`)
- **Tool Window** — bottom panel with quick-access buttons for the above actions

## Requirements

- IntelliJ IDEA 2023.3+ (or any JetBrains IDE based on IntelliJ Platform build 233+)
- Docker (for the "Start MockServer" action)

## Installation

### From JetBrains Marketplace (once published)

1. Open **Settings > Plugins > Marketplace**
2. Search for "MockServer"
3. Click **Install**

### From local build

```bash
cd mockserver-jetbrains
./gradlew buildPlugin
```

The plugin ZIP will be at `build/distributions/mockserver-jetbrains-7.0.1.zip`. Install via **Settings > Plugins > gear icon > Install Plugin from Disk**.

## Usage

1. Go to **Tools > MockServer > Open MockServer Dashboard** to view the dashboard
2. Go to **Tools > MockServer > Start MockServer (Docker)** to launch a container
3. The **MockServer** tool window (bottom bar) provides the same actions as buttons

## Building

```bash
./gradlew buildPlugin
```

## Running tests

```bash
./gradlew test
```

## Running in a sandbox IDE

```bash
./gradlew runIde
```

## Development

- **Language:** Kotlin
- **Build system:** Gradle with IntelliJ Platform Gradle Plugin 2.x
- **Minimum platform:** IntelliJ Platform 2023.3 (build 233)
- **Java:** 17+
