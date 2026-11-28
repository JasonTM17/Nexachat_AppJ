import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ReportVideoPreview } from '../../src/components/ReportVideoPreview';
import { I18nProvider } from '../../src/i18n';
import { ApiException } from '../../src/api';

vi.mock('../../src/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../src/api')>();
  return { ...actual, api: vi.fn() };
});

import { api } from '../../src/api';

const apiMock = api as ReturnType<typeof vi.fn>;

const MAX_VIDEO_BYTES = 4 * 1024 * 1024;

let originalCreateObjectURL: typeof URL.createObjectURL | undefined;
let originalRevokeObjectURL: typeof URL.revokeObjectURL | undefined;
let createObjectURLMock: ReturnType<typeof vi.fn>;
let revokeObjectURLMock: ReturnType<typeof vi.fn>;

function renderPreview(reportId = 'r1') {
  return render(
    <I18nProvider>
      <ReportVideoPreview reportId={reportId} />
    </I18nProvider>,
  );
}

beforeEach(() => {
  apiMock.mockReset();
  localStorage.clear();
  originalCreateObjectURL = URL.createObjectURL;
  originalRevokeObjectURL = URL.revokeObjectURL;
  createObjectURLMock = vi.fn().mockReturnValue('blob:https://admin.test/video-1');
  revokeObjectURLMock = vi.fn();
  Object.defineProperty(URL, 'createObjectURL', {
    configurable: true,
    writable: true,
    value: createObjectURLMock,
  });
  Object.defineProperty(URL, 'revokeObjectURL', {
    configurable: true,
    writable: true,
    value: revokeObjectURLMock,
  });
});

afterEach(() => {
  cleanup();
  if (originalCreateObjectURL) {
    Object.defineProperty(URL, 'createObjectURL', {
      configurable: true,
      writable: true,
      value: originalCreateObjectURL,
    });
  } else {
    delete (URL as unknown as Record<string, unknown>).createObjectURL;
  }
  if (originalRevokeObjectURL) {
    Object.defineProperty(URL, 'revokeObjectURL', {
      configurable: true,
      writable: true,
      value: originalRevokeObjectURL,
    });
  } else {
    delete (URL as unknown as Record<string, unknown>).revokeObjectURL;
  }
});

describe('ReportVideoPreview', () => {
  it('starts idle with no automatic API call', () => {
    renderPreview('r1');
    expect(screen.getByRole('button', { name: 'Load reported video' })).toBeEnabled();
    expect(document.querySelector('video')).toBeNull();
    expect(apiMock).not.toHaveBeenCalled();
  });

  it('loads a valid video/mp4 blob on click, encodes reportId, ignores duplicate clicks, and renders video', async () => {
    const user = userEvent.setup();
    let resolveBlob!: (b: Blob) => void;
    apiMock.mockImplementationOnce(
      () => new Promise<Blob>((resolve) => { resolveBlob = resolve; }),
    );
    renderPreview('r/1');

    const loadBtn = screen.getByRole('button', { name: 'Load reported video' });
    await user.click(loadBtn);

    expect(loadBtn).toBeDisabled();
    expect(loadBtn).toHaveAttribute('aria-busy', 'true');
    expect(screen.getByRole('status')).toHaveTextContent('Loading video…');

    // Duplicate click while loading is ignored
    await user.click(loadBtn);
    expect(apiMock).toHaveBeenCalledTimes(1);
    expect(apiMock).toHaveBeenCalledWith('/api/v1/admin/feed/reports/r%2F1/video', {
      method: 'POST',
      asBlob: true,
      signal: expect.any(AbortSignal),
    });

    const validBlob = new Blob(['mp4-bytes'], { type: 'video/mp4' });
    resolveBlob(validBlob);

    const video = await screen.findByLabelText('Reported video');
    expect(video.tagName).toBe('VIDEO');
    expect(video).toHaveAttribute('src', 'blob:https://admin.test/video-1');
    expect(createObjectURLMock).toHaveBeenCalledWith(validBlob);
    expect(screen.getByRole('button', { name: 'Close video' })).toBeInTheDocument();
  });

  it('rejects non-mp4, empty, and oversized (>4 MiB) blobs without calling createObjectURL', async () => {
    const user = userEvent.setup();
    renderPreview('r1');

    // 1. Wrong MIME type
    apiMock.mockResolvedValueOnce(new Blob(['not-mp4'], { type: 'image/png' }));
    await user.click(screen.getByRole('button', { name: 'Load reported video' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Could not play this video. Try again.');
    expect(createObjectURLMock).not.toHaveBeenCalled();

    // 2. Empty 0-byte blob
    apiMock.mockResolvedValueOnce(new Blob([], { type: 'video/mp4' }));
    await user.click(screen.getByRole('button', { name: 'Load reported video' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Could not play this video. Try again.');
    expect(createObjectURLMock).not.toHaveBeenCalled();

    // 3. Oversized > 4 MiB blob
    const oversized = new Blob(['x'], { type: 'video/mp4' });
    Object.defineProperty(oversized, 'size', { value: MAX_VIDEO_BYTES + 1 });
    apiMock.mockResolvedValueOnce(oversized);
    await user.click(screen.getByRole('button', { name: 'Load reported video' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Could not play this video. Try again.');
    expect(createObjectURLMock).not.toHaveBeenCalled();
  });

  it('shows error on API failure without auto-retry and allows manual retry', async () => {
    const user = userEvent.setup();
    apiMock.mockRejectedValueOnce(new ApiException(404, 'FILE_NOT_FOUND', 'missing'));
    renderPreview('r1');

    await user.click(screen.getByRole('button', { name: 'Load reported video' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Could not play this video. Try again.');
    expect(apiMock).toHaveBeenCalledTimes(1);

    apiMock.mockResolvedValueOnce(new Blob(['ok'], { type: 'video/mp4' }));
    await user.click(screen.getByRole('button', { name: 'Load reported video' }));
    expect(await screen.findByLabelText('Reported video')).toBeInTheDocument();
    expect(apiMock).toHaveBeenCalledTimes(2);
  });

  it('revokes object URL and shows error when <video> fires error event', async () => {
    const user = userEvent.setup();
    apiMock.mockResolvedValueOnce(new Blob(['bad-mp4'], { type: 'video/mp4' }));
    renderPreview('r1');

    await user.click(screen.getByRole('button', { name: 'Load reported video' }));
    const video = await screen.findByLabelText('Reported video');
    fireEvent.error(video);

    expect(revokeObjectURLMock).toHaveBeenCalledWith('blob:https://admin.test/video-1');
    expect(document.querySelector('video')).toBeNull();
    expect(screen.getByRole('status')).toHaveTextContent('Could not play this video. Try again.');
  });

  it('revokes object URL on Close video and fetches a fresh blob when re-opened', async () => {
    const user = userEvent.setup();
    createObjectURLMock
      .mockReturnValueOnce('blob:https://admin.test/video-1')
      .mockReturnValueOnce('blob:https://admin.test/video-2');
    apiMock.mockResolvedValue(new Blob(['mp4'], { type: 'video/mp4' }));
    renderPreview('r1');

    await user.click(screen.getByRole('button', { name: 'Load reported video' }));
    await screen.findByLabelText('Reported video');

    await user.click(screen.getByRole('button', { name: 'Close video' }));
    expect(revokeObjectURLMock).toHaveBeenCalledWith('blob:https://admin.test/video-1');
    expect(document.querySelector('video')).toBeNull();

    await user.click(screen.getByRole('button', { name: 'Load reported video' }));
    const video2 = await screen.findByLabelText('Reported video');
    expect(video2).toHaveAttribute('src', 'blob:https://admin.test/video-2');
    expect(apiMock).toHaveBeenCalledTimes(2);
  });

  it('revokes active object URL on unmount and aborts mid-fetch without leaking URL on late resolution', async () => {
    const user = userEvent.setup();
    // Part A: unmount with active video revokes URL
    apiMock.mockResolvedValueOnce(new Blob(['mp4'], { type: 'video/mp4' }));
    const first = renderPreview('r1');
    await user.click(screen.getByRole('button', { name: 'Load reported video' }));
    await screen.findByLabelText('Reported video');
    first.unmount();
    expect(revokeObjectURLMock).toHaveBeenCalledWith('blob:https://admin.test/video-1');

    // Part B: unmount mid-fetch aborts signal and ignores late resolution
    createObjectURLMock.mockClear();
    let capturedSignal: AbortSignal | undefined;
    let resolveLate!: (b: Blob) => void;
    apiMock.mockImplementationOnce((_path: string, opts?: { signal?: AbortSignal }) => {
      capturedSignal = opts?.signal;
      return new Promise<Blob>((resolve) => { resolveLate = resolve; });
    });
    const second = renderPreview('r2');
    await user.click(screen.getByRole('button', { name: 'Load reported video' }));
    expect(capturedSignal?.aborted).toBe(false);

    second.unmount();
    expect(capturedSignal?.aborted).toBe(true);

    resolveLate(new Blob(['late-mp4'], { type: 'video/mp4' }));
    await waitFor(() => {
      expect(createObjectURLMock).not.toHaveBeenCalled();
    });
  });

  it('accepts video/mp4 MIME with codec parameters and restores focus to Load button on Close video', async () => {
    const user = userEvent.setup();
    apiMock.mockResolvedValueOnce(new Blob(['mp4'], { type: 'video/mp4; codecs="avc1.42E01E"' }));
    renderPreview('r1');

    await user.click(screen.getByRole('button', { name: 'Load reported video' }));
    const video = await screen.findByLabelText('Reported video');
    expect(video).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Close video' }));
    expect(screen.getByRole('button', { name: 'Load reported video' })).toHaveFocus();
  });
});
