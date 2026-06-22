<?php

declare(strict_types=1);

namespace MockServer\Exception;

/**
 * Thrown when a control-plane feature is disabled on the server (HTTP 403).
 *
 * Several SRE control-plane endpoints are gated behind a server start-up flag and
 * return {@code 403 Forbidden} until that flag is set. For example starting a
 * load scenario ({@see \MockServer\MockServerClient::startLoadScenarios()})
 * returns 403 until MockServer is started with {@code loadGenerationEnabled=true}.
 * Registering a scenario ({@see \MockServer\MockServerClient::loadScenario()}) is
 * always allowed.
 */
class FeatureNotEnabledException extends MockServerException
{
}
