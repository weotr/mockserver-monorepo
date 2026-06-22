import {mockServerClient, ClockStatus, GrpcService, MockServerClient, ScenarioHandle, ScenarioList, ScenarioState, llm as llmFactory, mcpMock} from '../index';
import {RequestResponse} from '../mockServerClient';
import {CrossProtocolScenario, Expectation, ExpectationStep, HttpChaosProfile, HttpOverrideForwardedRequest, HttpRequest, HttpResponse, LoadScenario, LoadScenarioEntry, LoadScenarioList, LoadScenarioRegistration, LoadScenarioStartResult, LoadScenarioStopResult, RequestDefinition} from '../mockServer';

const client: MockServerClient = mockServerClient('mockhttp', 1080);

const httpResponse: HttpResponse = {
    statusCode: 200,
    body: {
        body: {},
        headers: {},
        statusCode: 200
    }
}

const expectation: Expectation = {
    httpRequest: {
        method: 'POST',
        path: `/some/path`,
        body: {
            type: 'REGEX',
            regex: '.*'
        }
    },
    httpResponse: httpResponse,
    times: {
        unlimited: true
    },
    timeToLive: {
        timeUnit: "HOURS",
        timeToLive: 1
    }
};

const chaosProfile: HttpChaosProfile = {
    errorStatus: 503,
    errorProbability: 0.5,
    dropConnectionProbability: 0.1,
    retryAfter: "30",
    latency: {
        timeUnit: "MILLISECONDS",
        value: 200
    },
    seed: 42,
    succeedFirst: 3,
    failRequestCount: 5,
    outageAfterMillis: 5000,
    outageDurationMillis: 10000,
    truncateBodyAtFraction: 0.25,
    malformedBody: true,
    slowResponseChunkSize: 8,
    slowResponseChunkDelay: {
        timeUnit: "MILLISECONDS",
        value: 250
    },
    quotaName: "acct",
    quotaLimit: 4,
    quotaWindowMillis: 60000,
    quotaErrorStatus: 429,
    degradationRampMillis: 30000
};

const chaosExpectation: Expectation = {
    httpRequest: {
        method: 'GET',
        path: `/chaos/test`
    },
    httpResponse: httpResponse,
    chaos: chaosProfile,
    times: {
        unlimited: true
    }
};

const expectations: Expectation[] = [expectation, expectation, chaosExpectation];

const requestDefinition: RequestDefinition = {
    method: 'POST',
    path: 'some/path',
    body: {
        type: 'REGEX',
        regex: `.*`
    },
};

const overrideForwardRequest: HttpOverrideForwardedRequest = {
    httpRequest: requestDefinition,
    httpResponse: httpResponse
}

const overrideForwardRequestWithModifiers: HttpOverrideForwardedRequest = {
    requestOverride: requestDefinition,
    requestModifier: {
        path: {
            regex: "^/(.+)/(.+)$",
            substitution: "/prefix/$1/infix/$2/postfix"
        },
        headers: {
            add: [
                {"name": "Content-Type", "values": ["application/json; charset=utf-8"]},
                {"name": "Cache-Control", "values": ["no-cache, no-store"]}
            ],
            replace: [
                {"name": "Content-Type", "values": ["application/json; charset=utf-8"]},
                {"name": "Cache-Control", "values": ["no-cache, no-store"]}
            ],
            remove: ["someHeader"]

        }
    },
    responseOverride: httpResponse,
    responseModifier: {
        headers: {
            add: [
                {"name": "Content-Type", "values": ["application/json; charset=utf-8"]},
                {"name": "Cache-Control", "values": ["no-cache, no-store"]}
            ],
            replace: [
                {"name": "Content-Type", "values": ["application/json; charset=utf-8"]},
                {"name": "Cache-Control", "values": ["no-cache, no-store"]}
            ],
            remove: ["someHeader"]

        }
    }
}

// ExpectationStep type-checking
const sideEffectStep: ExpectationStep = {
    httpRequest: {
        method: 'POST',
        path: '/webhook'
    },
    blocking: true,
    timeout: {
        timeUnit: 'SECONDS',
        value: 5
    },
    failurePolicy: 'FAIL_FAST'
};

const responderStep: ExpectationStep = {
    httpResponse: {
        statusCode: 200,
        body: 'ok'
    },
    responder: true,
    delay: {
        timeUnit: 'MILLISECONDS',
        value: 100
    }
};

const forwardStep: ExpectationStep = {
    httpForward: {
        host: 'example.com',
        port: 8080,
        scheme: 'HTTP'
    },
    responder: true
};

const callbackStep: ExpectationStep = {
    httpClassCallback: {
        callbackClass: 'com.example.Callback'
    }
};

const objectCallbackStep: ExpectationStep = {
    httpObjectCallback: {
        clientId: 'ws-client-1'
    }
};

const overrideStep: ExpectationStep = {
    httpOverrideForwardedRequest: {
        requestOverride: {
            path: '/override'
        }
    },
    responder: true
};

const errorStep: ExpectationStep = {
    httpError: {
        dropConnection: true
    },
    responder: true
};

const bestEffortStep: ExpectationStep = {
    httpRequest: {
        path: '/hook'
    },
    blocking: false,
    failurePolicy: 'BEST_EFFORT'
};

const stepsExpectation: Expectation = {
    httpRequest: {
        method: 'GET',
        path: '/api'
    },
    steps: [sideEffectStep, responderStep],
    times: {
        unlimited: true
    }
};

async function test() {
    let requestResponse: RequestResponse = await client.mockAnyResponse(expectation);
    await client.mockAnyResponse(expectations);

    requestResponse = await client.mockWithCallback(requestDefinition, (request) => httpResponse);
    requestResponse = await client.mockWithCallback(requestDefinition, (request) => httpResponse, 10);
    requestResponse = await client.mockWithCallback(requestDefinition, (request) => httpResponse, 10, 10, {unlimited: true}, "some_id");

    requestResponse = await client.mockWithForwardCallback(requestDefinition, (request) => request);
    requestResponse = await client.mockWithForwardCallback(requestDefinition, (request) => request, 10);
    requestResponse = await client.mockWithForwardCallback(requestDefinition, (request) => request, 10, 10, {unlimited: true}, "some_id");

    requestResponse = await client.mockWithForwardAndResponseCallback(requestDefinition, (request) => request, (request, response) => response);
    requestResponse = await client.mockWithForwardAndResponseCallback(requestDefinition, (request) => request, (request, response) => response, 10);
    requestResponse = await client.mockWithForwardAndResponseCallback(requestDefinition, (request) => request, (request, response) => response, 10, 10, {unlimited: true}, "some_id");

    // class callbacks (REST-only, pure JSON) -- type-checking only
    requestResponse = await client.respondWithClassCallback('/path', 'com.example.MyResponseCallback');
    requestResponse = await client.respondWithClassCallback(requestDefinition, {callbackClass: 'com.example.MyResponseCallback', delay: {timeUnit: 'MILLISECONDS', value: 100}, primary: true}, 10, 10, {unlimited: true}, 'cb-id');
    requestResponse = await client.forwardWithClassCallback('/path', 'com.example.MyForwardCallback');
    requestResponse = await client.forwardWithClassCallback(requestDefinition, {callbackClass: 'com.example.MyForwardCallback'}, 5);

    requestResponse = await client.mockSimpleResponse('some/path', {});
    requestResponse = await client.mockSimpleResponse('some/path', {}, 500);

    // advanced response builders (type-checking only)
    requestResponse = await client.respondWithSse('/sse', {
        statusCode: 200,
        events: [{event: 'message', data: 'hello'}],
        closeConnection: true
    });
    requestResponse = await client.respondWithSse(requestDefinition, {events: [{data: 'x'}]}, 3, 10, {unlimited: true}, 'sse-id');
    requestResponse = await client.respondWithWebSocket('/ws', {
        messages: [{text: 'hello'}],
        closeConnection: false
    });
    requestResponse = await client.respondWithDns('/dns', {
        answerRecords: [{name: 'example.com', type: 'A', ttl: 300, value: '127.0.0.1'}],
        responseCode: 'NOERROR'
    });
    requestResponse = await client.respondWithBinary('/binary', {
        binaryData: Buffer.from('hello').toString('base64')
    });
    requestResponse = await client.respondWithGrpcStream('/my.Service/Stream', {
        statusName: 'OK',
        messages: [{json: '{"value":"first"}'}],
        closeConnection: true
    });

    let _this = client.setDefaultHeaders(
        [
            {"name": "Content-Type", "values": ["application/json; charset=utf-8"]},
            {"name": "Cache-Control", "values": ["no-cache, no-store"]}
        ],
        [
            {"name": "sessionId", "values": ["786fcf9b-606e-605f-181d-c245b55e5eac"]}
        ]);
    _this = client.setDefaultHeaders({
            "Content-Type": ["application/json; charset=utf-8"]
        },
        {
            "sessionId": ["786fcf9b-606e-605f-181d-c245b55e5eac"]
        });

    let string = await client.verify(requestDefinition);
    await client.verify(requestDefinition, 1);
    await client.verify(requestDefinition, 1, 2);

    string = await client.verifySequence(requestDefinition, requestDefinition);

    requestResponse = await client.reset();

    requestResponse = await client.clear('some/path', 'ALL');
    requestResponse = await client.clear('some/path', 'LOG');
    requestResponse = await client.clear('some/path', 'EXPECTATIONS');

    requestResponse = await client.bind([1, 2, 3, 4]);

    // clock control
    requestResponse = await client.freezeClock("2025-01-15T09:30:00Z");
    requestResponse = await client.freezeClock();
    requestResponse = await client.advanceClock(3600000);
    requestResponse = await client.resetClock();
    let clockStatus: ClockStatus = await client.clockStatus();

    // service-scoped chaos
    requestResponse = await client.setServiceChaos("payments.svc", chaosProfile);
    requestResponse = await client.removeServiceChaos("payments.svc");
    requestResponse = await client.clearServiceChaos();
    let serviceChaos: { services: { [host: string]: HttpChaosProfile } } = await client.serviceChaosStatus();

    // load scenario (load injection)
    const loadScenarioDefinition: LoadScenario = {
        name: "load-test",
        templateType: "VELOCITY",
        labels: {team: "node"},
        maxRequests: 100,
        startDelayMillis: 250,
        profile: {
            stages: [
                {type: "VU", startVus: 1, endVus: 10, durationMillis: 5000, curve: "LINEAR"},
                {type: "VU", vus: 10, durationMillis: 10000},
                {type: "PAUSE", durationMillis: 1000},
                {type: "RATE", rate: 5, durationMillis: 5000, maxVus: 20}
            ]
        },
        steps: [
            {
                name: "ping",
                thinkTime: {timeUnit: "MILLISECONDS", value: 10},
                request: {method: "GET", path: "/ping"}
            }
        ]
    };
    let loadRegistration: LoadScenarioRegistration = await client.loadScenario(loadScenarioDefinition);
    let loadList: LoadScenarioList = await client.loadScenarios();
    let loadEntry: LoadScenarioEntry = await client.getLoadScenario("load-test");
    let loadStart: LoadScenarioStartResult = await client.startLoadScenarios("load-test");
    loadStart = await client.startLoadScenarios(["load-test"]);
    let loadStop: LoadScenarioStopResult = await client.stopLoadScenarios("load-test");
    loadStop = await client.stopLoadScenarios(["load-test"]);
    loadStop = await client.stopLoadScenarios();
    requestResponse = await client.deleteLoadScenario("load-test");
    requestResponse = await client.clearLoadScenarios();
    loadStart = await client.runLoadScenario(loadScenarioDefinition);

    // stateful scenario (state machine) management
    const deployScenario: ScenarioHandle = client.scenario("Deploy");
    let scenarioState: ScenarioState = await deployScenario.state();
    scenarioState = await deployScenario.set("Deploying");
    scenarioState = await deployScenario.set("Deploying", {transitionAfterMs: 5000, nextState: "Deployed"});
    scenarioState = await deployScenario.trigger("Failed");
    const scenarioList: ScenarioList = await client.scenarios();

    // typed Expectation scenario fields (responseWeights / switchAfter / crossProtocolScenarios)
    const crossProtocol: CrossProtocolScenario[] = [
        {trigger: "DNS_QUERY", matchPattern: "api.example.com", scenarioName: "Deploy", targetState: "Resolving"},
        {trigger: "WEBSOCKET_CONNECT", scenarioName: "Deploy", targetState: "Connected"}
    ];
    const scenarioExpectation: Expectation = {
        httpRequest: {path: "/stateful"},
        httpResponses: [{statusCode: 200}, {statusCode: 503}],
        responseMode: "WEIGHTED",
        responseWeights: [3, 1],
        switchAfter: 2,
        scenarioName: "Deploy",
        scenarioState: "Deploying",
        newScenarioState: "Deployed",
        crossProtocolScenarios: crossProtocol
    };
    requestResponse = await client.mockAnyResponse(scenarioExpectation);

    // LLM mocking builders (type-checking only)
    requestResponse = await client.mockWithLLM(
        client.llm.llmMock('/v1/messages')
            .withProvider(client.llm.Provider.ANTHROPIC)
            .withModel('claude-sonnet-4')
            .respondingWith(
                client.llm.completion()
                    .withText('Paris.')
                    .withStopReason('end_turn')
                    .withUsage(client.llm.usage().withInputTokens(10).withOutputTokens(2))
            )
    );
    // also reachable as a standalone factory import
    const standaloneCompletion = llmFactory.completion().withText('via factory import');
    requestResponse = await client.mockWithLLM(
        client.llm.conversation()
            .withPath('/v1/messages')
            .withProvider(client.llm.Provider.OPENAI)
            .isolateBy(client.llm.header('x-session-id'))
            .turn().whenTurnIndex(0).respondingWith(
                client.llm.completion().withToolCall(client.llm.toolUse('search').withArguments({}))
            )
            .andThen()
            .turn().whenContainsToolResultFor('search').respondingWith(standaloneCompletion)
            .andThen()
    );
    requestResponse = await client.mockWithLLM(
        client.llm.llmFailover()
            .withPath('/v1/chat/completions')
            .withProvider(client.llm.Provider.OPENAI)
            .withModel('gpt-4o')
            .failWith(503, 2)
            .failWith(429)
            .thenRespondWith(
                client.llm.completion()
                    .streaming()
                    .withStreamingPhysics(client.llm.tokensPerSecond(50).withJitter(0.2))
            )
    );

    requestResponse = await client.uploadGrpcDescriptor(Buffer.from([]));
    requestResponse = await client.uploadGrpcDescriptor(new Uint8Array([]));
    let generatedCode: string = await client.retrieveExpectationsAsCode('java', '/somePath');
    let recordedCode: string = await client.retrieveRecordedExpectationsAsCode('python');
    let grpcServices: GrpcService[] = await client.retrieveGrpcServices();
    let firstServiceName: string = grpcServices[0].name;
    let firstMethodStreaming: boolean = grpcServices[0].methods[0].clientStreaming;
    requestResponse = await client.clearGrpcDescriptors();

    // MCP (Model Context Protocol) mock builder
    const mcpExpectations: Expectation[] = client.mcpMock('/mcp')
        .withServerName('MyServer')
        .withServerVersion('2.0.0')
        .withProtocolVersion('2025-03-26')
        .withTool('get_weather')
        .withDescription('Get the weather for a city')
        .withInputSchema('{"type":"object","properties":{"city":{"type":"string"}}}')
        .respondingWith('sunny')
        .and()
        .withResource('file:///config.json')
        .withName('config')
        .withMimeType('application/json')
        .withContent('{"debug":true}')
        .and()
        .withPrompt('greet')
        .withDescription('A greeting prompt')
        .withArgument('name', 'who to greet', true)
        .respondingWith('user', 'Hello {{name}}')
        .and()
        .build();
    requestResponse = await client.mcpMock('/mcp').withToolsCapability().applyTo();
    requestResponse = await mcpMock('/other-mcp').withResourcesCapability().applyTo(client);
}
