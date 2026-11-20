import { beforeEach, describe, expect, it } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { I18nProvider, STRINGS, useI18n } from '../../src/i18n';
import type { ReactNode } from 'react';

const wrapper = ({ children }: { children: ReactNode }) => (
  <I18nProvider>{children}</I18nProvider>
);

describe('i18n', () => {
  beforeEach(() => localStorage.clear());

  it('defaults to English strings', () => {
    const { result } = renderHook(() => useI18n(), { wrapper });
    expect(result.current.t('nav.users')).toBe('Users');
  });

  it('switches to Vietnamese and persists the choice', () => {
    const { result } = renderHook(() => useI18n(), { wrapper });
    act(() => result.current.setLang('vi'));
    expect(result.current.t('nav.users')).toBe('Tài khoản');
    expect(localStorage.getItem('admin.lang')).toBe('vi');
  });

  it('has the feed strings in English and Vietnamese', () => {
    const { result } = renderHook(() => useI18n(), { wrapper });
    expect(result.current.t('nav.feed')).toBe('Feed');
    expect(result.current.t('feed.action.hide')).toBe('Hide');
    act(() => result.current.setLang('vi'));
    expect(result.current.t('nav.feed')).toBe('Bản tin');
    expect(result.current.t('feed.action.hide')).toBe('Ẩn');
  });

  it('interpolates variables', () => {
    const { result } = renderHook(() => useI18n(), { wrapper });
    expect(result.current.t('common.selected', { n: 3 })).toBe('3 selected');
  });

  it('has the rule toggle and match-hit strings in both locales', () => {
    const { result } = renderHook(() => useI18n(), { wrapper });
    expect(result.current.t('common.off')).toBe('off');
    expect(result.current.t('common.on')).toBe('on');
    expect(result.current.t('rules.test.hit', { offset: 14, length: 8 })).toBe(
      'offset 14 · len 8',
    );
    act(() => result.current.setLang('vi'));
    expect(result.current.t('common.off')).toBe('Tắt');
    expect(result.current.t('common.on')).toBe('Bật');
    expect(result.current.t('rules.test.hit', { offset: 14, length: 8 })).toBe(
      'vị trí 14 · độ dài 8',
    );
  });

  it('falls back to the key for missing strings', () => {
    const { result } = renderHook(() => useI18n(), { wrapper });
    expect(result.current.t('nonexistent.key' as never)).toBe('nonexistent.key');
  });

  it('keeps the en and vi key sets identical', () => {
    expect(Object.keys(STRINGS.vi).sort()).toEqual(Object.keys(STRINGS.en).sort());
  });
});
