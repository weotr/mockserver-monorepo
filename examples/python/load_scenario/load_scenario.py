#!/usr/bin/env python3
"""MockServer Python client -- Load Scenario registry example.

A "load scenario" is a named, server-side traffic generator: you register it
once (its profile of ramp/hold/pause stages and the request steps it drives),
then start/stop it by name. While running it generates synthetic traffic against
the data plane and reports live throughput/latency status. This is the registry
workflow exercised with the typed Python client:

  client.load_scenario(scenario)        register/upsert (PUT /mockserver/loadScenario)
  client.start_load_scenarios(names)    start one/many (PUT .../start)
  client.load_scenarios()               list all (GET /mockserver/loadScenario)
  client.get_load_scenario(name)        one scenario + live status (GET .../{name})
  client.stop_load_scenarios(names)     stop one/many; None = stop all (PUT .../stop)
  client.run_load_scenario(scenario)    register + start in one call
  client.delete_load_scenario(name)     delete one (DELETE .../{name})
  client.clear_load_scenarios()         clear the registry (DELETE /mockserver/loadScenario)

IMPORTANT: the server must be started with load generation enabled, otherwise
starting returns HTTP 403:
  java -Dmockserver.loadGenerationEnabled=true -jar mockserver-netty-...-jar-with-dependencies.jar -serverPort 1080
  (or env MOCKSERVER_LOAD_GENERATION_ENABLED=true). Registering is always allowed.

Prints "PASS" and exits 0 on success; exits non-zero on the first failure.

The server location is read from the environment (defaults localhost:1080):
  MOCKSERVER_HOST (default "localhost")
  MOCKSERVER_PORT (default 1080)
"""

import os
import sys
import time

from mockserver import (
    Delay,
    Expectation,
    HttpRequest,
    HttpResponse,
    LoadProfile,
    LoadScenario,
    LoadStage,
    LoadStep,
    MockServerClient,
)


MOCK_HOST = os.environ.get("MOCKSERVER_HOST", "localhost")
MOCK_PORT = int(os.environ.get("MOCKSERVER_PORT", "1080"))


def build_scenario() -> LoadScenario:
    """A realistic multi-stage scenario built from the typed model.

    The profile is a linear arrival-rate ramp (5 -> 50 req/s, capped at 50 VUs),
    then a 25-VU hold, then a PAUSE. Two Velocity-templated steps drive each
    iteration ($!iteration.index varies the request). ``start_delay_millis``
    defers load for half a second after the scenario is started. Stage VUs are
    kept within the default safety cap of 50 (loadGenerationMaxVirtualUsers).
    """
    return LoadScenario(
        name="checkout-load",
        template_type="VELOCITY",
        max_requests=100000,
        start_delay_millis=500,
        labels={"team": "payments", "env": "staging"},
        profile=LoadProfile(stages=[
            LoadStage.rate_stage(30000, start_rate=5, end_rate=50, max_vus=50, curve="LINEAR"),
            LoadStage.vu_stage(60000, vus=25),
            LoadStage.pause_stage(10000),
        ]),
        steps=[
            LoadStep(
                name="browse",
                request=HttpRequest(method="GET", path="/products/$!iteration.index"),
                think_time=Delay(time_unit="MILLISECONDS", value=500),
            ),
            LoadStep(
                name="checkout",
                request=HttpRequest(
                    method="POST",
                    path="/cart/checkout",
                    body='{"item":"$!iteration.index","qty":1}',
                ),
                labels={"critical": "true"},
            ),
        ],
    )


def main() -> int:
    scenario = build_scenario()

    with MockServerClient(MOCK_HOST, MOCK_PORT) as client:
        # A target expectation so generated traffic gets a 200 to measure.
        client.upsert(Expectation(
            http_request=HttpRequest(),
            http_response=HttpResponse().with_status_code(200).with_body("ok"),
        ))

        try:
            # 1. Register (does NOT start it yet).
            client.load_scenario(scenario)
            print('registered "checkout-load"')

            # 2. Start it (accepts a single name or a list of names).
            client.start_load_scenarios("checkout-load")
            print('started "checkout-load"')
            time.sleep(1.5)

            # 3. List all registered scenarios -> {"scenarios": [ <status node>, ... ]}.
            listing = client.load_scenarios()
            scenarios = listing.get("scenarios", [])
            running = [s for s in scenarios if s.get("state") == "RUNNING"]
            assert any(s.get("name") == "checkout-load" for s in running), (
                "checkout-load is not RUNNING in the list "
                "(is loadGenerationEnabled=true?)"
            )
            print("listed: " + ", ".join(
                f"{s.get('name')}={s.get('state')}" for s in scenarios
            ))

            # One scenario's live status (throughput/latency, current stage, ...).
            status = client.get_load_scenario("checkout-load")
            print(
                f"status: state={status.get('state')}"
                f" stageType={status.get('stageType')}"
                f" currentTarget={status.get('currentTarget')}"
                f" requestsSent={status.get('requestsSent')}"
            )

            # 4. Stop it (pass no names / None to stop ALL running scenarios).
            client.stop_load_scenarios("checkout-load")
            print('stopped "checkout-load"')

            # Tidy up the registry.
            client.clear_load_scenarios()
        except AssertionError as error:
            print(f"FAIL: {error}")
            return 1
        except Exception as error:  # noqa: BLE001 - surface any runtime error
            print(f"ERROR: {error}")
            return 1

    print("PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
