import { describe, it, expect, vi, afterEach } from 'vitest';
import {
  listFiles,
  storeFile,
  deleteFile,
  retrieveFileText,
} from '../lib/fileStore';

const params = { host: '127.0.0.1', port: '1080', secure: false };
afterEach(() => { vi.restoreAllMocks(); });

describe('fileStore client', () => {
  it('PUTs to /files/list and returns a string array', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ['a.json', 'logo.png'],
    });
    vi.stubGlobal('fetch', fetchMock);
    const result = await listFiles(params);
    const [url, init] = fetchMock.mock.calls[0]!;
    expect(url).toBe('http://127.0.0.1:1080/mockserver/files/list');
    expect((init as RequestInit).method).toBe('PUT');
    expect(result).toEqual(['a.json', 'logo.png']);
  });

  it('returns an empty array when the body is not an array', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => ({}) }));
    expect(await listFiles(params)).toEqual([]);
  });

  it('PUTs a store body with name, content, and base64 flag', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ name: 'test.json', size: 42 }),
    });
    vi.stubGlobal('fetch', fetchMock);
    const result = await storeFile(params, { name: 'test.json', content: '{}', base64: false });
    const [url, init] = fetchMock.mock.calls[0]!;
    expect(url).toBe('http://127.0.0.1:1080/mockserver/files/store');
    expect((init as RequestInit).method).toBe('PUT');
    expect(JSON.parse((init as RequestInit).body as string)).toEqual({ name: 'test.json', content: '{}', base64: false });
    expect(result).toEqual({ name: 'test.json', size: 42 });
  });

  it('PUTs a delete body with the file name', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, text: async () => '' });
    vi.stubGlobal('fetch', fetchMock);
    await deleteFile(params, 'old.txt');
    const [url, init] = fetchMock.mock.calls[0]!;
    expect(url).toBe('http://127.0.0.1:1080/mockserver/files/delete');
    expect((init as RequestInit).method).toBe('PUT');
    expect(JSON.parse((init as RequestInit).body as string)).toEqual({ name: 'old.txt' });
  });

  it('PUTs a retrieve body and returns the raw text', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, text: async () => 'file-content-here' });
    vi.stubGlobal('fetch', fetchMock);
    const content = await retrieveFileText(params, 'readme.txt');
    const [url, init] = fetchMock.mock.calls[0]!;
    expect(url).toBe('http://127.0.0.1:1080/mockserver/files/retrieve');
    expect((init as RequestInit).method).toBe('PUT');
    expect(JSON.parse((init as RequestInit).body as string)).toEqual({ name: 'readme.txt' });
    expect(content).toBe('file-content-here');
  });

  it('throws in the standard "MockServer returned <status>: <body>" shape on a non-ok response', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 404, text: async () => 'file not found: missing.json' }));
    await expect(retrieveFileText(params, 'missing.json')).rejects.toThrow(
      'MockServer returned 404: file not found: missing.json',
    );
  });

  it('throws the standard shape with an empty body when the error body is empty', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 500, text: async () => '' }));
    await expect(listFiles(params)).rejects.toThrow('MockServer returned 500: ');
  });

  it('passes the abort signal to list', async () => {
    const controller = new AbortController();
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => [] });
    vi.stubGlobal('fetch', fetchMock);
    await listFiles(params, controller.signal);
    expect((fetchMock.mock.calls[0]![1] as RequestInit).signal).toBe(controller.signal);
  });
});
