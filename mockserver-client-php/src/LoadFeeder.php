<?php

declare(strict_types=1);

namespace MockServer;

/**
 * Parameterized test data (a data feeder) for a {@see LoadScenario}: an inline
 * dataset from which one row is selected per iteration and exposed to that
 * iteration's templated request path, body and headers as
 * {@code $iteration.data.<column>} (Velocity) /
 * {@code {{iteration.data.<column>}}} (Mustache).
 *
 * The dataset is always inline (no external URL or file source). Supply EITHER
 * {@code rows} (inline list of column-name to value maps, the primary form) OR
 * {@code data} + {@code format} (raw CSV/JSON parsed server-side); when both are
 * given rows wins.
 *
 * @example
 *   LoadFeeder::rows([
 *       ['user' => 'alice', 'id' => '1'],
 *       ['user' => 'bob',   'id' => '2'],
 *   ])->strategy('RANDOM');
 *
 *   LoadFeeder::raw("user,id\nalice,1\nbob,2", 'CSV');
 */
class LoadFeeder implements \JsonSerializable
{
    /** @var array<int, array<string, string>>|null */
    private ?array $rows = null;
    private ?string $data = null;
    private ?string $format = null;
    private ?string $strategy = null;

    private function __construct()
    {
    }

    /**
     * Build a feeder from an inline list of column-name to value maps.
     *
     * @param array<int, array<string, string>> $rows The inline dataset (must be non-empty when used).
     */
    public static function rows(array $rows): self
    {
        $feeder = new self();
        $feeder->rows = $rows;

        return $feeder;
    }

    /**
     * Build a feeder from a raw inline dataset parsed server-side.
     *
     * @param string $data The raw CSV or JSON dataset.
     * @param string $format The format of {@code $data}: CSV or JSON.
     */
    public static function raw(string $data, string $format): self
    {
        $feeder = new self();
        $feeder->data = $data;
        $feeder->format = strtoupper($format);

        return $feeder;
    }

    /**
     * Set how a row is chosen each iteration.
     *
     * @param string $strategy One of CIRCULAR (default), RANDOM, SEQUENTIAL.
     */
    public function strategy(string $strategy): self
    {
        $this->strategy = strtoupper($strategy);

        return $this;
    }

    /**
     * @return array<string, mixed>
     */
    public function jsonSerialize(): array
    {
        return $this->toArray();
    }

    /**
     * @return array<string, mixed>
     */
    public function toArray(): array
    {
        $result = [];
        if ($this->rows !== null) {
            $result['rows'] = $this->rows;
        }
        if ($this->data !== null) {
            $result['data'] = $this->data;
        }
        if ($this->format !== null) {
            $result['format'] = $this->format;
        }
        if ($this->strategy !== null) {
            $result['strategy'] = $this->strategy;
        }

        return $result;
    }
}
