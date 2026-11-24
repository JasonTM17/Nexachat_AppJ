import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiException, api, tokenStore } from '../../src/api';

const jsonResponse = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
  tokenStore.clear();
  sessionStorage.clear();
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

  it('reserves an Idempotency-Key before refreshing and drops it on success', async () => {
    tokenStore.set('old', 'good-refresh');
    let reservedKey = '';
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(401, { error: { code: 'TOKEN_EXPIRED' } }))
      .mockImplementationOnce((_url: string, init: RequestInit) => {
        // The key is already in storage when the request leaves.
        reservedKey = (
          JSON.parse(sessionStorage.getItem('admin.idempotencyKey') ?? '{}') as { key?: string }
        ).key ?? '';
        expect((init.headers as Record<string, string>)['Idempotency-Key']).toBe(reservedKey);
        return Promise.resolve(
          jsonResponse(200, { accessToken: 'new-acc', refreshToken: 'new-ref' }),
        );
      })
      .mockResolvedValueOnce(jsonResponse(200, { me: true }));
    vi.stubGlobal('fetch', fetchMock);

    await expect(api('/api/v1/admin/me')).resolves.toEqual({ me: true });
    expect(reservedKey).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
    expect(sessionStorage.getItem('admin.idempotencyKey')).toBeNull();
  });

  it('shares one reserved key with a second request that joins the refresh mid-flight', async () => {
    tokenStore.set('old', 'good-refresh');
    let releaseRefresh: (res: Response) => void = () => {};
    const headerKeys: string[] = [];
    let plainCalls = 0;
    const fetchMock = vi.fn().mockImplementation((url: string, init: RequestInit) => {
      if (url.endsWith('/auth/refresh')) {
        headerKeys.push((init.headers as Record<string, string>)['Idempotency-Key']);
        return new Promise<Response>((resolve) => {
          releaseRefresh = resolve;
        });
      }
      plainCalls += 1;
      return Promise.resolve(
        plainCalls <= 2
          ? jsonResponse(401, { error: { code: 'TOKEN_EXPIRED' } })
          : jsonResponse(200, { ok: true }),
      );
    });
    vi.stubGlobal('fetch', fetchMock);

    const first = api('/a');
    const second = api('/b');
    await new Promise((r) => setTimeout(r, 0));
    expect(headerKeys).toHaveLength(1);

    releaseRefresh(jsonResponse(200, { accessToken: 'new-acc', refreshToken: 'new-ref' }));
    await expect(Promise.all([first, second])).resolves.toEqual([
      { ok: true },
      { ok: true },
    ]);
    expect(sessionStorage.getItem('admin.idempotencyKey')).toBeNull();
  });

  it('mints a new key when the stored one is bound to another refresh token', async () => {
    tokenStore.set('old', 'good-refresh');
    sessionStorage.setItem(
      'admin.idempotencyKey',
      JSON.stringify({ key: 'stale-key', refreshHash: 'deadbeef' }),
    );
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(401, { error: { code: 'TOKEN_EXPIRED' } }))
      .mockImplementationOnce((_url: string, init: RequestInit) => {
        expect((init.headers as Record<string, string>)['Idempotency-Key']).not.toBe('stale-key');
        return Promise.resolve(
          jsonResponse(200, { accessToken: 'new-acc', refreshToken: 'new-ref' }),
        );
      })
      .mockResolvedValueOnce(jsonResponse(200, { me: true }));
    vi.stubGlobal('fetch', fetchMock);

    await api('/api/v1/admin/me');
    expect(fetchMock).toHaveBeenCalledTimes(3);
  });

  it('drops the reserved key when the refresh expires the session', async () => {
    tokenStore.set('old', 'bad-refresh');
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(401, { error: { code: 'TOKEN_EXPIRED' } }))
      .mockResolvedValueOnce(jsonResponse(401, { error: { code: 'INVALID_REFRESH' } }));
    vi.stubGlobal('fetch', fetchMock);

    await expect(api('/api/v1/admin/me')).rejects.toBeInstanceOf(ApiException);
    expect(sessionStorage.getItem('admin.idempotencyKey')).toBeNull();
  });

  it('rejects a hung request as a retryable TIMEOUT ApiException', async () => {
    vi.useFakeTimers();
    vi.stubGlobal(
      'fetch',
      vi.fn().mockImplementation((_url: string, init: RequestInit) => {
        return new Promise((_resolve, reject) => {
          init.signal?.addEventListener('abort', () =>
            reject(new DOMException('Aborted', 'AbortError')),
          );
        });
      }),
    );

    const pending = api('/api/v1/admin/users', { auth: false });
    const assertion = expect(pending).rejects.toMatchObject({ status: 0, code: 'TIMEOUT' });
    await vi.advanceTimersByTimeAsync(15_000);
    await assertion;
  });

  it('times overview and health out sooner than other endpoints', async () => {
    vi.useFakeTimers();
    vi.stubGlobal(
      'fetch',
      vi.fn().mockImplementation((_url: string, init: RequestInit) => {
        return new Promise((_resolve, reject) => {
          init.signal?.addEventListener('abort', () =>
            reject(new DOMException('Aborted', 'AbortError')),
          );
        });
      }),
    );

    const pending = api('/api/v1/admin/system/health', { auth: false });
    const assertion = expect(pending).rejects.toMatchObject({ status: 0, code: 'TIMEOUT' });
    await vi.advanceTimersByTimeAsync(10_000);
    await assertion;
  });
});
