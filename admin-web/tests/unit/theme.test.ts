import { afterEach, describe, expect, it, vi } from 'vitest';
import { THEME_KEY, initialTheme, persistTheme } from '../../src/lib/theme';

const setOsDark = (dark: boolean) => {
  window.matchMedia = vi.fn().mockReturnValue({ matches: dark });
};

afterEach(() => {
  localStorage.clear();
  delete (window as unknown as Record<string, unknown>).matchMedia;
});

describe('theme', () => {
  it('prefers the stored explicit choice over the OS preference', () => {
    setOsDark(true);
    localStorage.setItem(THEME_KEY, 'light');
    expect(initialTheme()).toBe('light');
    localStorage.setItem(THEME_KEY, 'dark');
    expect(initialTheme()).toBe('dark');
  });

  it('falls back to the OS color scheme when nothing is stored', () => {
    setOsDark(true);
    expect(initialTheme()).toBe('dark');
    setOsDark(false);
    expect(initialTheme()).toBe('light');
  });

  it('treats a missing matchMedia (jsdom) as light', () => {
    expect(initialTheme()).toBe('light');
  });

  it('persists only explicit choices', () => {
    expect(localStorage.getItem(THEME_KEY)).toBeNull();
    persistTheme('dark');
    expect(localStorage.getItem(THEME_KEY)).toBe('dark');
  });
});
