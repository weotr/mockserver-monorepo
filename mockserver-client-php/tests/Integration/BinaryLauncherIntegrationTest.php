<?php

declare(strict_types=1);

namespace MockServer\Tests\Integration;

use MockServer\BinaryLauncher;
use MockServer\MockServerClient;
use PHPUnit\Framework\TestCase;

/**
 * Integration test for the binary launcher.
 *
 * This test attempts a real download + start of the MockServer binary.
 * It is SKIPPED by default — enable it by setting MOCKSERVER_BINARY_IT=1.
 *
 * The test requires network access and a published binary for the target
 * version; a real released bundle may not exist for SNAPSHOT or unreleased
 * versions.
 *
 * Example:
 *   MOCKSERVER_BINARY_IT=1 vendor/bin/phpunit --testsuite Integration --filter BinaryLauncherIntegration
 */
class BinaryLauncherIntegrationTest extends TestCase
{
    protected function setUp(): void
    {
        $flag = getenv('MOCKSERVER_BINARY_IT');
        if ($flag === false || $flag === '' || $flag === '0') {
            $this->markTestSkipped(
                'MOCKSERVER_BINARY_IT not set — skipping live binary launcher integration test.'
            );
        }
    }

    public function testDownloadAndStartBinary(): void
    {
        $launcher = new BinaryLauncher(null, function (string $msg): void {
            fwrite(STDERR, "[BinaryLauncher] {$msg}\n");
        });

        // Attempt to download and start
        $handle = $launcher->start(18765);

        try {
            // Give the server a moment to start
            $client = new MockServerClient('localhost', 18765);
            $started = $client->hasStarted(30, 1.0);

            $this->assertTrue($started, 'MockServer should have started on port 18765');

            // Basic status check
            $status = $client->status();
            $this->assertArrayHasKey('ports', $status);
        } finally {
            $handle->stop();
        }
    }
}
