"""Playwright Chromium launcher, error/CSP telemetry, and WCAG contrast/layout audits."""

from __future__ import annotations

import os
import re
from contextlib import contextmanager
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Tuple

from .client import check


_RGB_RE = re.compile(
    r"^rgba?\(\s*([0-9.]+)\s*[,\s]\s*([0-9.]+)\s*[,\s]\s*([0-9.]+)"
    r"(?:\s*[,/]\s*([0-9.]+%?))?\s*\)$",
    re.IGNORECASE,
)


def parse_css_color(value: str) -> Optional[Tuple[float, float, float, float]]:
    """Parses CSS rgb()/rgba()/#hex strings into normalized (r, g, b, a) 0..255 + 0..1."""
    s = (value or "").strip()
    if not s or s == "transparent":
        return (0.0, 0.0, 0.0, 0.0)
    if s.startswith("#"):
        hex_part = s[1:]
        if len(hex_part) == 3:
            r, g, b = (int(c * 2, 16) for c in hex_part)
            return (float(r), float(g), float(b), 1.0)
        if len(hex_part) == 6:
            r = int(hex_part[0:2], 16)
            g = int(hex_part[2:4], 16)
            b = int(hex_part[4:6], 16)
            return (float(r), float(g), float(b), 1.0)
        if len(hex_part) == 8:
            r = int(hex_part[0:2], 16)
            g = int(hex_part[2:4], 16)
            b = int(hex_part[4:6], 16)
            a = int(hex_part[6:8], 16) / 255.0
            return (float(r), float(g), float(b), a)
        return None
    m = _RGB_RE.match(s)
    if m:
        r = float(m.group(1))
        g = float(m.group(2))
        b = float(m.group(3))
        a_raw = m.group(4)
        if a_raw is None:
            a = 1.0
        elif a_raw.endswith("%"):
            a = float(a_raw[:-1]) / 100.0
        else:
            a = float(a_raw)
        return (r, g, b, max(0.0, min(1.0, a)))
    return None


def alpha_blend(
    fg: Tuple[float, float, float, float],
    bg: Tuple[float, float, float],
) -> Tuple[float, float, float]:
    a = fg[3]
    return (
        fg[0] * a + bg[0] * (1.0 - a),
        fg[1] * a + bg[1] * (1.0 - a),
        fg[2] * a + bg[2] * (1.0 - a),
    )


def _linearize_channel(c255: float) -> float:
    c = max(0.0, min(255.0, c255)) / 255.0
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


def relative_luminance(rgb: Tuple[float, float, float]) -> float:
    r = _linearize_channel(rgb[0])
    g = _linearize_channel(rgb[1])
    b = _linearize_channel(rgb[2])
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def contrast_ratio(
    fg_rgb: Tuple[float, float, float],
    bg_rgb: Tuple[float, float, float],
) -> float:
    l1 = relative_luminance(fg_rgb)
    l2 = relative_luminance(bg_rgb)
    lighter = max(l1, l2)
    darker = min(l1, l2)
    return (lighter + 0.05) / (darker + 0.05)


def normalize_hex_color(value: str) -> str:
    parsed = parse_css_color(value)
    if parsed is None:
        return value.strip().lower()
    r, g, b, _ = parsed
    return f"#{int(round(r)):02x}{int(round(g)):02x}{int(round(b)):02x}"


def resolve_chromium_executable() -> Optional[str]:
    """Finds Chromium headless shell / executable without requiring downloads."""
    env_path = os.environ.get("PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH")
    if env_path and os.path.isfile(env_path):
        return env_path

    local_app_data = os.environ.get("LOCALAPPDATA") or str(Path.home() / "AppData" / "Local")
    ms_pw = Path(local_app_data) / "ms-playwright"
    if ms_pw.is_dir():
        shells = sorted(
            ms_pw.glob("chromium_headless_shell-*/chrome-headless-shell-win64/chrome-headless-shell.exe"),
            reverse=True,
        )
        if shells:
            return str(shells[0])
        full = sorted(
            ms_pw.glob("chromium-*/chrome-win64/chrome.exe"),
            reverse=True,
        )
        if full:
            return str(full[0])
    return None


@dataclass
class AllowRule:
    statuses: Tuple[int, ...] = ()
    url_substrings: Tuple[str, ...] = ()


class PageAuditRecorder:
    """Collects console errors, uncaught exceptions, CSP violations, and failed requests."""

    def __init__(self, page: Any, own_origin: str) -> None:
        self.page = page
        self.own_origin = own_origin.rstrip("/")
        self.console_errors: List[str] = []
        self.page_errors: List[str] = []
        self.request_failures: List[str] = []
        self.http_errors: List[str] = []
        self.external_requests: List[str] = []
        self._allow_stack: List[AllowRule] = []
        self._attached = False

    def attach(self) -> None:
        if self._attached:
            return
        self._attached = True
        self.page.add_init_script(
            """
            window.__e2eCspViolations = [];
            document.addEventListener('securitypolicyviolation', (e) => {
              window.__e2eCspViolations.push({
                violatedDirective: e.violatedDirective,
                blockedURI: e.blockedURI,
                effectiveDirective: e.effectiveDirective
              });
            });
            """
        )

        def on_console(msg: Any) -> None:
            if msg.type != "error":
                return
            text = msg.text or ""
            # Browser automatically emits "Failed to load resource: the server responded with a status of 401/403"
            # when an intentional negative test hits an allowlisted HTTP status.
            if self._is_console_status_allowed(text):
                return
            self.console_errors.append(text)

        def on_pageerror(err: Any) -> None:
            self.page_errors.append(str(err))

        def on_request(req: Any) -> None:
            url = req.url or ""
            if url.startswith("http://") or url.startswith("https://"):
                if not url.startswith(self.own_origin):
                    self.external_requests.append(url)

        def on_requestfailed(req: Any) -> None:
            url = req.url or ""
            failure = req.failure or ""
            # Video/media abort on dialog close or navigation is harmless if allowlisted
            if self._is_url_allowed(url):
                return
            self.request_failures.append(f"{req.method} {url}: {failure}")

        def on_response(resp: Any) -> None:
            status = resp.status
            if status < 400:
                return
            url = resp.url or ""
            if self._is_http_allowed(status, url):
                return
            self.http_errors.append(f"{status} {resp.request.method} {url}")

        self.page.on("console", on_console)
        self.page.on("pageerror", on_pageerror)
        self.page.on("request", on_request)
        self.page.on("requestfailed", on_requestfailed)
        self.page.on("response", on_response)

    def _is_url_allowed(self, url: str) -> bool:
        for rule in self._allow_stack:
            if not rule.url_substrings or any(sub in url for sub in rule.url_substrings):
                if not rule.statuses:
                    return True
        return False

    def _is_http_allowed(self, status: int, url: str) -> bool:
        for rule in self._allow_stack:
            status_ok = not rule.statuses or status in rule.statuses
            url_ok = not rule.url_substrings or any(sub in url for sub in rule.url_substrings)
            if status_ok and url_ok:
                return True
        return False

    def _is_console_status_allowed(self, text: str) -> bool:
        if "Failed to load resource" not in text:
            return False
        for rule in self._allow_stack:
            for st in rule.statuses:
                if f"status of {st}" in text:
                    return True
        return False

    @contextmanager
    def allow(self, statuses: Iterable[int] = (), url_substrings: Iterable[str] = ()):
        rule = AllowRule(tuple(statuses), tuple(url_substrings))
        self._allow_stack.append(rule)
        try:
            yield
        finally:
            self._allow_stack.pop()

    def csp_violations(self) -> List[dict]:
        try:
            return list(self.page.evaluate("() => window.__e2eCspViolations || []"))
        except Exception:
            return []

    def assert_clean(self, label: str) -> None:
        csp = self.csp_violations()
        check(not self.page_errors, f"{label}: uncaught page errors: {self.page_errors}")
        check(not csp, f"{label}: CSP violations: {csp}")
        check(not self.console_errors, f"{label}: console errors: {self.console_errors}")
        check(not self.request_failures, f"{label}: failed requests: {self.request_failures}")
        check(not self.http_errors, f"{label}: unexpected HTTP >=400 responses: {self.http_errors}")
        check(not self.external_requests, f"{label}: unexpected external URLs: {self.external_requests}")

    def reset(self) -> None:
        self.console_errors.clear()
        self.page_errors.clear()
        self.request_failures.clear()
        self.http_errors.clear()
        self.external_requests.clear()
        try:
            self.page.evaluate("() => { window.__e2eCspViolations = []; }")
        except Exception:
            pass


class BrowserDriver:
    """Manages Playwright Chromium lifecycle across Tier 4 browser tests."""

    def __init__(self, own_origin: str) -> None:
        self.own_origin = own_origin.rstrip("/")
        self._pw = None
        self._browser = None

    def __enter__(self) -> "BrowserDriver":
        from playwright.sync_api import sync_playwright

        self._pw = sync_playwright().start()
        exe = resolve_chromium_executable()
        if exe:
            self._browser = self._pw.chromium.launch(headless=True, executable_path=exe)
        else:
            try:
                self._browser = self._pw.chromium.launch(headless=True)
            except Exception:
                self._browser = self._pw.chromium.launch(headless=True, channel="msedge")
        return self

    def __exit__(self, exc_type, exc, tb) -> None:
        if self._browser is not None:
            try:
                self._browser.close()
            except Exception:
                pass
            self._browser = None
        if self._pw is not None:
            try:
                self._pw.stop()
            except Exception:
                pass
            self._pw = None

    @contextmanager
    def new_page(
        self,
        *,
        width: int = 1440,
        height: int = 900,
        color_scheme: str = "light",
        locale: str = "vi-VN",
    ):
        assert self._browser is not None
        context = self._browser.new_context(
            viewport={"width": width, "height": height},
            color_scheme=color_scheme,
            locale=locale,
        )
        page = context.new_page()
        recorder = PageAuditRecorder(page, self.own_origin)
        recorder.attach()
        try:
            yield page, recorder
        finally:
            context.close()


_LAYOUT_AND_CONTRAST_JS = """
(opts) => {
  const docEl = document.documentElement;
  const overflow = docEl.scrollWidth > window.innerWidth;

  const isVisible = (el) => {
    if (!el) return false;
    const st = getComputedStyle(el);
    if (st.display === 'none' || st.visibility === 'hidden' || parseFloat(st.opacity) === 0) return false;
    const r = el.getBoundingClientRect();
    return r.width > 0 && r.height > 0;
  };

  const touchFailures = [];
  if (opts.checkTouchTargets) {
    const minH = opts.minTouchPx || 47.5;
    const targetSel = opts.touchTargetSelector || 'main button, main a, main input, main select, main textarea, header button, header a, dialog button, dialog input, dialog select, dialog textarea';
    const controls = document.querySelectorAll(targetSel);
    for (const el of controls) {
      if (!isVisible(el)) continue;
      if (el.classList.contains('skip-link')) continue;
      const r = el.getBoundingClientRect();
      if (r.height < minH) {
        touchFailures.push({
          tag: el.tagName,
          id: el.id || '',
          cls: el.className || '',
          height: Math.round(r.height * 10) / 10
        });
      }
    }
  }

  const effectiveBg = (el) => {
    let cur = el;
    const layers = [];
    while (cur && cur.nodeType === 1) {
      const bg = getComputedStyle(cur).backgroundColor;
      if (bg && bg !== 'transparent' && bg !== 'rgba(0, 0, 0, 0)') {
        layers.push(bg);
      }
      cur = cur.parentElement;
    }
    const rootBg = getComputedStyle(document.documentElement).backgroundColor;
    if (rootBg && rootBg !== 'transparent') layers.push(rootBg);
    return layers;
  };

  const sampleSelectors = opts.contrastSelectors || [];
  const contrastSamples = [];
  for (const sel of sampleSelectors) {
    const el = document.querySelector(sel);
    if (!isVisible(el)) continue;
    const st = getComputedStyle(el);
    contrastSamples.push({
      selector: sel,
      color: st.color,
      fontSize: parseFloat(st.fontSize || '16'),
      fontWeight: parseInt(st.fontWeight || '400', 10) || 400,
      bgImage: st.backgroundImage || '',
      bgLayers: effectiveBg(el)
    });
  }

  const rootStyle = getComputedStyle(document.documentElement);
  const tokens = {};
  for (const name of (opts.tokenNames || [])) {
    tokens[name] = rootStyle.getPropertyValue(name).trim();
  }

  return {
    scrollWidth: docEl.scrollWidth,
    innerWidth: window.innerWidth,
    overflow,
    touchFailures,
    contrastSamples,
    tokens
  };
}
"""


_GRADIENT_RGB_RE = re.compile(r"rgba?\(\s*[0-9.]+\s*[,\s]\s*[0-9.]+\s*[,\s]\s*[0-9.]+(?:\s*[,/]\s*[0-9.]+%?)?\s*\)", re.IGNORECASE)


def extract_gradient_bg_layers(bg_image: str) -> List[str]:
    """Extracts color stops from CSS linear-gradient(...) strings."""
    if not bg_image or "gradient" not in bg_image.lower():
        return []
    return _GRADIENT_RGB_RE.findall(bg_image)


def compute_effective_bg(bg_layers: List[str], fallback_rgb: Tuple[float, float, float]) -> Tuple[float, float, float]:
    """Composites CSS background layers bottom-up onto fallback_rgb."""
    current = fallback_rgb
    for layer in reversed(bg_layers):
        parsed = parse_css_color(layer)
        if parsed is None:
            continue
        current = alpha_blend(parsed, current)
    return current


def audit_page_design(
    page: Any,
    *,
    color_scheme: str = "light",
    check_touch_targets: bool = True,
    touch_target_selector: Optional[str] = None,
    min_touch_px: float = 47.5,
    contrast_selectors: Optional[List[str]] = None,
    token_names: Optional[List[str]] = None,
) -> dict:
    """Runs layout overflow, touch target, contrast, and token checks on current DOM."""
    selectors = contrast_selectors or [
        "body",
        "h1",
        ".lead",
        ".muted",
        ".button-primary",
        ".button-secondary",
        ".eyebrow",
    ]
    data = page.evaluate(
        _LAYOUT_AND_CONTRAST_JS,
        {
            "checkTouchTargets": check_touch_targets,
            "touchTargetSelector": touch_target_selector,
            "minTouchPx": min_touch_px,
            "contrastSelectors": selectors,
            "tokenNames": token_names or [],
        },
    )
    check(
        not data["overflow"],
        f"Horizontal overflow detected: scrollWidth={data['scrollWidth']} > innerWidth={data['innerWidth']}",
    )
    check(
        not data["touchFailures"],
        f"Touch targets below {min_touch_px}px: {data['touchFailures'][:5]}",
    )

    fallback = (21.0, 19.0, 22.0) if color_scheme == "dark" else (255.0, 247.0, 252.0)
    contrast_failures = []
    for sample in data["contrastSamples"]:
        fg_parsed = parse_css_color(sample["color"])
        if fg_parsed is None:
            continue
        grad_stops = extract_gradient_bg_layers(sample.get("bgImage", ""))
        bg_candidates = (
            [compute_effective_bg([stop, *sample["bgLayers"]], fallback) for stop in grad_stops]
            if grad_stops
            else [compute_effective_bg(sample["bgLayers"], fallback)]
        )
        is_large = sample["fontSize"] >= 24.0 or (sample["fontSize"] >= 18.66 and sample["fontWeight"] >= 700)
        threshold = 3.0 if is_large else 4.5
        worst_ratio = min(
            contrast_ratio(alpha_blend(fg_parsed, bg_rgb), bg_rgb)
            for bg_rgb in bg_candidates
        )
        if worst_ratio < threshold:
            contrast_failures.append(
                {
                    "selector": sample["selector"],
                    "ratio": round(worst_ratio, 2),
                    "threshold": threshold,
                    "fg": sample["color"],
                    "bgImage": sample.get("bgImage", ""),
                    "bgLayers": sample["bgLayers"],
                }
            )
    check(not contrast_failures, f"WCAG contrast failures ({color_scheme}): {contrast_failures}")
    return data
