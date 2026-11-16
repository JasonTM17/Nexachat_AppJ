import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiException, api, tokenStore } from '../../src/api';

const jsonResponse = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });

afterEach(() => {
  vi.unstubAllGlobals();
  tokenStore.clear();
});

describe('api', () => {
  it('sends Authorization when a token is stored', async () => {
    tokenStore.set('acc', 'ref');
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(200, { ok: true }));
    vi.stubGlobal('fetch', fetchMock);
    await api('/api/v1/admin/me');
    const headers = (fetchMock.mock.calls[0][1] as RequestInit).headers as Record<string, string>;
    expect(headers.Authorization).toBe('Bearer acc');
  });

  it('throws ApiException with the envelope code on error', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        jsonResponse(403, { error: { code: 'FORBIDDEN', message: 'no' } }),
      ),
    );
    await expect(api('/x', { auth: false })).rejects.toMatchObject({
      status: 403,
      code: 'FORBIDDEN',
    });
  });

  it('refreshes once on 401 then retries', async () => {
    tokenStore.set('old', 'good-refresh');
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(401, { error: { code: 'TOKEN_EXPIRED' } }))
      .mockResolvedValueOnce(
        jsonResponse(200, { accessToken: 'new-acc', refreshToken: 'new-ref' }),
      )
      .mockResolvedValueOnce(jsonResponse(200, { me: true }));
    vi.stubGlobal('fetch', fetchMock);
    const out = await api('/api/v1/admin/me');
    expect(out).toEqual({ me: true });
    expect(tokenStore.access).toBe('new-acc');
    expect(fetchMock).toHaveBeenCalledTimes(3);
  });

  it('gives up when refresh fails', async () => {
    tokenStore.set('old', 'bad-refresh');
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(401, { error: { code: 'TOKEN_EXPIRED' } }))
      .mockResolvedValueOnce(jsonResponse(401, { error: { code: 'X' } }));
    vi.stubGlobal('fetch', fetchMock);
    await expect(api('/api/v1/admin/me')).rejects.toBeInstanceOf(ApiException);
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });
});
