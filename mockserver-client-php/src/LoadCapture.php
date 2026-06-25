<?php

declare(strict_types=1);

namespace MockServer;

/**
 * A declarative cross-step capture / correlation rule for a {@see LoadStep}:
 * extracts a value from a step's response and binds it to a variable name that
 * a later step in the same iteration can reference from its templated request
 * fields via {@code $iteration.captured.<name>} (Velocity) /
 * {@code {{iteration.captured.<name>}}} (Mustache).
 *
 * Best-effort: on no match it falls back to {@code defaultValue} (when set) or
 * leaves the variable unset, never failing the run.
 *
 * Use the static factory {@see LoadCapture::of()}, optionally followed by
 * {@see LoadCapture::defaultValue()}.
 *
 * @example
 *   LoadCapture::of('token', 'BODY_JSONPATH', '$.token');
 *   LoadCapture::of('etag', 'HEADER', 'ETag')->defaultValue('none');
 */
class LoadCapture implements \JsonSerializable
{
    private string $name;
    private string $source;
    private string $expression;
    private ?string $defaultValue = null;

    private function __construct(string $name, string $source, string $expression)
    {
        $this->name = $name;
        $this->source = strtoupper($source);
        $this->expression = $expression;
    }

    /**
     * Build a capture rule.
     *
     * @param string $name The variable name later steps reference.
     * @param string $source Where to extract from: BODY_JSONPATH, HEADER or BODY_REGEX.
     * @param string $expression The JSONPath (BODY_JSONPATH), header name (HEADER)
     *        or regex (BODY_REGEX, capture group 1) driving the extraction.
     */
    public static function of(string $name, string $source, string $expression): self
    {
        return new self($name, $source, $expression);
    }

    /**
     * Set the fallback value bound to the variable when extraction yields nothing.
     */
    public function defaultValue(string $defaultValue): self
    {
        $this->defaultValue = $defaultValue;

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
        $result = [
            'name' => $this->name,
            'source' => $this->source,
            'expression' => $this->expression,
        ];
        if ($this->defaultValue !== null) {
            $result['defaultValue'] = $this->defaultValue;
        }

        return $result;
    }
}
