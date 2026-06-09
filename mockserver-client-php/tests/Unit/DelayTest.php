<?php

declare(strict_types=1);

namespace MockServer\Tests\Unit;

use MockServer\Delay;
use PHPUnit\Framework\TestCase;

class DelayTest extends TestCase
{
    public function testMilliseconds(): void
    {
        $delay = Delay::milliseconds(250);

        $this->assertSame([
            'timeUnit' => 'MILLISECONDS',
            'value' => 250,
        ], $delay->toArray());
    }

    public function testSeconds(): void
    {
        $delay = Delay::seconds(5);

        $this->assertSame([
            'timeUnit' => 'SECONDS',
            'value' => 5,
        ], $delay->toArray());
    }

    public function testGetters(): void
    {
        $delay = Delay::milliseconds(100);

        $this->assertSame('MILLISECONDS', $delay->getTimeUnit());
        $this->assertSame(100, $delay->getValue());
    }

    public function testJsonSerialize(): void
    {
        $delay = Delay::seconds(2);

        $json = json_encode($delay, JSON_THROW_ON_ERROR);
        $decoded = json_decode($json, true);

        $this->assertSame('SECONDS', $decoded['timeUnit']);
        $this->assertSame(2, $decoded['value']);
    }

    public function testConstructorUppercases(): void
    {
        $delay = new Delay('milliseconds', 100);

        $this->assertSame('MILLISECONDS', $delay->getTimeUnit());
    }
}
