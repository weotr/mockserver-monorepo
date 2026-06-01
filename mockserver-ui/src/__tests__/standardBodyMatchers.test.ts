import { describe, it, expect } from 'vitest';
import {
  buildExpectationJson,
  standardToJava,
  standardToCurl,
  type StandardMatcher,
  type StandardActionPayload,
} from '../lib/standardCodegen';

function baseMatcher(overrides?: Partial<StandardMatcher>): StandardMatcher {
  return {
    id: '',
    method: 'POST',
    path: '/api/test',
    headers: '',
    queryString: '',
    cookies: '',
    pathParams: '',
    body: '',
    bodyBinary: false,
    bodyMatcherType: 'string',
    secure: false,
    priority: 0,
    times: 0,
    ...overrides,
  };
}

function baseAction(): StandardActionPayload {
  return {
    type: 'static',
    static: { statusCode: 200, body: '{"ok":true}', contentType: 'application/json' },
  };
}

// ---------------------------------------------------------------------------
// buildExpectationJson — JSON body shapes for each new matcher type
// ---------------------------------------------------------------------------

describe('buildExpectationJson body matcher types', () => {
  it('emits STRING body for the default string type', () => {
    const m = baseMatcher({ body: '{"foo":"bar"}' });
    const result = buildExpectationJson(m, baseAction());
    // String type emits the body as a plain string (no type wrapper)
    expect(result['httpRequest']).toHaveProperty('body', '{"foo":"bar"}');
  });

  it('emits JSON_SCHEMA body', () => {
    const schema = '{"type":"object","properties":{"name":{"type":"string"}}}';
    const m = baseMatcher({ body: schema, bodyMatcherType: 'json-schema' });
    const result = buildExpectationJson(m, baseAction());
    const body = (result['httpRequest'] as Record<string, unknown>)['body'] as Record<string, unknown>;
    expect(body).toEqual({ type: 'JSON_SCHEMA', jsonSchema: schema });
  });

  it('emits JSON_PATH body', () => {
    const expr = '$.store.book[0].title';
    const m = baseMatcher({ body: expr, bodyMatcherType: 'json-path' });
    const result = buildExpectationJson(m, baseAction());
    const body = (result['httpRequest'] as Record<string, unknown>)['body'] as Record<string, unknown>;
    expect(body).toEqual({ type: 'JSON_PATH', jsonPath: expr });
  });

  it('emits XML body', () => {
    const xml = '<root><element>value</element></root>';
    const m = baseMatcher({ body: xml, bodyMatcherType: 'xml' });
    const result = buildExpectationJson(m, baseAction());
    const body = (result['httpRequest'] as Record<string, unknown>)['body'] as Record<string, unknown>;
    expect(body).toEqual({ type: 'XML', xml });
  });

  it('emits XML_SCHEMA body', () => {
    const xsd = '<xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema"><xs:element name="root"/></xs:schema>';
    const m = baseMatcher({ body: xsd, bodyMatcherType: 'xml-schema' });
    const result = buildExpectationJson(m, baseAction());
    const body = (result['httpRequest'] as Record<string, unknown>)['body'] as Record<string, unknown>;
    expect(body).toEqual({ type: 'XML_SCHEMA', xmlSchema: xsd });
  });

  it('emits XPATH body', () => {
    const xpath = '/root/element[@attr="value"]';
    const m = baseMatcher({ body: xpath, bodyMatcherType: 'xpath' });
    const result = buildExpectationJson(m, baseAction());
    const body = (result['httpRequest'] as Record<string, unknown>)['body'] as Record<string, unknown>;
    expect(body).toEqual({ type: 'XPATH', xpath });
  });

  it('emits REGEX body', () => {
    const regex = '^Hello.*World$';
    const m = baseMatcher({ body: regex, bodyMatcherType: 'regex' });
    const result = buildExpectationJson(m, baseAction());
    const body = (result['httpRequest'] as Record<string, unknown>)['body'] as Record<string, unknown>;
    expect(body).toEqual({ type: 'REGEX', regex });
  });

  it('emits PARAMETERS body from key=value lines', () => {
    const m = baseMatcher({ body: 'username=admin\npassword=secret', bodyMatcherType: 'parameters' });
    const result = buildExpectationJson(m, baseAction());
    const body = (result['httpRequest'] as Record<string, unknown>)['body'] as Record<string, unknown>;
    expect(body).toEqual({
      type: 'PARAMETERS',
      parameters: { username: ['admin'], password: ['secret'] },
    });
  });

  it('emits PARAMETERS with multiple values for same key', () => {
    const m = baseMatcher({ body: 'tag=a\ntag=b\ntag=c', bodyMatcherType: 'parameters' });
    const result = buildExpectationJson(m, baseAction());
    const body = (result['httpRequest'] as Record<string, unknown>)['body'] as Record<string, unknown>;
    expect(body).toEqual({
      type: 'PARAMETERS',
      parameters: { tag: ['a', 'b', 'c'] },
    });
  });

  it('emits PARAMETERS with empty object when body has no valid key=value pairs', () => {
    const m = baseMatcher({ body: 'no-equals-sign', bodyMatcherType: 'parameters' });
    const result = buildExpectationJson(m, baseAction());
    const body = (result['httpRequest'] as Record<string, unknown>)['body'] as Record<string, unknown>;
    expect(body).toEqual({ type: 'PARAMETERS', parameters: {} });
  });

  it('trims whitespace from body values', () => {
    const m = baseMatcher({ body: '  $.store.book  ', bodyMatcherType: 'json-path' });
    const result = buildExpectationJson(m, baseAction());
    const body = (result['httpRequest'] as Record<string, unknown>)['body'] as Record<string, unknown>;
    expect(body).toEqual({ type: 'JSON_PATH', jsonPath: '$.store.book' });
  });

  it('does not emit body when body text is empty for new types', () => {
    const m = baseMatcher({ body: '   ', bodyMatcherType: 'json-schema' });
    const result = buildExpectationJson(m, baseAction());
    expect((result['httpRequest'] as Record<string, unknown>)['body']).toBeUndefined();
  });

  it('preserves existing graphql behaviour unchanged', () => {
    const m = baseMatcher({
      body: '{ hero { name } }',
      bodyMatcherType: 'graphql',
      graphqlOptions: { selectionSetMatchType: 'AST_SUBSET', fields: 'hero, name' },
    });
    const result = buildExpectationJson(m, baseAction());
    const body = (result['httpRequest'] as Record<string, unknown>)['body'] as Record<string, unknown>;
    expect(body).toEqual({
      type: 'GRAPHQL',
      graphql: '{ hero { name } }',
      selectionSetMatchType: 'AST_SUBSET',
      fields: ['hero', 'name'],
    });
  });

  it('preserves existing binary behaviour unchanged', () => {
    const m = baseMatcher({ body: 'SGVsbG8=', bodyMatcherType: 'binary', bodyBinary: true });
    const result = buildExpectationJson(m, baseAction());
    const body = (result['httpRequest'] as Record<string, unknown>)['body'] as Record<string, unknown>;
    expect(body).toEqual({ type: 'BINARY', base64Bytes: 'SGVsbG8=' });
  });
});

// ---------------------------------------------------------------------------
// standardToJava — Java codegen for each new matcher type
// ---------------------------------------------------------------------------

describe('standardToJava body matcher types', () => {
  it('generates jsonSchema() for json-schema', () => {
    const m = baseMatcher({ body: '{"type":"object"}', bodyMatcherType: 'json-schema' });
    const java = standardToJava(m, baseAction());
    expect(java).toContain('.withBody(jsonSchema("{\\"type\\":\\"object\\"}"))');
  });

  it('generates jsonPath() for json-path', () => {
    const m = baseMatcher({ body: '$.store.book', bodyMatcherType: 'json-path' });
    const java = standardToJava(m, baseAction());
    expect(java).toContain('.withBody(jsonPath("$.store.book"))');
  });

  it('generates xml() for xml', () => {
    const m = baseMatcher({ body: '<root/>', bodyMatcherType: 'xml' });
    const java = standardToJava(m, baseAction());
    expect(java).toContain('.withBody(xml("<root/>"))');
  });

  it('generates xmlSchema() for xml-schema', () => {
    const m = baseMatcher({ body: '<xs:schema/>', bodyMatcherType: 'xml-schema' });
    const java = standardToJava(m, baseAction());
    expect(java).toContain('.withBody(xmlSchema("<xs:schema/>"))');
  });

  it('generates xpath() for xpath', () => {
    const m = baseMatcher({ body: '/root/element', bodyMatcherType: 'xpath' });
    const java = standardToJava(m, baseAction());
    expect(java).toContain('.withBody(xpath("/root/element"))');
  });

  it('generates regex() for regex', () => {
    const m = baseMatcher({ body: '^Hello$', bodyMatcherType: 'regex' });
    const java = standardToJava(m, baseAction());
    expect(java).toContain('.withBody(regex("^Hello$"))');
  });

  it('generates params() for parameters', () => {
    const m = baseMatcher({ body: 'key=val', bodyMatcherType: 'parameters' });
    const java = standardToJava(m, baseAction());
    expect(java).toContain('.withBody(params(param("key", "val")))');
  });

  it('generates params() with multiple entries', () => {
    const m = baseMatcher({ body: 'a=1\nb=2', bodyMatcherType: 'parameters' });
    const java = standardToJava(m, baseAction());
    expect(java).toContain('param("a", "1")');
    expect(java).toContain('param("b", "2")');
  });
});

// ---------------------------------------------------------------------------
// standardToCurl — curl codegen includes the correct body
// ---------------------------------------------------------------------------

describe('standardToCurl body matcher types', () => {
  it('includes JSON_SCHEMA in curl payload', () => {
    const m = baseMatcher({ body: '{"type":"string"}', bodyMatcherType: 'json-schema' });
    const curl = standardToCurl(m, baseAction(), 'http://localhost:1080');
    expect(curl).toContain('"JSON_SCHEMA"');
    expect(curl).toContain('"jsonSchema"');
  });

  it('includes REGEX in curl payload', () => {
    const m = baseMatcher({ body: '^test$', bodyMatcherType: 'regex' });
    const curl = standardToCurl(m, baseAction(), 'http://localhost:1080');
    expect(curl).toContain('"REGEX"');
    expect(curl).toContain('"regex"');
  });

  it('includes PARAMETERS in curl payload', () => {
    const m = baseMatcher({ body: 'foo=bar', bodyMatcherType: 'parameters' });
    const curl = standardToCurl(m, baseAction(), 'http://localhost:1080');
    expect(curl).toContain('"PARAMETERS"');
    expect(curl).toContain('"parameters"');
  });

  it('includes XPATH in curl payload', () => {
    const m = baseMatcher({ body: '/root', bodyMatcherType: 'xpath' });
    const curl = standardToCurl(m, baseAction(), 'http://localhost:1080');
    expect(curl).toContain('"XPATH"');
    expect(curl).toContain('"xpath"');
  });
});
