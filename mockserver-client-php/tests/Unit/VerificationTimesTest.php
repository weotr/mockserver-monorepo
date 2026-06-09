<?php

declare(strict_types=1);

namespace MockServer\Tests\Unit;

use MockServer\VerificationTimes;
use PHPUnit\Framework\TestCase;

class VerificationTimesTest extends TestCase
{
    public function testAtLeast(): void
    {
        $times = VerificationTimes::atLeast(2);

        $this->assertSame(['atLeast' => 2], $times->toArray());
    }

    public function testAtMost(): void
    {
        $times = VerificationTimes::atMost(5);

        $this->assertSame(['atMost' => 5], $times->toArray());
    }

    public function testExactly(): void
    {
        $times = VerificationTimes::exactly(3);

        $this->assertSame(['atLeast' => 3, 'atMost' => 3], $times->toArray());
    }

    public function testBetween(): void
    {
        $times = VerificationTimes::between(1, 5);

        $this->assertSame(['atLeast' => 1, 'atMost' => 5], $times->toArray());
    }

    public function testOnce(): void
    {
        $times = VerificationTimes::once();

        $this->assertSame(['atLeast' => 1, 'atMost' => 1], $times->toArray());
    }

    public function testJsonSerialize(): void
    {
        $times = VerificationTimes::atLeast(1);

        $json = json_encode($times, JSON_THROW_ON_ERROR);
        $decoded = json_decode($json, true);

        $this->assertSame(['atLeast' => 1], $decoded);
    }
}
