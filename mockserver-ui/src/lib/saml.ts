/**
 * Client for MockServer's PUT /mockserver/saml — stands up a mock SAML 2.0 identity
 * provider (metadata endpoint + SSO endpoint implementing the SP-initiated Web-Browser-SSO
 * POST profile) as a set of expectations. All fields are optional; the server fills in
 * sensible defaults so an empty body produces a fully functional mock IdP.
 *
 * Field names mirror the server's SamlProviderConfiguration exactly.
 */
import { buildBaseUrl } from './mcpClient';
import type { ConnectionParams } from '../hooks/useConnectionParams';

export interface SamlConfig {
  /** IdP entity ID (issuer). Default: http://localhost:1080/saml/idp */
  idpEntityId?: string;
  /** Path the SP posts the AuthnRequest to. Default: /saml/sso */
  ssoServiceUrl?: string;
  /** Path serving the IdP metadata XML. Default: /saml/metadata */
  metadataUrl?: string;
  /** Service-provider entity ID the assertion is issued for. */
  spEntityId?: string;
  /** SP Assertion Consumer Service URL the SAMLResponse is posted back to. */
  assertionConsumerServiceUrl?: string;
  /** Subject NameID asserted (e.g. the user's email). */
  subjectNameId?: string;
  /** NameID format URN. */
  nameIdFormat?: string;
  /** Extra SAML attribute statements (name -> value). */
  attributes?: Record<string, string>;
  /** Assertion / session validity window in seconds. */
  sessionDurationSeconds?: number;
}

/**
 * Create the mock SAML provider. Returns the number of expectations the server registered.
 *
 * On failure throws an Error whose message carries the `MockServer returned <status>: <body>`
 * shape so callers can pass it through {@link import('./errorMessage').humanizeError}.
 */
export async function createSamlProvider(params: ConnectionParams, config: SamlConfig): Promise<number> {
  const res = await fetch(`${buildBaseUrl(params)}/mockserver/saml`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(config),
  });
  if (!res.ok) {
    const text = await res.text().catch(() => '');
    throw new Error(`MockServer returned ${res.status}: ${text}`);
  }
  const body = await res.json().catch(() => []);
  return Array.isArray(body) ? body.length : 0;
}
