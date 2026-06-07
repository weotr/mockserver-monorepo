/**
 * Client for MockServer's gRPC descriptor endpoints:
 *   PUT /mockserver/grpc/descriptors  — load a compiled protobuf FileDescriptorSet (raw bytes,
 *                                        e.g. `protoc --descriptor_set_out=…`).
 *   PUT /mockserver/grpc/services     — list the services/methods from the loaded descriptors.
 *   PUT /mockserver/grpc/clear        — clear all loaded descriptors.
 */
import { buildBaseUrl } from './mcpClient';
import type { ConnectionParams } from '../hooks/useConnectionParams';

export interface GrpcMethod {
  name: string;
  inputType: string;
  outputType: string;
  clientStreaming: boolean;
  serverStreaming: boolean;
}

export interface GrpcService {
  name: string;
  methods: GrpcMethod[];
}

async function textError(res: Response): Promise<string> {
  const text = await res.text().catch(() => '');
  return text || `HTTP ${res.status} ${res.statusText}`;
}

/** Upload a compiled FileDescriptorSet (raw bytes). */
export async function uploadDescriptorSet(params: ConnectionParams, fileBytes: ArrayBuffer): Promise<void> {
  const res = await fetch(`${buildBaseUrl(params)}/mockserver/grpc/descriptors`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/octet-stream' },
    body: fileBytes,
  });
  if (!res.ok) throw new Error(await textError(res));
}

/** List the services + methods from the loaded descriptors. */
export async function listGrpcServices(params: ConnectionParams, signal?: AbortSignal): Promise<GrpcService[]> {
  const res = await fetch(`${buildBaseUrl(params)}/mockserver/grpc/services`, { method: 'PUT', signal });
  if (!res.ok) throw new Error(await textError(res));
  const body = await res.json().catch(() => []);
  return Array.isArray(body) ? (body as GrpcService[]) : [];
}

/** Clear all loaded gRPC descriptors. */
export async function clearGrpcDescriptors(params: ConnectionParams): Promise<void> {
  const res = await fetch(`${buildBaseUrl(params)}/mockserver/grpc/clear`, { method: 'PUT' });
  if (!res.ok) throw new Error(await textError(res));
}
