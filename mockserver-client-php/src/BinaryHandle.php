<?php

declare(strict_types=1);

namespace MockServer;

/**
 * Handle for a running MockServer binary process.
 *
 * Returned by {@see BinaryLauncher::start()} and used to stop the server
 * and read its output.
 */
class BinaryHandle
{
    /** @var resource */
    private $process;
    /** @var resource|null */
    private $stdout;
    /** @var resource|null */
    private $stderr;
    private int $port;
    private bool $stopped = false;

    /**
     * @param resource $process proc_open handle
     * @param resource|null $stdout stdout pipe (null when output is discarded to /dev/null)
     * @param resource|null $stderr stderr pipe (null when output is discarded to /dev/null)
     * @param int $port The port MockServer was started on
     * @internal Use BinaryLauncher::start() to create instances.
     */
    public function __construct($process, $stdout, $stderr, int $port)
    {
        $this->process = $process;
        $this->stdout = $stdout;
        $this->stderr = $stderr;
        $this->port = $port;
    }

    /**
     * Stop the running MockServer process.
     *
     * @param int $signal Signal to send (default SIGTERM, 15)
     */
    public function stop(int $signal = 15): void
    {
        if ($this->stopped) {
            return;
        }

        $this->stopped = true;
        if ($this->stdout !== null) {
            @fclose($this->stdout);
        }
        if ($this->stderr !== null) {
            @fclose($this->stderr);
        }
        proc_terminate($this->process, $signal);
        proc_close($this->process);
    }

    /**
     * Check if the process is still running.
     */
    public function isRunning(): bool
    {
        if ($this->stopped) {
            return false;
        }

        $status = proc_get_status($this->process);

        return $status['running'] ?? false;
    }

    /**
     * Get the port MockServer was started on.
     */
    public function getPort(): int
    {
        return $this->port;
    }

    /**
     * Read any available stdout output (non-blocking).
     *
     * Returns an empty string when output was not captured (the default).
     * To capture output, pass $captureOutput = true to BinaryLauncher::start().
     */
    public function readStdout(): string
    {
        if ($this->stdout === null) {
            return '';
        }

        return (string) @stream_get_contents($this->stdout);
    }

    /**
     * Read any available stderr output (non-blocking).
     *
     * Returns an empty string when output was not captured (the default).
     * To capture output, pass $captureOutput = true to BinaryLauncher::start().
     */
    public function readStderr(): string
    {
        if ($this->stderr === null) {
            return '';
        }

        return (string) @stream_get_contents($this->stderr);
    }

    /**
     * Ensure the process is stopped on garbage collection.
     */
    public function __destruct()
    {
        $this->stop();
    }
}
