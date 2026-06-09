/**
 * Unit test for MockServer VS Code extension.
 * Tests command registration without requiring a running VS Code instance.
 * Uses a minimal stub of the vscode API.
 */

import * as assert from "assert";

// Stub the vscode module before importing the extension
interface Disposable {
    dispose(): void;
}

interface Subscription {
    push(...items: Disposable[]): void;
}

interface FakeContext {
    subscriptions: Disposable[];
}

const registeredCommands: Map<string, Function> = new Map();
const outputLines: string[] = [];

// Build a minimal vscode stub
const vscodeStub = {
    commands: {
        registerCommand(id: string, handler: Function): Disposable {
            registeredCommands.set(id, handler);
            return { dispose() { registeredCommands.delete(id); } };
        },
    },
    window: {
        createOutputChannel(_name: string) {
            return {
                appendLine(msg: string) { outputLines.push(msg); },
                show(_preserveFocus?: boolean) {},
                dispose() {},
            };
        },
        showInformationMessage(_msg: string) {},
        showErrorMessage(_msg: string) {},
        showWarningMessage(_msg: string) {},
    },
    env: {
        openExternal(_uri: any) { return Promise.resolve(true); },
    },
    Uri: {
        parse(value: string) { return { toString: () => value }; },
    },
};

// Patch require to intercept 'vscode' imports
const Module = require("module");
const originalRequire = Module.prototype.require;
Module.prototype.require = function (id: string) {
    if (id === "vscode") {
        return vscodeStub;
    }
    return originalRequire.apply(this, arguments);
};

// Now import the extension (it will get our stub)
// Clear the module cache first so it picks up the stub
delete require.cache[require.resolve("../extension")];
const extension = require("../extension");

function runTests(): void {
    console.log("Running MockServer VS Code extension tests...\n");

    let passed = 0;
    let failed = 0;

    function test(name: string, fn: () => void): void {
        try {
            fn();
            console.log(`  PASS: ${name}`);
            passed++;
        } catch (e: any) {
            console.log(`  FAIL: ${name}`);
            console.log(`        ${e.message}`);
            failed++;
        }
    }

    // Setup: activate the extension
    registeredCommands.clear();
    const fakeContext: FakeContext = { subscriptions: [] };
    extension.activate(fakeContext);

    test("activate registers mockserver.start command", () => {
        assert.ok(
            registeredCommands.has("mockserver.start"),
            "mockserver.start command not registered"
        );
    });

    test("activate registers mockserver.stop command", () => {
        assert.ok(
            registeredCommands.has("mockserver.stop"),
            "mockserver.stop command not registered"
        );
    });

    test("activate registers mockserver.openDashboard command", () => {
        assert.ok(
            registeredCommands.has("mockserver.openDashboard"),
            "mockserver.openDashboard command not registered"
        );
    });

    test("activate adds disposables to subscriptions", () => {
        // 3 commands + 1 output channel = 4
        assert.ok(
            fakeContext.subscriptions.length >= 3,
            `Expected at least 3 subscriptions, got ${fakeContext.subscriptions.length}`
        );
    });

    test("deactivate does not throw", () => {
        assert.doesNotThrow(() => extension.deactivate());
    });

    test("registered command handlers are functions", () => {
        for (const [id, handler] of registeredCommands) {
            assert.strictEqual(typeof handler, "function", `Handler for ${id} is not a function`);
        }
    });

    console.log(`\nResults: ${passed} passed, ${failed} failed, ${passed + failed} total`);
    if (failed > 0) {
        process.exit(1);
    }
}

runTests();
