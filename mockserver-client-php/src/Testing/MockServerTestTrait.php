<?php

declare(strict_types=1);

namespace MockServer\Testing;

use MockServer\MockServerClient;

/**
 * PHPUnit helper trait that provides a {@see MockServerClient} and resets the
 * server before and after every test, so recorded requests, expectations and
 * logs never leak between tests.
 *
 * Mix it into a PHPUnit test case and call {@see bootMockServer()} from your own
 * `setUp()` (or rely on the provided {@see setUpMockServer()} / {@see tearDownMockServer()}
 * hooks):
 *
 * <code>
 * use MockServer\Testing\MockServerTestTrait;
 * use PHPUnit\Framework\TestCase;
 *
 * final class MyTest extends TestCase
 * {
 *     use MockServerTestTrait;
 *
 *     protected function setUp(): void
 *     {
 *         $this->setUpMockServer();
 *     }
 *
 *     protected function tearDown(): void
 *     {
 *         $this->tearDownMockServer();
 *     }
 *
 *     public function testSomething(): void
 *     {
 *         // $this->mockServer is a reset MockServerClient ready to use
 *         $this->mockServer->reset();
 *     }
 * }
 * </code>
 *
 * The server URL is read from the `MOCKSERVER_URL` environment variable
 * (for example `http://localhost:1080`). When it is unset the test is skipped
 * via PHPUnit's `markTestSkipped()`.
 */
trait MockServerTestTrait
{
    protected ?MockServerClient $mockServer = null;

    /**
     * Build a {@see MockServerClient} from the MOCKSERVER_URL environment
     * variable, skipping the test if it is not configured. Does not reset.
     */
    protected function bootMockServer(): MockServerClient
    {
        $url = getenv('MOCKSERVER_URL');
        if ($url === false || $url === '') {
            $this->markTestSkipped('MOCKSERVER_URL environment variable not set — skipping MockServer test.');
        }

        $parsed = parse_url($url);
        $host = $parsed['host'] ?? 'localhost';
        $port = $parsed['port'] ?? 1080;
        $secure = ($parsed['scheme'] ?? 'http') === 'https';
        $contextPath = trim($parsed['path'] ?? '', '/');

        $this->mockServer = new MockServerClient($host, (int) $port, $contextPath, $secure);

        return $this->mockServer;
    }

    /**
     * Boot the client and reset the server to a clean state. Call from `setUp()`.
     */
    protected function setUpMockServer(): void
    {
        $this->bootMockServer();
        $this->mockServer->reset();
    }

    /**
     * Reset the server again after the test. Call from `tearDown()`.
     * Best-effort: failures during cleanup are swallowed.
     */
    protected function tearDownMockServer(): void
    {
        if ($this->mockServer !== null) {
            try {
                $this->mockServer->reset();
            } catch (\Throwable) {
                // Best effort cleanup.
            }
            $this->mockServer = null;
        }
    }
}
