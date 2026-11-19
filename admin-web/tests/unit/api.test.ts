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

  it('resolves undefined for 204 and for an empty 200 body', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(new Response(null, { status: 204 })));
    await expect(api('/x', { method: 'POST' })).resolves.toBeUndefined();
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(new Response('', { status: 200 })));
    await expect(api('/x', { method: 'POST' })).resolves.toBeUndefined();
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

  it('treats a failed refresh as an expired session: clears tokens and signals', async () => {
    tokenStore.set('old', 'bad-refresh');
    const events: string[] = [];
    const onExpired = () => events.push('expired');
    window.addEventListener('admin:session-expired', onExpired);
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(401, { error: { code: 'TOKEN_EXPIRED' } }))
      .mockResolvedValueOnce(jsonResponse(401, { error: { code: 'INVALID_REFRESH' } }));
    vi.stubGlobal('fetch', fetchMock);
    await expect(api('/api/v1/admin/me')).rejects.toBeInstanceOf(ApiException);
    expect(tokenStore.access).toBeNull();
    expect(tokenStore.refresh).toBeNull();
    expect(events).toEqual(['expired']);
    expect(sessionStorage.getItem('admin.sessionExpired')).toBe('1');
    window.removeEventListener('admin:session-expired', onExpired);
    sessionStorage.clear();
  });

  it('does not signal expiry when there is no refresh token at all', async () => {
    const events: string[] = [];
    const onExpired = () => events.push('expired');
    window.addEventListener('admin:session-expired', onExpired);
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(jsonResponse(401, { error: { code: 'TOKEN_EXPIRED' } })),
    );
    await expect(api('/x')).rejects.toBeInstanceOf(ApiException);
    expect(events).toEqual([]);
    window.removeEventListener('admin:session-expired', onExpired);
  });
});
