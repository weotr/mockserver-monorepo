<?php

declare(strict_types=1);

namespace MockServer\Tests\Unit;

use MockServer\Testing\MockServerTestTrait;
use PHPUnit\Framework\SkippedTestError;
use PHPUnit\Framework\TestCase;

/**
 * Smoke tests for {@see MockServerTestTrait} that do not require a running
 * MockServer. They assert the trait skips gracefully when MOCKSERVER_URL is
 * unset and exposes the expected lifecycle helpers.
 */
final class MockServerTestTraitTest extends TestCase
{
    use MockServerTestTrait;

    public function testBootSkipsWhenServerUrlUnset(): void
    {
        $previous = getenv('MOCKSERVER_URL');
        putenv('MOCKSERVER_URL');

        try {
            $this->bootMockServer();
            $this->fail('Expected bootMockServer() to skip the test when MOCKSERVER_URL is unset.');
        } catch (SkippedTestError $e) {
            $this->assertStringContainsString('MOCKSERVER_URL', $e->getMessage());
        } finally {
            if ($previous !== false) {
                putenv('MOCKSERVER_URL=' . $previous);
            }
        }
    }

    public function testTearDownIsNoOpWithoutClient(): void
    {
        // No client was booted, so tearDown must not throw.
        $this->mockServer = null;
        $this->tearDownMockServer();
        $this->assertNull($this->mockServer);
    }
}
