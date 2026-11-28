"""Offline unit tests for tools/e2e/social_suite/."""

from __future__ import annotations

import os
import sys
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[3]
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))

from tools.e2e.social_suite.browser_driver import (  # noqa: E402
    alpha_blend,
    compute_effective_bg,
    contrast_ratio,
    extract_gradient_bg_layers,
    normalize_hex_color,
    parse_css_color,
)
from tools.e2e.social_suite.client import (  # noqa: E402
    CheckOutcome,
    SocialApiClient,
    SuiteContext,
    build_multipart_body,
    reaction_path,
    retry_after_seconds,
    Response,
)
from tools.e2e.social_suite.fixtures import (  # noqa: E402
    FIXTURE_ACCOUNTS,
    MAX_VIDEO_BYTES,
    SessionRecord,
    SessionStore,
    load_audio_only_m4a_bytes,
    load_valid_mp4_bytes,
    make_jpeg_bytes,
    make_oversized_mp4_bytes,
    make_png_bytes,
    make_webp_bytes,
)
from tools.e2e.social_suite.proxy_server import (  # noqa: E402
    CSP_ADMIN,
    CSP_SOCIAL,
    VercelProxyServer,
    headers_for_path,
    resolve_static_subpath,
    rewrite_api_path,
)
from tools.e2e.social_suite.run_social_e2e import (  # noqa: E402
    build_parser,
    evaluate_exit_code,
    parse_tiers,
    select_checks,
)
from tools.e2e.social_suite.stack_harness import is_executable_spring_jar, is_loopback_url  # noqa: E402
from tools.e2e.social_suite.tier1_feature_api import get_tier1_checks  # noqa: E402
from tools.e2e.social_suite.tier2_boundary_edge import get_tier2_checks  # noqa: E402
from tools.e2e.social_suite.tier3_cross_feature import get_tier3_checks  # noqa: E402
from tools.e2e.social_suite.tier4_browser_e2e import get_tier4_checks  # noqa: E402


class TestProxyRewritesAndHeaders(unittest.TestCase):
    def test_session_and_report_rewrites(self) -> None:
        self.assertEqual(rewrite_api_path("/api/social/v1/session/login"), "/api/v1/auth/login")
        self.assertEqual(rewrite_api_path("/api/social/v1/session/refresh"), "/api/v1/auth/refresh")
        self.assertEqual(rewrite_api_path("/api/social/v1/session/logout"), "/api/v1/auth/logout")
        self.assertEqual(rewrite_api_path("/api/social/v1/reports"), "/api/v1/reports")

    def test_social_subtrees_preserve_query_and_block_wildcards(self) -> None:
        self.assertEqual(
            rewrite_api_path("/api/social/v1/posts?limit=10&q=ca%20phe"),
            "/api/social/v1/posts?limit=10&q=ca%20phe",
        )
        self.assertEqual(
            rewrite_api_path("/api/social/v1/authors/1234"),
            "/api/social/v1/authors/1234",
        )
        self.assertEqual(rewrite_api_path("/api/v1/admin/feed/reports"), "/api/v1/admin/feed/reports")
        self.assertIsNone(rewrite_api_path("/api/social/v1/unknown/wildcard"))

    def test_csp_and_cache_headers_match_vercel_json(self) -> None:
        soc_headers = dict(headers_for_path("/social/"))
        self.assertEqual(soc_headers["Content-Security-Policy"], CSP_SOCIAL)
        self.assertEqual(soc_headers["X-Frame-Options"], "DENY")

        adm_headers = dict(headers_for_path("/admin/feed"))
        self.assertEqual(adm_headers["Content-Security-Policy"], CSP_ADMIN)
        self.assertIn("Permissions-Policy", adm_headers)

        join_headers = dict(headers_for_path("/join/abc"))
        self.assertEqual(join_headers["Cache-Control"], "no-store")

        api_headers = dict(headers_for_path("/api/social/v1/posts"))
        self.assertEqual(api_headers["Cache-Control"], "private, no-store")
        self.assertEqual(api_headers["CDN-Cache-Control"], "no-store")

    def test_static_spa_rewrites(self) -> None:
        self.assertEqual(resolve_static_subpath("/social"), "/social/index.html")
        self.assertEqual(resolve_static_subpath("/social/"), "/social/index.html")
        self.assertEqual(resolve_static_subpath("/join/abc123"), "/join/index.html")

    def test_proxy_server_serves_site_and_rejects_traversal(self) -> None:
        import urllib.request
        import urllib.error

        with VercelProxyServer(REPO_ROOT / "site", "http://127.0.0.1:18999") as proxy:
            with urllib.request.urlopen(f"{proxy.base_url}/social/") as resp:
                self.assertEqual(resp.status, 200)
                self.assertEqual(resp.headers.get("Content-Security-Policy"), CSP_SOCIAL)
                body = resp.read().decode("utf-8")
                self.assertIn("NexaChat Social", body)
            with urllib.request.urlopen(f"{proxy.base_url}/join/join.js") as js_resp:
                self.assertEqual(js_resp.status, 200)
                self.assertIn("javascript", js_resp.headers.get("Content-Type", "").lower())
            with urllib.request.urlopen(f"{proxy.base_url}/join/AbCdEfGhIjKlMnOpQrStUvWxYz0123456789_012345") as join_resp:
                self.assertEqual(join_resp.status, 200)
                self.assertIn("invite-valid", join_resp.read().decode("utf-8"))
            with self.assertRaises(urllib.error.HTTPError) as ctx:
                urllib.request.urlopen(f"{proxy.base_url}/social/../../server/pom.xml")
            status = ctx.exception.code
            ctx.exception.close()
            self.assertIn(status, (403, 404))


class TestContrastAndColorMath(unittest.TestCase):
    def test_parse_and_normalize_colors(self) -> None:
        self.assertEqual(parse_css_color("#733f68"), (115.0, 63.0, 104.0, 1.0))
        self.assertEqual(parse_css_color("rgba(255, 247, 252, 1)"), (255.0, 247.0, 252.0, 1.0))
        self.assertEqual(normalize_hex_color("rgb(115, 63, 104)"), "#733f68")

    def test_tonal_plum_contrast_meets_wcag_aa(self) -> None:
        # Primary button #733f68 with #ffffff text -> >= 4.5:1
        ratio_primary = contrast_ratio((255.0, 255.0, 255.0), (115.0, 63.0, 104.0))
        self.assertGreaterEqual(ratio_primary, 7.0)
        # Light canvas #fff7fc with ink #1d1b1e -> >= 15:1
        ratio_light = contrast_ratio((29.0, 27.0, 30.0), (255.0, 247.0, 252.0))
        self.assertGreaterEqual(ratio_light, 15.0)
        # Dark surface #151316 with ink #e8e0e5 -> >= 13:1
        ratio_dark = contrast_ratio((232.0, 224.0, 229.0), (21.0, 19.0, 22.0))
        self.assertGreaterEqual(ratio_dark, 13.0)

    def test_alpha_blending_layers(self) -> None:
        blended = alpha_blend((0.0, 0.0, 0.0, 0.5), (200.0, 200.0, 200.0))
        self.assertEqual(blended, (100.0, 100.0, 100.0))
        eff = compute_effective_bg(["rgba(0, 0, 0, 0.5)"], (200.0, 200.0, 200.0))
        self.assertEqual(eff, (100.0, 100.0, 100.0))

    def test_gradient_stop_extraction(self) -> None:
        stops = extract_gradient_bg_layers("linear-gradient(180deg, rgb(124, 69, 112) 0%, rgb(106, 56, 95) 100%)")
        self.assertEqual(stops, ["rgb(124, 69, 112)", "rgb(106, 56, 95)"])
        self.assertEqual(extract_gradient_bg_layers("none"), [])


class TestFixturesAndMultipart(unittest.TestCase):
    def test_six_account_pool_and_in_memory_store(self) -> None:
        self.assertEqual(len(FIXTURE_ACCOUNTS), 6)
        store = SessionStore()
        store.set(SessionRecord("author", "+84900000951", "Author", "id1", "tok1", "ref1"))
        self.assertEqual(store.token("author"), "tok1")
        with self.assertRaises(KeyError):
            store.token("missing")

    def test_image_and_video_fixtures(self) -> None:
        png = make_png_bytes()
        self.assertTrue(png.startswith(b"\x89PNG\r\n\x1a\n"))
        jpg = make_jpeg_bytes()
        self.assertTrue(jpg.startswith(b"\xff\xd8\xff"))
        webp = make_webp_bytes()
        self.assertEqual(webp[:4], b"RIFF")
        self.assertEqual(webp[8:12], b"WEBP")

        mp4 = load_valid_mp4_bytes()
        self.assertGreater(len(mp4), 100)
        self.assertIn(b"ftyp", mp4[:32])
        m4a = load_audio_only_m4a_bytes(trailing_moov=False)
        self.assertGreater(len(m4a), 100)
        oversized = make_oversized_mp4_bytes()
        self.assertGreater(len(oversized), MAX_VIDEO_BYTES)

    def test_multipart_supports_zero_one_two_files(self) -> None:
        zero_body, ctype0 = build_multipart_body(files=[], fields={"width": "8"}, boundary="b0")
        self.assertIn("boundary=b0", ctype0)
        self.assertEqual(zero_body.count(b'name="file"'), 0)

        two_body, _ = build_multipart_body(
            files=[("file", "a.png", "image/png", b"123"), ("file", "b.png", "image/png", b"456")],
            boundary="b2",
        )
        self.assertEqual(two_body.count(b'name="file"'), 2)

    def test_reaction_path_and_retry_after(self) -> None:
        p = reaction_path("/api/social/v1", "p1", "\u2764\ufe0f")
        self.assertEqual(p, "/api/social/v1/posts/p1/reactions/%E2%9D%A4%EF%B8%8F")
        resp = Response(429, {"Retry-After": "12"}, b'{"error":{"code":"RATE_LIMITED","details":{"retryAfterSeconds":17}}}')
        self.assertEqual(retry_after_seconds(resp), 17)


class TestRunnerSelectionAndModes(unittest.TestCase):
    def test_tier_coverage_thresholds(self) -> None:
        t1 = get_tier1_checks()
        t2 = get_tier2_checks()
        t3 = get_tier3_checks()
        t4 = get_tier4_checks()
        self.assertGreaterEqual(len(t1), 80)
        self.assertGreaterEqual(len(t2), 75)
        self.assertGreaterEqual(len(t3), 16)
        self.assertGreaterEqual(len(t1) + len(t2) + len(t3) + len(t4), 184)

        # Ensure unique check IDs across all tiers
        all_ids = [c.check_id for c in (*t1, *t2, *t3, *t4)]
        self.assertEqual(len(all_ids), len(set(all_ids)))

    def test_mode_exit_code_contract(self) -> None:
        pass_only = [CheckOutcome("1", "F1", 1, "BASELINE", "t", "PASS")]
        pending = [
            CheckOutcome("1", "F1", 1, "BASELINE", "t", "PASS"),
            CheckOutcome("2", "F3", 1, "M1", "t", "PENDING_MILESTONE"),
        ]
        blocked = [
            CheckOutcome("1", "F1", 1, "BASELINE", "t", "PASS"),
            CheckOutcome("3", "F16", 4, "M4", "t", "BLOCKED_CAPABILITY"),
        ]
        failed = [CheckOutcome("1", "F1", 1, "BASELINE", "t", "FAIL")]

        self.assertEqual(evaluate_exit_code(pass_only, "auto"), 0)
        self.assertEqual(evaluate_exit_code(pending, "auto"), 0)
        self.assertEqual(evaluate_exit_code(pending, "strict"), 1)
        self.assertEqual(evaluate_exit_code(blocked, "strict"), 0)
        self.assertEqual(evaluate_exit_code(failed, "auto"), 1)
        self.assertEqual(evaluate_exit_code(failed, "baseline"), 1)

    def test_parse_tiers_and_select_baseline(self) -> None:
        self.assertEqual(parse_tiers("all"), [1, 2, 3, 4])
        self.assertEqual(parse_tiers("1,3"), [1, 3])
        with self.assertRaises(ValueError):
            parse_tiers("5")
        checks = get_tier1_checks()
        base = select_checks(checks, [1], "baseline")
        self.assertTrue(all(c.milestone_gate == "BASELINE" for c in base))
        self.assertTrue(is_loopback_url("http://127.0.0.1:8080"))
        self.assertFalse(is_loopback_url("https://api.nexachat.io.vn"))

    def test_executable_jar_manifest_check(self) -> None:
        import tempfile
        import zipfile

        with tempfile.TemporaryDirectory() as tmp:
            thin = Path(tmp) / "thin.jar"
            with zipfile.ZipFile(thin, "w") as zf:
                zf.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\n")
            self.assertFalse(is_executable_spring_jar(thin))

            fat = Path(tmp) / "fat.jar"
            with zipfile.ZipFile(fat, "w") as zf:
                zf.writestr(
                    "META-INF/MANIFEST.MF",
                    "Manifest-Version: 1.0\r\nMain-Class: org.springframework.boot.loader.launch.JarLauncher\r\n",
                )
            self.assertTrue(is_executable_spring_jar(fat))

    def test_screenshots_dir_option_and_capture(self) -> None:
        import tempfile

        args = build_parser().parse_args(["--screenshots-dir", "C:/tmp/shots"])
        self.assertEqual(args.screenshots_dir, "C:/tmp/shots")

        class FakePage:
            def screenshot(self, *, path: str, full_page: bool = True) -> None:
                Path(path).write_bytes(b"pngdata" if full_page else b"partial")

        with tempfile.TemporaryDirectory() as tmp:
            ctx = SuiteContext(
                SocialApiClient("http://127.0.0.1:8080"),
                SocialApiClient("http://127.0.0.1:8081"),
                SessionStore(),
                screenshots_dir=tmp,
            )
            saved = ctx.capture_screenshot(FakePage(), "sample.png")
            self.assertIsNotNone(saved)
            assert saved is not None
            self.assertTrue(saved.is_file())
            self.assertEqual(len(ctx.screenshots_saved), 1)


if __name__ == "__main__":
    unittest.main()
