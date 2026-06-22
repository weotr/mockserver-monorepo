'use strict';

/*
 * Pure unit tests for the LLM mocking builder API (llm.js). These assert the
 * exact expectation wire JSON produced by the builders — no running server is
 * required — to guarantee byte-for-byte parity with the Java client builders.
 */

var { describe, it } = require('node:test');
var assert = require('node:assert/strict');

var llm = require('../../llm');

// Round-trip through JSON.stringify/parse so toJSON() is applied and
// null/undefined fields are dropped, exactly as the over-the-wire body would be.
function wire(value) {
    return JSON.parse(JSON.stringify(value));
}

// =========================================================================
describe('llm builder — basic completion mock', function () {
    it('produces the expected httpLlmResponse expectation JSON', function () {
        var expectation = llm.llmMock('/v1/messages')
            .withProvider(llm.Provider.ANTHROPIC)
            .withModel('claude-sonnet-4')
            .respondingWith(
                llm.completion()
                    .withText('The capital of France is Paris.')
                    .withStopReason('end_turn')
                    .withUsage(llm.usage().withInputTokens(42).withOutputTokens(8))
            )
            .build();

        assert.deepEqual(wire(expectation), {
            httpRequest: { method: 'POST', path: '/v1/messages' },
            httpLlmResponse: {
                provider: 'ANTHROPIC',
                model: 'claude-sonnet-4',
                completion: {
                    text: 'The capital of France is Paris.',
                    stopReason: 'end_turn',
                    usage: { inputTokens: 42, outputTokens: 8 }
                }
            }
        });
    });

    it('omits null fields (usage with only input tokens)', function () {
        var expectation = llm.llmMock('/v1/chat/completions')
            .withProvider(llm.Provider.OPENAI)
            .withModel('gpt-4o')
            .respondingWith(llm.completion().withText('hi').withUsage(llm.inputTokens(5)))
            .build();

        assert.deepEqual(wire(expectation).httpLlmResponse.completion, {
            text: 'hi',
            usage: { inputTokens: 5 }
        });
    });

    it('supports an embedding response', function () {
        var expectation = llm.llmMock('/v1/embeddings')
            .withProvider(llm.Provider.OPENAI)
            .withModel('text-embedding-3-small')
            .respondingWith(llm.embedding().withDimensions(1536).withDeterministicFromInput(true))
            .build();

        assert.deepEqual(wire(expectation).httpLlmResponse, {
            provider: 'OPENAI',
            model: 'text-embedding-3-small',
            embedding: { dimensions: 1536, deterministicFromInput: true }
        });
    });
});

// =========================================================================
describe('llm builder — tool-use mock', function () {
    it('encodes tool calls with id/name/arguments', function () {
        var expectation = llm.llmMock('/v1/messages')
            .withProvider(llm.Provider.ANTHROPIC)
            .withModel('claude-sonnet-4')
            .respondingWith(
                llm.completion()
                    .withStopReason('tool_use')
                    .withToolCall(
                        llm.toolUse('get_weather')
                            .withId('toolu_01')
                            .withArguments('{"city":"Paris"}')
                    )
            )
            .build();

        assert.deepEqual(wire(expectation).httpLlmResponse.completion, {
            stopReason: 'tool_use',
            toolCalls: [
                { id: 'toolu_01', name: 'get_weather', arguments: '{"city":"Paris"}' }
            ]
        });
    });

    it('accepts an object for arguments and serialises it to a JSON string', function () {
        var completion = llm.completion().withToolCall(
            llm.toolUse('search').withArguments({ q: 'mockserver' })
        );
        assert.equal(wire(completion).toolCalls[0].arguments, '{"q":"mockserver"}');
    });
});

// =========================================================================
describe('llm builder — streaming physics', function () {
    it('encodes streaming + timeToFirstToken as a Delay, tokensPerSecond, jitter', function () {
        var expectation = llm.llmMock('/v1/messages')
            .withProvider(llm.Provider.ANTHROPIC)
            .withModel('claude-sonnet-4')
            .respondingWith(
                llm.completion()
                    .withText('streamed')
                    .streaming()
                    .withStreamingPhysics(
                        llm.streamingPhysics()
                            .withTimeToFirstToken(llm.timeToFirstToken(500, 'MILLISECONDS'))
                            .withTokensPerSecond(50)
                            .withJitter(0.2)
                    )
            )
            .build();

        assert.deepEqual(wire(expectation).httpLlmResponse.completion, {
            text: 'streamed',
            streaming: true,
            streamingPhysics: {
                timeToFirstToken: { timeUnit: 'MILLISECONDS', value: 500 },
                tokensPerSecond: 50,
                jitter: 0.2
            }
        });
    });
});

// =========================================================================
describe('llm builder — multi-turn conversation', function () {
    it('produces one scenario-stateful expectation per turn', function () {
        var expectations = llm.conversation()
            .withPath('/v1/messages')
            .withProvider(llm.Provider.ANTHROPIC)
            .withModel('claude-sonnet-4')
            .turn()
                .whenTurnIndex(0)
                .respondingWith(llm.completion().withToolCall(llm.toolUse('search').withArguments('{}')))
            .andThen()
            .turn()
                .whenContainsToolResultFor('search')
                .respondingWith(llm.completion().withText('The answer is 42.'))
            .andThen()
            .build();

        assert.equal(expectations.length, 2);

        var wired = wire(expectations);

        // shared, auto-generated scenario name with the reserved prefix
        var scenarioName = wired[0].scenarioName;
        assert.match(scenarioName, /^__llm_conv_[0-9a-f-]{36}$/);
        assert.equal(wired[1].scenarioName, scenarioName);

        // turn 0: Started -> turn_1
        assert.deepEqual(wired[0], {
            httpRequest: { method: 'POST', path: '/v1/messages' },
            scenarioName: scenarioName,
            scenarioState: 'Started',
            newScenarioState: 'turn_1',
            httpLlmResponse: {
                provider: 'ANTHROPIC',
                model: 'claude-sonnet-4',
                completion: { toolCalls: [{ name: 'search', arguments: '{}' }] },
                conversationPredicates: { turnIndex: 0 }
            }
        });

        // turn 1: turn_1 -> __done
        assert.deepEqual(wired[1], {
            httpRequest: { method: 'POST', path: '/v1/messages' },
            scenarioName: scenarioName,
            scenarioState: 'turn_1',
            newScenarioState: '__done',
            httpLlmResponse: {
                provider: 'ANTHROPIC',
                model: 'claude-sonnet-4',
                completion: { text: 'The answer is 42.' },
                conversationPredicates: { containsToolResultFor: 'search' }
            }
        });
    });

    it('encodes the isolation source into the scenario name', function () {
        var expectations = llm.conversation()
            .withPath('/v1/messages')
            .withProvider(llm.Provider.ANTHROPIC)
            .isolateBy(llm.header('x-session-id'))
            .turn().whenTurnIndex(0).respondingWith(llm.completion().withText('hi'))
            .andThen()
            .build();

        assert.match(expectations[0].scenarioName, /^__llm_conv_[0-9a-f-]{36}__iso=header:x-session-id$/);
    });
});

// =========================================================================
describe('llm builder — provider failover', function () {
    it('coalesces same-status failures then appends an unlimited success', function () {
        var expectations = llm.llmFailover()
            .withPath('/v1/chat/completions')
            .withProvider(llm.Provider.OPENAI)
            .withModel('gpt-4o')
            .failWith(503, 2)
            .failWith(429)
            .thenRespondWith(llm.completion().withText('recovered'))
            .build();

        var wired = wire(expectations);
        assert.equal(wired.length, 3);

        // two coalesced 503s
        assert.deepEqual(wired[0].times, { remainingTimes: 2, unlimited: false });
        assert.deepEqual(wired[0].timeToLive, { unlimited: true });
        assert.equal(wired[0].httpResponse.statusCode, 503);
        assert.equal(JSON.parse(wired[0].httpResponse.body).error.type, 'service_unavailable');

        // one 429
        assert.deepEqual(wired[1].times, { remainingTimes: 1, unlimited: false });
        assert.deepEqual(wired[1].timeToLive, { unlimited: true });
        assert.equal(wired[1].httpResponse.statusCode, 429);

        // unlimited success LLM response — every expectation carries timeToLive
        // (matches Python/Java which emit timeToLive: {unlimited:true} on all).
        assert.deepEqual(wired[2].times, { remainingTimes: 0, unlimited: true });
        assert.deepEqual(wired[2].timeToLive, { unlimited: true });
        assert.deepEqual(wired[2].httpLlmResponse, {
            provider: 'OPENAI',
            model: 'gpt-4o',
            completion: { text: 'recovered' }
        });
    });

    it('uses a custom error body verbatim and does not coalesce different bodies', function () {
        var expectations = llm.llmFailover()
            .withPath('/v1/messages')
            .withProvider(llm.Provider.ANTHROPIC)
            .failWith(500, '{"error":"first"}')
            .failWith(500, '{"error":"second"}')
            .thenRespondWith(llm.completion().withText('ok'))
            .build();

        var wired = wire(expectations);
        // two distinct bodies are NOT coalesced + one success => 3
        assert.equal(wired.length, 3);
        assert.equal(wired[0].times.remainingTimes, 1);
        assert.equal(wired[0].httpResponse.body, '{"error":"first"}');
        assert.equal(wired[1].httpResponse.body, '{"error":"second"}');
        assert.equal(wired[2].httpLlmResponse.model, undefined); // model never set => omitted
    });

    it('getFailureCount reports the expanded failure count', function () {
        var builder = llm.llmFailover()
            .withPath('/v1/messages')
            .withProvider(llm.Provider.ANTHROPIC)
            .failWith(429, 3)
            .failWith(503);
        assert.equal(builder.getFailureCount(), 4);
    });

    it('validates the status code range', function () {
        assert.throws(function () {
            llm.llmFailover().withPath('/p').withProvider(llm.Provider.OPENAI).failWith(99);
        }, /statusCode must be between 100 and 599/);
        assert.throws(function () {
            llm.llmFailover().withPath('/p').withProvider(llm.Provider.OPENAI).failWith(600);
        }, /statusCode must be between 100 and 599/);
    });

    it('rejects a failure count below 1', function () {
        assert.throws(function () {
            llm.llmFailover().withPath('/p').withProvider(llm.Provider.OPENAI).failWith(429, 0);
        }, /count must be >= 1/);
    });

    it('requires path, provider, at least one failure and a success completion', function () {
        assert.throws(function () { llm.llmFailover().build(); }, /Path must be set/);
        assert.throws(function () {
            llm.llmFailover().withPath('/p').build();
        }, /Provider must be set/);
        assert.throws(function () {
            llm.llmFailover().withPath('/p').withProvider(llm.Provider.OPENAI).build();
        }, /At least one failure must be defined/);
        assert.throws(function () {
            llm.llmFailover().withPath('/p').withProvider(llm.Provider.OPENAI).failWith(429).build();
        }, /Success completion must be set/);
    });

    it('applyTo delegates to client.mockWithLLM with the built expectations', function () {
        var captured = null;
        var fakeClient = { mockWithLLM: function (e) { captured = e; return 'PROMISE'; } };
        var result = llm.llmFailover()
            .withPath('/v1/messages')
            .withProvider(llm.Provider.ANTHROPIC)
            .failWith(429)
            .thenRespondWith(llm.completion().withText('ok'))
            .applyTo(fakeClient);
        assert.equal(result, 'PROMISE');
        assert.ok(Array.isArray(captured));
        assert.equal(captured.length, 2);
    });
});

// =========================================================================
describe('llm builder — defaultErrorBody status messages', function () {
    it('maps each known status code to its canonical error type', function () {
        var cases = {
            429: 'rate_limit_error',
            500: 'internal_server_error',
            502: 'bad_gateway',
            503: 'service_unavailable',
            418: 'error' // default branch
        };
        Object.keys(cases).forEach(function (code) {
            var body = JSON.parse(llm.defaultErrorBody(Number(code)));
            assert.equal(body.error.type, cases[code], 'status ' + code);
            assert.equal(typeof body.error.message, 'string');
        });
        // the default branch echoes the status code in the message
        assert.match(JSON.parse(llm.defaultErrorBody(418)).error.message, /418/);
    });
});

// =========================================================================
describe('llm model factories and validation', function () {
    it('usage()/inputTokens()/outputTokens() factories produce the right JSON', function () {
        assert.deepEqual(wire(llm.usage().withInputTokens(1).withOutputTokens(2)), { inputTokens: 1, outputTokens: 2 });
        assert.deepEqual(wire(llm.inputTokens(7)), { inputTokens: 7 });
        assert.deepEqual(wire(llm.outputTokens(9)), { outputTokens: 9 });
    });

    it('usage rejects negative token counts', function () {
        assert.throws(function () { llm.usage().withInputTokens(-1); }, /inputTokens must be >= 0/);
        assert.throws(function () { llm.usage().withOutputTokens(-1); }, /outputTokens must be >= 0/);
    });

    it('streamingPhysics factories and validation', function () {
        assert.deepEqual(wire(llm.tokensPerSecond(100)), { tokensPerSecond: 100 });
        assert.deepEqual(wire(llm.jitter(0.5)), { jitter: 0.5 });
        assert.throws(function () { llm.streamingPhysics().withTokensPerSecond(0); }, /tokensPerSecond must be between 1 and 10000/);
        assert.throws(function () { llm.streamingPhysics().withTokensPerSecond(10001); }, /tokensPerSecond must be between 1 and 10000/);
        assert.throws(function () { llm.streamingPhysics().withJitter(-0.1); }, /jitter must be between 0.0 and 1.0/);
        assert.throws(function () { llm.streamingPhysics().withJitter(1.1); }, /jitter must be between 0.0 and 1.0/);
    });

    it('withTimeToFirstToken accepts a (value, timeUnit) pair as well as a Delay object', function () {
        // pair form, explicit unit
        assert.deepEqual(
            wire(llm.streamingPhysics().withTimeToFirstToken(250, 'SECONDS')).timeToFirstToken,
            { timeUnit: 'SECONDS', value: 250 }
        );
        // pair form, default unit
        assert.deepEqual(
            wire(llm.streamingPhysics().withTimeToFirstToken(250)).timeToFirstToken,
            { timeUnit: 'MILLISECONDS', value: 250 }
        );
        // object form
        assert.deepEqual(
            wire(llm.streamingPhysics().withTimeToFirstToken({ timeUnit: 'SECONDS', value: 3 })).timeToFirstToken,
            { timeUnit: 'SECONDS', value: 3 }
        );
        // standalone timeToFirstToken() factory default unit
        assert.deepEqual(llm.timeToFirstToken(10), { timeUnit: 'MILLISECONDS', value: 10 });
        // streamingPhysics withSeed
        assert.deepEqual(wire(llm.streamingPhysics().withSeed(99)), { seed: 99 });
    });

    it('embedding withSeed serialises and is omitted when null', function () {
        assert.deepEqual(wire(llm.embedding().withSeed(123)), { seed: 123 });
        assert.deepEqual(wire(llm.embedding()), {});
    });

    it('toolUse withName overrides the constructor name; toolUse factory works without new', function () {
        var tu = llm.toolUse('orig').withName('renamed').withId('id1').withArguments('{}');
        assert.deepEqual(wire(tu), { id: 'id1', name: 'renamed', arguments: '{}' });
        // constructor invoked without `new`
        var direct = llm.ToolUse('plain');
        assert.ok(direct instanceof llm.ToolUse);
        assert.deepEqual(wire(direct), { name: 'plain' });
    });

    it('completion withToolCalls accepts both an array and varargs, plus outputSchema/model', function () {
        var arrForm = wire(llm.completion().withToolCalls([
            llm.toolUse('a').withArguments('{}'),
            llm.toolUse('b').withArguments('{}')
        ]));
        assert.equal(arrForm.toolCalls.length, 2);
        assert.deepEqual(arrForm.toolCalls.map(function (t) { return t.name; }), ['a', 'b']);

        var varargsForm = wire(llm.completion().withToolCalls(
            llm.toolUse('x').withArguments('{}'),
            llm.toolUse('y').withArguments('{}'),
            llm.toolUse('z').withArguments('{}')
        ));
        assert.equal(varargsForm.toolCalls.length, 3);

        // outputSchema accepts an object (serialised) or a string (verbatim), model passes through
        assert.equal(wire(llm.completion().withOutputSchema({ type: 'object' })).outputSchema, '{"type":"object"}');
        assert.equal(wire(llm.completion().withOutputSchema('{"type":"string"}')).outputSchema, '{"type":"string"}');
        assert.equal(wire(llm.completion().withModel('claude-opus-4')).model, 'claude-opus-4');
    });

    it('the bare model-factory functions construct fresh instances without new', function () {
        assert.ok(llm.usage() instanceof llm.Usage);
        assert.ok(llm.completion() instanceof llm.Completion);
        assert.ok(llm.embedding() instanceof llm.EmbeddingResponse);
        assert.ok(llm.streamingPhysics() instanceof llm.StreamingPhysics);
        assert.ok(llm.llmMock('/p') instanceof llm.LlmMockBuilder);
        assert.ok(llm.conversation() instanceof llm.LlmConversationBuilder);
        assert.ok(llm.llmFailover() instanceof llm.LlmFailoverBuilder);
    });
});

// =========================================================================
describe('llm isolation sources', function () {
    it('header/queryParameter/cookie encode as kind:name', function () {
        assert.equal(llm.header('x-a').encode(), 'header:x-a');
        assert.equal(llm.queryParameter('q').encode(), 'query_parameter:q');
        assert.equal(llm.cookie('sid').encode(), 'cookie:sid');
    });

    it('an isolation source rejects an empty name', function () {
        assert.throws(function () { llm.header(''); }, /name must not be null or empty/);
        assert.throws(function () { new llm.IsolationSource('header', null); }, /name must not be null or empty/);
    });
});

// =========================================================================
describe('llm mock builder edge cases', function () {
    it('respondingWith a Completion sets completion and clears embedding (and vice versa)', function () {
        // embedding then completion clears embedding
        var b = llm.llmMock('/p').withProvider(llm.Provider.OPENAI)
            .respondingWith(llm.embedding().withDimensions(8))
            .respondingWith(llm.completion().withText('hi'));
        var wired = wire(b.build());
        assert.equal(wired.httpLlmResponse.completion.text, 'hi');
        assert.equal(wired.httpLlmResponse.embedding, undefined);
    });

    it('applyTo delegates to client.mockWithLLM', function () {
        var captured = null;
        var result = llm.llmMock('/p')
            .withProvider(llm.Provider.OPENAI)
            .respondingWith(llm.completion().withText('x'))
            .applyTo({ mockWithLLM: function (e) { captured = e; return 'OK'; } });
        assert.equal(result, 'OK');
        assert.equal(captured.httpRequest.path, '/p');
    });
});

// =========================================================================
describe('llm conversation builder — predicates, modifiers and validation', function () {
    it('encodes every turn predicate and the normalization modifier', function () {
        var expectations = llm.conversation()
            .withPath('/v1/messages')
            .withProvider(llm.Provider.ANTHROPIC)
            .withModel('claude-sonnet-4')
            .turn()
                .whenTurnIndex(0)
                .whenLatestMessageContains('weather')
                .whenLatestMessageMatches(/temp.*/)
                .whenLatestMessageRole(llm.Role.USER)
                .whenContainsToolResultFor('get_weather')
                .whenSemanticMatch('user asks about weather')
                .withNormalization('LOWERCASE')
                .withChaos({ dropRate: 0.1 })
                .respondingWith(llm.completion().withText('it is sunny'))
            .build();

        var wired = wire(expectations);
        assert.equal(wired.length, 1);
        assert.deepEqual(wired[0].httpLlmResponse.conversationPredicates, {
            turnIndex: 0,
            latestMessageContains: 'weather',
            latestMessageMatches: 'temp.*',
            latestMessageRole: 'USER',
            containsToolResultFor: 'get_weather',
            semanticMatchAgainst: 'user asks about weather',
            normalization: 'LOWERCASE'
        });
        assert.deepEqual(wired[0].httpLlmResponse.chaos, { dropRate: 0.1 });
    });

    it('whenLatestMessageMatches accepts a string regex and rejects null', function () {
        var expectations = llm.conversation()
            .withPath('/p').withProvider(llm.Provider.OPENAI)
            .turn().whenLatestMessageMatches('^hello$').respondingWith(llm.completion().withText('hi'))
            .build();
        assert.equal(wire(expectations)[0].httpLlmResponse.conversationPredicates.latestMessageMatches, '^hello$');
        assert.throws(function () {
            llm.conversation().turn().whenLatestMessageMatches(null);
        }, /regex must not be null/);
    });

    it('a turn with no predicate omits conversationPredicates entirely', function () {
        var expectations = llm.conversation()
            .withPath('/p').withProvider(llm.Provider.OPENAI)
            .turn().respondingWith(llm.completion().withText('hi'))
            .build();
        assert.equal(wire(expectations)[0].httpLlmResponse.conversationPredicates, undefined);
    });

    it('normalization alone is a modifier, not a predicate, so conversationPredicates is omitted', function () {
        var expectations = llm.conversation()
            .withPath('/p').withProvider(llm.Provider.OPENAI)
            .turn().withNormalization('TRIM').respondingWith(llm.completion().withText('hi'))
            .build();
        assert.equal(wire(expectations)[0].httpLlmResponse.conversationPredicates, undefined);
    });

    it('TurnBuilder.turn() chains a second turn, and .build()/.applyTo() delegate to the parent', function () {
        var captured = null;
        var conv = llm.conversation()
            .withPath('/p').withProvider(llm.Provider.OPENAI);
        var built = conv
            .turn().whenTurnIndex(0).respondingWith(llm.completion().withText('a'))
            .turn().whenTurnIndex(1).respondingWith(llm.completion().withText('b'))
            .build(); // build() called on the TurnBuilder delegates to the conversation
        assert.equal(built.length, 2);
        assert.equal(built[0].scenarioState, 'Started');
        assert.equal(built[1].scenarioState, 'turn_1');

        // applyTo on a TurnBuilder delegates through the parent conversation
        var result = llm.conversation()
            .withPath('/p').withProvider(llm.Provider.OPENAI)
            .turn().whenTurnIndex(0).respondingWith(llm.completion().withText('a'))
            .applyTo({ mockWithLLM: function (e) { captured = e; return 'CONV'; } });
        assert.equal(result, 'CONV');
        assert.equal(captured.length, 1);
    });

    it('LlmConversationBuilder.applyTo delegates to client.mockWithLLM', function () {
        var captured = null;
        var result = llm.conversation()
            .withPath('/p').withProvider(llm.Provider.OPENAI)
            .turn().whenTurnIndex(0).respondingWith(llm.completion().withText('a')).andThen()
            .applyTo({ mockWithLLM: function (e) { captured = e; return 'C'; } });
        assert.equal(result, 'C');
        assert.equal(captured.length, 1);
    });

    it('validates that turns, path and provider are present before building', function () {
        assert.throws(function () {
            llm.conversation().withPath('/p').withProvider(llm.Provider.OPENAI).build();
        }, /At least one turn must be defined/);
        assert.throws(function () {
            llm.conversation().turn().respondingWith(llm.completion().withText('x')).andThen().build();
        }, /Path must be set/);
        assert.throws(function () {
            llm.conversation().withPath('/p').turn().respondingWith(llm.completion().withText('x')).andThen().build();
        }, /Provider must be set/);
    });
});
