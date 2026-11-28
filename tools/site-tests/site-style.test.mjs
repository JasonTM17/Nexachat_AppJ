import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const SOCIAL_CSS = readFileSync(new URL('../../site/social/social.css', import.meta.url), 'utf8');
const SITE_CSS = readFileSync(new URL('../../site/style.css', import.meta.url), 'utf8');

test('social.css enforces responsive modal/filter wrapping, search cancel suppression, and outline input border', () => {
  assert.match(SOCIAL_CSS, /\.dialog-actions\s*\{[^}]*flex-wrap:\s*wrap;/);
  assert.match(SOCIAL_CSS, /\.header-session\s*\{[^}]*flex-wrap:\s*wrap;/);
  assert.match(SOCIAL_CSS, /\.active-filter-chip\s*\{[^}]*max-width:\s*100%;/);
  assert.match(SOCIAL_CSS, /\.active-filter-label\s*\{[^}]*text-overflow:\s*ellipsis;/);
  assert.match(SOCIAL_CSS, /\.search-input::-webkit-search-cancel-button\s*\{/);
  assert.match(SOCIAL_CSS, /\.text-input\s*\{[^}]*border:\s*1\.5px solid var\(--outline\);/);
  assert.doesNotMatch(SOCIAL_CSS, /\.text-input\s*\{[^}]*border:\s*2px solid transparent;/);
  assert.match(SOCIAL_CSS, /\.feed-list\s*\{[^}]*grid-template-columns:\s*minmax\(0,\s*1fr\);/);
  assert.match(SOCIAL_CSS, /\.comment-list\s*\{[^}]*grid-template-columns:\s*minmax\(0,\s*1fr\);/);
  assert.match(SOCIAL_CSS, /\.context-panel\s*\{[^}]*(?=[^}]*grid-template-columns:\s*minmax\(0,\s*1fr\);)(?=[^}]*min-width:\s*0;)[^}]*\}/);
  assert.doesNotMatch(SOCIAL_CSS, /\.context-panel\s*\{[^}]*grid-template-columns:\s*1fr;/);
  assert.match(SOCIAL_CSS, /\.post-card\s*\{[^}]*min-width:\s*0;/);
  assert.match(SOCIAL_CSS, /\.post-header\s*\{[^}]*min-width:\s*0;/);
  assert.match(SOCIAL_CSS, /\.comment-card\s*\{[^}]*min-width:\s*0;/);
  assert.match(SOCIAL_CSS, /\.author-trigger\s*\{[^}]*max-width:\s*100%;/);
  assert.match(SOCIAL_CSS, /\.author-name\s*\{[^}]*overflow-wrap:\s*anywhere;/);
  assert.match(SOCIAL_CSS, /\.composer-author-name\s*\{[^}]*overflow-wrap:\s*anywhere;/);
  assert.match(SOCIAL_CSS, /\.composer-identity\s*\{[^}]*max-width:\s*100%;/);
  assert.match(SOCIAL_CSS, /\.composer-identity\s*>\s*div\s*\{[^}]*min-width:\s*0;/);
  assert.match(SOCIAL_CSS, /\.reaction-pills-group\s*\{[^}]*(?=[^}]*flex-wrap:\s*wrap;)(?=[^}]*max-width:\s*100%;)[^}]*\}/);
  assert.match(SOCIAL_CSS, /\.comments-error-text\s*\{[^}]*(?=[^}]*min-width:\s*0;)(?=[^}]*overflow-wrap:\s*anywhere;)[^}]*\}/);
  assert.match(SOCIAL_CSS, /\.author-profile-head\s*\{[^}]*min-width:\s*0;/);
});

test('site/style.css defines Tonal Plum dark/light tokens, pill buttons, 16px card radius, Inter font, and accessible link rules', () => {
  // Dark tokens
  assert.match(SITE_CSS, /--bg-color:\s*#151316;/);
  assert.match(SITE_CSS, /--card-bg:\s*#1d1b1e;/);
  assert.match(SITE_CSS, /--text-main:\s*#e8e0e5;/);
  assert.match(SITE_CSS, /--text-muted:\s*#d2c2cb;/);
  assert.match(SITE_CSS, /--primary:\s*#f4b2e2;/);
  assert.match(SITE_CSS, /--btn-bg:\s*#733f68;/);
  assert.match(SITE_CSS, /--input-border:\s*#9b8d95;/);
  assert.match(SITE_CSS, /--error:\s*#ffb4ab;/);
  assert.match(SITE_CSS, /--radius:\s*16px;/);

  // Light tokens
  assert.match(SITE_CSS, /--bg-color:\s*#fff7fc;/);
  assert.match(SITE_CSS, /--card-bg:\s*#ffffff;/);
  assert.match(SITE_CSS, /--text-main:\s*#1d1b1e;/);
  assert.match(SITE_CSS, /--text-muted:\s*#4f434b;/);
  assert.match(SITE_CSS, /--primary:\s*#733f68;/);
  assert.match(SITE_CSS, /--input-border:\s*#81737b;/);
  assert.match(SITE_CSS, /--error:\s*#ba1a1a;/);

  // Shapes, typography, and zero-specificity link baseline rules
  assert.match(SITE_CSS, /body\s*\{[^}]*font-family:\s*Inter,/);
  assert.match(SITE_CSS, /\.btn\s*\{[^}]*border-radius:\s*999px;/);
  assert.match(SITE_CSS, /\.skip-link\s*\{[^}]*border-radius:\s*999px;/);
  assert.match(SITE_CSS, /\.form-input\s*\{[^}]*border-radius:\s*12px;/);
  assert.doesNotMatch(SITE_CSS, /^a(?::hover|:focus-visible)?\s*\{/m);
  assert.match(SITE_CSS, /^:where\(a\)\s*\{[^}]*color:\s*var\(--primary\);/m);
  assert.match(SITE_CSS, /^:where\(a:hover\)\s*\{[^}]*color:\s*var\(--primary-hover\);/m);
  assert.match(SITE_CSS, /^:where\(a:focus-visible\)\s*\{[^}]*border-radius:\s*4px;/m);
  assert.match(SITE_CSS, /\.install-hint a\s*\{[^}]*color:\s*var\(--primary\);/);

  // Explicit hover/base colors on anchor-bearing controls (prevent link hover/focus bleed)
  assert.match(SITE_CSS, /^\.btn:hover\s*\{(?=[^}]*background-color:\s*var\(--btn-bg-hover\);)(?=[^}]*color:\s*var\(--logo-on-brand\);)[^}]*\}/m);
  assert.match(SITE_CSS, /^\.btn-secondary:hover\s*\{(?=[^}]*background-color:\s*var\(--secondary-hover\);)(?=[^}]*color:\s*var\(--text-main\);)[^}]*\}/m);
  assert.match(SITE_CSS, /^\.btn-light:hover\s*\{(?=[^}]*background-color:\s*#eedbea;)(?=[^}]*color:\s*var\(--btn-bg\);)[^}]*\}/m);
  assert.match(SITE_CSS, /^\.btn-danger\s*\{[^}]*color:\s*var\(--logo-on-brand\);/m);
  assert.match(SITE_CSS, /^\.btn-danger:hover\s*\{[^}]*color:\s*var\(--logo-on-brand\);/m);
  assert.match(SITE_CSS, /^\.skip-link:hover\s*\{[^}]*color:\s*var\(--text-main\);/m);
  assert.match(SITE_CSS, /^\.home-brand:hover\s*\{[^}]*color:\s*var\(--logo-ink\);/m);

  // .prose link rules must exclude .btn so <a class="btn"> in download.html keeps button styling
  assert.doesNotMatch(SITE_CSS, /^\.prose a(?::hover|:focus-visible)?\s*\{/m);
  assert.match(SITE_CSS, /^\.prose a:not\(\.btn\)\s*\{[^}]*color:\s*var\(--primary\);/m);
  assert.match(SITE_CSS, /^\.prose a:not\(\.btn\):hover\s*\{[^}]*color:\s*var\(--primary-hover\);/m);
  assert.match(SITE_CSS, /^\.prose a:not\(\.btn\):focus-visible\s*\{/m);
});
