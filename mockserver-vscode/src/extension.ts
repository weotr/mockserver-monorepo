import * as vscode from "vscode";
import { execSync, exec } from "child_process";

const MOCKSERVER_VERSION = "7.0.0";
const CONTAINER_NAME = "mockserver-vscode";
const DEFAULT_PORT = 1080;
const DOCKER_IMAGE = `mockserver/mockserver:${MOCKSERVER_VERSION}`;

let outputChannel: vscode.OutputChannel;

export function activate(context: vscode.ExtensionContext): void {
    outputChannel = vscode.window.createOutputChannel("MockServer");

    const startCmd = vscode.commands.registerCommand("mockserver.start", startMockServer);
    const stopCmd = vscode.commands.registerCommand("mockserver.stop", stopMockServer);
    const dashboardCmd = vscode.commands.registerCommand("mockserver.openDashboard", openDashboard);

    context.subscriptions.push(startCmd, stopCmd, dashboardCmd, outputChannel);
}

export function deactivate(): void {
    // Nothing to clean up
}

async function startMockServer(): Promise<void> {
    const port = DEFAULT_PORT;
    const dockerCmd = `docker run -d --rm --name ${CONTAINER_NAME} -p ${port}:1080 ${DOCKER_IMAGE}`;

    outputChannel.appendLine(`Starting MockServer (${DOCKER_IMAGE}) on port ${port}...`);
    outputChannel.show(true);

    try {
        // Check if Docker is available
        execSync("docker info", { stdio: "pipe" });
    } catch {
        vscode.window.showErrorMessage(
            "Docker is not running. Please start Docker Desktop and try again."
        );
        return;
    }

    // Check if container is already running
    try {
        const running = execSync(
            `docker ps --filter name=${CONTAINER_NAME} --format "{{.Names}}"`,
            { encoding: "utf-8" }
        ).trim();
        if (running === CONTAINER_NAME) {
            vscode.window.showInformationMessage(
                `MockServer is already running on port ${port}.`
            );
            return;
        }
    } catch {
        // Ignore — proceed with start
    }

    exec(dockerCmd, (error, stdout, stderr) => {
        if (error) {
            const msg = stderr || error.message;
            outputChannel.appendLine(`Error: ${msg}`);
            vscode.window.showErrorMessage(`Failed to start MockServer: ${msg}`);
            return;
        }
        const containerId = stdout.trim().substring(0, 12);
        outputChannel.appendLine(`MockServer started (container: ${containerId}).`);
        vscode.window.showInformationMessage(
            `MockServer started on http://localhost:${port}`
        );
    });
}

async function stopMockServer(): Promise<void> {
    outputChannel.appendLine("Stopping MockServer...");
    outputChannel.show(true);

    exec(`docker stop ${CONTAINER_NAME}`, (error, _stdout, stderr) => {
        if (error) {
            if (stderr.includes("No such container") || stderr.includes("not found")) {
                vscode.window.showWarningMessage("MockServer container is not running.");
            } else {
                vscode.window.showErrorMessage(`Failed to stop MockServer: ${stderr || error.message}`);
            }
            outputChannel.appendLine(`Stop result: ${stderr || error.message}`);
            return;
        }
        outputChannel.appendLine("MockServer stopped.");
        vscode.window.showInformationMessage("MockServer stopped.");
    });
}

async function openDashboard(): Promise<void> {
    const url = `http://localhost:${DEFAULT_PORT}/mockserver/dashboard`;
    const opened = await vscode.env.openExternal(vscode.Uri.parse(url));
    if (!opened) {
        vscode.window.showErrorMessage(`Failed to open dashboard at ${url}`);
    }
}
