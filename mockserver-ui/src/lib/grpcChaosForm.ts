/**
 * gRPC chaos form state, empty-form constant, and profile builder — extracted
 * from ServiceChaosPanel.tsx so that a component file only exports components
 * (satisfies react-refresh/only-export-components).
 */
import type { GrpcChaosProfileDTO } from './grpcChaos';

export interface GrpcChaosFormState {
  service: string;
  errorStatusCode: string;
  errorProbability: string;
  errorMessage: string;
  seed: string;
  latencyMs: string;
  succeedFirst: string;
  failRequestCount: string;
  quotaName: string;
  quotaLimit: string;
  quotaWindowMillis: string;
  ttlMs: string;
  omitGrpcStatus: boolean;
  corruptGrpcStatus: boolean;
  customTrailers: string;
  abortAfterMessages: string;
}

export const EMPTY_GRPC_CHAOS_FORM: GrpcChaosFormState = {
  service: '',
  errorStatusCode: 'UNAVAILABLE',
  errorProbability: '',
  errorMessage: '',
  seed: '',
  latencyMs: '',
  succeedFirst: '',
  failRequestCount: '',
  quotaName: '',
  quotaLimit: '',
  quotaWindowMillis: '',
  ttlMs: '',
  omitGrpcStatus: false,
  corruptGrpcStatus: false,
  customTrailers: '',
  abortAfterMessages: '',
};

/** Parse a trimmed numeric field, or undefined when blank. NaN is treated as undefined. */
function num(raw: string): number | undefined {
  const trimmed = raw.trim();
  if (trimmed === '') return undefined;
  const value = Number(trimmed);
  return Number.isFinite(value) ? value : undefined;
}

/** Parse "key=value" per line into a Record, ignoring blank/invalid lines. */
function parseCustomTrailers(raw: string): Record<string, string> | undefined {
  const lines = raw.split('\n').filter((l) => l.trim());
  if (lines.length === 0) return undefined;
  const result: Record<string, string> = {};
  for (const line of lines) {
    const eqIdx = line.indexOf('=');
    if (eqIdx < 1) continue;
    const key = line.slice(0, eqIdx).trim();
    const value = line.slice(eqIdx + 1).trim();
    if (key) result[key] = value;
  }
  return Object.keys(result).length > 0 ? result : undefined;
}

export function buildGrpcChaosProfile(form: GrpcChaosFormState): GrpcChaosProfileDTO {
  const profile: GrpcChaosProfileDTO = {};
  const errorProbability = num(form.errorProbability);
  if (form.errorStatusCode) {
    profile.errorStatusCode = form.errorStatusCode;
    // gRPC fault injection only fires when errorProbability > 0; default to always-inject when
    // a status code is chosen but no probability is given, so error-status-only is not a no-op.
    profile.errorProbability = errorProbability ?? 1;
  } else if (errorProbability != null) {
    profile.errorProbability = errorProbability;
  }
  const errorMessage = form.errorMessage.trim();
  if (errorMessage) profile.errorMessage = errorMessage;
  const seed = num(form.seed);
  if (seed != null) profile.seed = seed;
  const latencyMs = num(form.latencyMs);
  if (latencyMs != null) profile.latencyMs = latencyMs;
  const succeedFirst = num(form.succeedFirst);
  if (succeedFirst != null) profile.succeedFirst = succeedFirst;
  const failRequestCount = num(form.failRequestCount);
  if (failRequestCount != null) profile.failRequestCount = failRequestCount;
  const quotaName = form.quotaName.trim();
  if (quotaName) profile.quotaName = quotaName;
  const quotaLimit = num(form.quotaLimit);
  if (quotaLimit != null) profile.quotaLimit = quotaLimit;
  const quotaWindowMillis = num(form.quotaWindowMillis);
  if (quotaWindowMillis != null) profile.quotaWindowMillis = quotaWindowMillis;
  if (form.omitGrpcStatus) profile.omitGrpcStatus = true;
  if (form.corruptGrpcStatus) profile.corruptGrpcStatus = true;
  const trailers = parseCustomTrailers(form.customTrailers);
  if (trailers) profile.customTrailers = trailers;
  const abortAfterMessages = num(form.abortAfterMessages);
  if (abortAfterMessages != null) profile.abortAfterMessages = abortAfterMessages;
  return profile;
}
