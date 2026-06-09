# MockServer for VS Code

Start, stop, and manage [MockServer](https://www.mock-server.com) Docker containers directly from Visual Studio Code.

## Prerequisites

- [Docker Desktop](https://www.docker.com/products/docker-desktop/) installed and running
- VS Code 1.80+

## Installation

Install from the [VS Code Marketplace](https://marketplace.visualstudio.com/items?itemName=mock-server.mockserver) or search for "MockServer" in the Extensions view.

For Open VSX (Eclipse Theia, Gitpod, etc.), install from [open-vsx.org](https://open-vsx.org/extension/mock-server/mockserver).

## Commands

Open the Command Palette (`Cmd+Shift+P` / `Ctrl+Shift+P`) and type "MockServer":

| Command | Description |
|---------|-------------|
| **MockServer: Start (Docker)** | Pulls and starts `mockserver/mockserver:7.0.0` on port 1080 |
| **MockServer: Stop** | Stops the running MockServer container |
| **MockServer: Open Dashboard** | Opens the MockServer dashboard in your browser |

## Snippets

In any `.json` file, type:

- `mockserver-expectation` - inserts a full expectation template (request + response)
- `mockserver-forward` - inserts an expectation with a forward action
- `mockserver-verify` - inserts a verify request template

## Quick Start

1. Run **MockServer: Start (Docker)** from the Command Palette
2. Create expectations by POSTing JSON to `http://localhost:1080/mockserver/expectation`
3. Run **MockServer: Open Dashboard** to inspect recorded requests
4. Run **MockServer: Stop** when done

## Building from Source

```bash
npm install
npm run compile
npm test
```

## Packaging

```bash
npx vsce package
```

This produces a `.vsix` file you can install manually via `code --install-extension mockserver-*.vsix`.

## License

Apache-2.0
