// Shared display-locale holder: the i18n provider writes here and pure
// formatters (fmtTime) read from here, avoiding an i18n <-> ui cycle.
let locale = 'en-US';

export function setTimeLang(lang: string) {
  locale = lang === 'vi' ? 'vi-VN' : 'en-US';
}

export function timeLocale(): string {
  return locale;
}
