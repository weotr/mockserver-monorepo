/**
 * Client for MockServer's PUT /mockserver/oidc — stands up a mock OIDC/OAuth2 provider
 * (discovery document, JWKS, token / userinfo / introspection / revocation endpoints) as a set of
 * expectations. All fields are optional; the server fills in sensible defaults.
 */
import { buildBaseUrl } from './mcpClient';
import type { ConnectionParams } from '../hooks/useConnectionParams';

export interface OidcConfig {
  issuer?: string;
  tokenPath?: string;
  subject?: string;
  clientId?: string;
  audience?: string;
  scopes?: string[];
  tokenExpirySeconds?: number;
  /** Negative-testing toggles. */
  issueExpiredToken?: boolean;
  wrongIssuer?: boolean;
  tamperedSignature?: boolean;
}

/**
 * Create the mock OIDC provider. Returns the number of expectations the server registered.
 */
export async function createOidcProvider(params: ConnectionParams, config: OidcConfig): Promise<number> {
  const res = await fetch(`${buildBaseUrl(params)}/mockserver/oidc`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(config),
  });
  if (!res.ok) {
    const text = await res.text().catch(() => '');
    throw new Error(text || `Failed to create OIDC provider (HTTP ${res.status} ${res.statusText})`);
  }
  const body = await res.json().catch(() => []);
  return Array.isArray(body) ? body.length : 0;
}
