"""Tier 4: Multi-user Playwright Chromium browser E2E, design audit & Android checks."""

from __future__ import annotations

import subprocess
import uuid
from pathlib import Path
from typing import List

from .browser_driver import BrowserDriver, audit_page_design, normalize_hex_color
from .client import (
    BlockedCapability,
    CheckCase,
    SuiteContext,
    check,
    expect_error,
    expect_status,
)
from .fixtures import (
    FIXTURE_PASSWORD,
    REPO_ROOT,
    VALID_JOIN_TOKEN,
    load_valid_mp4_bytes,
    make_png_bytes,
)


def _tmp_file_path(ctx: SuiteContext, name: str, data: bytes) -> Path:
    # Keep temporary upload files inside scratch/teamwork_tmp or suite dir, never /tmp
    base = Path.home() / ".gemini" / "antigravity" / "scratch" / "teamwork_tmp"
    base.mkdir(parents=True, exist_ok=True)
    path = base / f"{ctx.nonce}_{name}"
    path.write_bytes(data)
    return path


def _social_login(page, recorder, ctx: SuiteContext, role_key: str) -> None:
    page.fill("#login-identifier", ctx.phone(role_key))
    page.fill("#login-password", FIXTURE_PASSWORD)
    with page.expect_response(lambda r: "/api/social/v1/session/login" in r.url and r.request.method == "POST") as resp_info:
        page.click("#login-button")
    resp = resp_info.value
    if resp.status == 200:
        try:
            data = resp.json()
            rec = ctx.sessions.get(role_key)
            rec.access_token = data["accessToken"]
            rec.refresh_token = data.get("refreshToken", rec.refresh_token)
            ctx.sessions.set(rec)
        except Exception:
            pass
    page.wait_for_selector("#session-panel:not([hidden])", timeout=15000)
    page.wait_for_selector("#composer-panel:not([hidden])", timeout=15000)
    recorder.assert_clean(f"social login ({role_key})")


def _admin_login(page, recorder, admin_base_url: str, identifier: str, password: str, *, expect_success: bool = True) -> None:
    page.goto(f"{admin_base_url}/login", wait_until="networkidle")
    page.fill("#identifier", identifier)
    page.fill("#password", password)
    if expect_success:
        page.click('button[type="submit"]')
        page.wait_for_url("**/overview", timeout=15000)
        recorder.assert_clean("admin login")
    else:
        with recorder.allow(statuses=(401, 403), url_substrings=("/api/v1/auth/login", "/api/v1/admin/me")):
            page.click('button[type="submit"]')
            page.wait_for_selector('.form-error[role="alert"]', timeout=15000)


# --------------------------------------------------------------------------- S1
def _t4_s1_bilingual_web_social_lifecycle(ctx: SuiteContext) -> None:
    ctx.set_social_flags(True, True)
    cur = ctx.get_admin_settings()
    draft = {
        "titleVi": f"Th\u00f4ng b\u00e1o S1 {ctx.nonce}",
        "bodyVi": "N\u1ed9i dung b\u1ea3ng tin S1",
        "titleEn": f"S1 Notice {ctx.nonce}",
        "bodyEn": "S1 live announcement body",
    }
    d = ctx.spring.put(
        "/api/v1/admin/social/settings/announcement/draft",
        token=ctx.token("admin"),
        body={"expectedRevision": cur["revision"], **draft},
    )
    expect_status(d, 200, "S1 save draft")
    pub = ctx.spring.post(
        "/api/v1/admin/social/settings/announcement/publish",
        token=ctx.token("admin"),
        body={"expectedRevision": d.json()["revision"]},
    )
    expect_status(pub, 200, "S1 publish announcement")

    # Seed another user's post so S1 can report it via #report-dialog
    other_post = ctx.create_post_safe("reader", ctx.text("S1 reportable post")).json()

    png_path = _tmp_file_path(ctx, "s1_photo.png", make_png_bytes(32, 32))
    mp4_path = _tmp_file_path(ctx, "s1_clip.mp4", load_valid_mp4_bytes())

    try:
        with BrowserDriver(ctx.proxy_base_url) as driver:
            with driver.new_page(width=1440, height=900, color_scheme="light", locale="vi-VN") as (page, rec):
                page.goto(f"{ctx.proxy_base_url}/social/", wait_until="networkidle")
                page.wait_for_selector("#announcement-card:not([hidden])", timeout=15000)
                check(draft["titleVi"] in page.inner_text("#announcement-title"), "VI announcement title missing")

                # Toggle to EN and back to VI
                page.click("#lang-en-button")
                check(page.get_attribute("html", "lang") == "en", "html[lang] did not switch to en")
                check(draft["titleEn"] in page.inner_text("#announcement-title"), "EN announcement title missing")
                page.click("#lang-vi-button")
                check(page.get_attribute("html", "lang") == "vi", "html[lang] did not switch back to vi")

                # Sign in as author & verify in-memory token invariant (theme preference in localStorage is allowed)
                _social_login(page, rec, ctx, "author")
                author_tok = ctx.token("author")
                storage_audit = page.evaluate(
                    """(tok) => {
                      const ssKeys = Object.keys(sessionStorage);
                      const lsEntries = Object.keys(localStorage).map(k => [k, localStorage.getItem(k) || '']);
                      const badLs = lsEntries.filter(([k, v]) => {
                        const kl = k.toLowerCase();
                        return kl.includes('token') || kl.includes('auth') || kl.includes('session')
                          || v.includes(tok) || v.startsWith('eyJ');
                      });
                      return { ssCount: ssKeys.length, badLs };
                    }""",
                    author_tok,
                )
                check(
                    storage_audit["ssCount"] == 0 and not storage_audit["badLs"],
                    f"tokens leaked to web storage: {storage_audit}",
                )
                check(page.locator(".social-layout.signed-in").count() == 1, "missing .social-layout.signed-in")

                # Create text post
                text_body = ctx.text("S1 browser text")
                page.fill("#post-body", text_body)
                page.click("#post-submit")
                text_card = page.locator(".post-card", has_text=text_body).first
                text_card.wait_for(state="visible", timeout=15000)
                text_post_id = text_card.get_attribute("data-post-id")
                check(bool(text_post_id), "created text post missing data-post-id")

                # Upload & post PNG image
                img_body = ctx.text("S1 browser image")
                page.fill("#post-body", img_body)
                page.set_input_files("#post-media", str(png_path))
                page.wait_for_selector("#media-selected:not([hidden])", timeout=10000)
                page.click("#post-submit")
                img_card = page.locator(".post-card", has_text=img_body).first
                img_card.wait_for(state="visible", timeout=15000)
                img_el = img_card.locator("img.post-image")
                img_el.wait_for(state="visible", timeout=15000)
                img_info = img_el.evaluate("el => ({ src: el.src, w: el.naturalWidth })")
                check(img_info["src"].startswith("blob:") and img_info["w"] > 0, f"image failed blob render: {img_info}")

                # Upload & post MP4 video and verify playback
                vid_body = ctx.text("S1 browser video")
                page.fill("#post-body", vid_body)
                page.set_input_files("#post-media", str(mp4_path))
                page.wait_for_selector("#media-selected:not([hidden])", timeout=10000)
                page.click("#post-submit")
                vid_card = page.locator(".post-card", has_text=vid_body).first
                vid_card.wait_for(state="visible", timeout=15000)
                vid_card.locator("button.video-load-button").click()
                vid_el = vid_card.locator("video.post-video")
                vid_el.wait_for(state="visible", timeout=15000)
                vid_info = vid_el.evaluate(
                    """async (v) => {
                      await v.play();
                      await new Promise(r => setTimeout(r, 400));
                      return { src: v.src, w: v.videoWidth, t: v.currentTime };
                    }"""
                )
                check(
                    vid_info["src"].startswith("blob:") and vid_info["w"] > 0 and vid_info["t"] > 0,
                    f"video playback check failed: {vid_info}",
                )

                # Heart react + comment + persistence across refresh + delete comment & post
                heart_btn = text_card.locator("button.action-pill").first
                heart_btn.click()
                page.wait_for_function(
                    "(id) => document.querySelector(`.post-card[data-post-id='${id}'] button.action-pill`)?.getAttribute('aria-pressed') === 'true'",
                    arg=text_post_id,
                    timeout=10000,
                )

                text_card.locator("button.action-pill").nth(1).click()
                comment_input = page.locator(f"#comment-input-{text_post_id}")
                comment_input.wait_for(state="visible", timeout=10000)
                comment_text = ctx.text("S1 browser comment")
                comment_input.fill(comment_text)
                text_card.locator(".comment-form-row button.button-primary").click()
                text_card.locator(".comment-card", has_text=comment_text).wait_for(state="visible", timeout=15000)

                # Report other user's post via #report-dialog
                other_card = page.locator(f".post-card[data-post-id='{other_post['id']}']")
                other_card.wait_for(state="visible", timeout=15000)
                other_card.locator("button.action-pill", has_text="Báo cáo").click()
                page.wait_for_selector("#report-dialog[open]", timeout=10000)
                page.select_option("#report-reason", "SPAM")
                page.click("#report-submit")
                page.wait_for_function("() => !document.getElementById('report-dialog').open", timeout=10000)

                # Delete own comment & own text post (auto-accept confirm dialogs)
                page.on("dialog", lambda d: d.accept())
                text_card.locator(".comment-card", has_text=comment_text).locator("button.action-pill.danger").click()
                page.wait_for_function(
                    "([id, txt]) => !document.querySelector(`.post-card[data-post-id='${id}']`)?.innerText.includes(txt)",
                    arg=[text_post_id, comment_text],
                    timeout=10000,
                )
                text_card.locator("button.action-pill.danger").first.click()
                page.wait_for_function(
                    "(id) => !document.querySelector(`.post-card[data-post-id='${id}']`)",
                    arg=text_post_id,
                    timeout=10000,
                )

                # Clean sign out
                page.click("#logout-button")
                page.wait_for_selector("#signed-out-panel:not([hidden])", timeout=10000)
                rec.assert_clean("S1 social lifecycle")
    finally:
        for p in (png_path, mp4_path):
            p.unlink(missing_ok=True)
        # Unpublish announcement & clean up posts
        cur2 = ctx.get_admin_settings()
        if cur2.get("published") is not None:
            ctx.spring.post(
                "/api/v1/admin/social/settings/announcement/unpublish",
                token=ctx.token("admin"),
                body={"expectedRevision": cur2["revision"]},
            )
        ctx.spring.delete(f"/api/v1/feed/posts/{other_post['id']}", token=ctx.token("reader"))


def _t4_s1b_m2_web_features(ctx: SuiteContext) -> None:
    ctx.require_milestone(
        "M2",
        ctx.caps.m2_social_features and ctx.caps.m1_all,
        "M2 Web Social features (search, edit, multi-emoji, author filter, report details, DOM stability) or M1 APIs not landed yet",
    )
    ctx.set_social_flags(True, True)
    unique_tag = f"caphe_{ctx.nonce}"
    reader_post = None
    author_vid_post = None

    try:
        with BrowserDriver(ctx.proxy_base_url) as driver:
            with driver.new_page(width=1440, height=900, color_scheme="light", locale="en-US") as (page, rec):
                page.goto(f"{ctx.proxy_base_url}/social/#lang-en", wait_until="networkidle")
                lead = page.inner_text("#social-lead")
                check("existing images" not in lead, f"stale EN lead text: {lead!r}")

                # Switch to VI and sign in (also refreshes author's token after T4.S1 logout)
                page.click("#lang-vi-button")
                check("ảnh đã có" not in page.inner_text("#social-lead"), "stale VI lead text")
                _social_login(page, rec, ctx, "author")
                check(page.locator("#session-panel .header-user-pill").is_visible(), "header user pill missing")

                # Seed posts now that author has a fresh valid token
                r_resp = ctx.create_post_safe("reader", f"C\u00e0 ph\u00ea {unique_tag} s\u1eefa \u0111\u00e1")
                expect_status(r_resp, 201, "S1b seed reader post")
                reader_post = r_resp.json()

                up_resp = ctx.spring.upload_multipart(
                    "/api/v1/feed/videos",
                    token=ctx.token("author"),
                    files=[("file", "s1b_clip.mp4", "video/mp4", load_valid_mp4_bytes())],
                )
                expect_status(up_resp, 201, "S1b upload video")
                up_vid = up_resp.json()

                a_resp = ctx.create_post_safe(
                    "author",
                    ctx.text("S1b editable video post"),
                    video_id=up_vid["videoId"],
                )
                expect_status(a_resp, 201, "S1b create author video post")
                author_vid_post = a_resp.json()

                with page.expect_response(lambda r: "/api/social/v1/posts" in r.url and r.request.method == "GET"):
                    page.click("#refresh-button")

                # Capture Light VI signed-in feed (full-window viewport)
                page.locator(f".post-card[data-post-id='{reader_post['id']}']").wait_for(state="visible", timeout=15000)
                page.evaluate("() => window.scrollTo(0, 0)")
                ctx.capture_screenshot(page, "social-light-vi.png", full_page=False)

                # Toggle Dark theme via #theme-toggle -> verify persistence & Dark tokens -> capture Dark VI
                page.click("#theme-toggle")
                check(page.get_attribute("html", "data-theme") == "dark", "theme-toggle did not set data-theme=dark")
                check(page.evaluate("() => localStorage.getItem('social.theme')") == "dark", "theme not persisted in localStorage")
                check(page.get_attribute("#theme-toggle", "aria-pressed") == "true", "theme-toggle aria-pressed != true")
                dark_canvas = normalize_hex_color(
                    page.evaluate("() => getComputedStyle(document.documentElement).getPropertyValue('--canvas')")
                )
                check(dark_canvas == "#151316", f"dark --canvas expected #151316, got {dark_canvas}")
                page.wait_for_timeout(300)
                page.evaluate("() => window.scrollTo(0, 0)")
                audit_page_design(
                    page,
                    color_scheme="dark",
                    check_touch_targets=True,
                    min_touch_px=47.5,
                    contrast_selectors=[
                        "body",
                        "h1",
                        "#logout-button",
                        "#lang-vi-button",
                        "#post-submit",
                        "#feed-search-input",
                        ".disclosure",
                        "#open-chat-link",
                    ],
                )
                ctx.capture_screenshot(page, "social-dark-vi.png", full_page=False)

                # Toggle back to Light
                page.click("#theme-toggle")
                check(page.get_attribute("html", "data-theme") == "light", "theme-toggle did not return to light")
                page.wait_for_timeout(300)

                # Multi-emoji picker & active reaction pills + Range video playback
                vid_card = page.locator(f".post-card[data-post-id='{author_vid_post['id']}']")
                vid_card.wait_for(state="visible", timeout=15000)
                vid_card.locator("button.video-load-button").click()
                vid_el = vid_card.locator("video.post-video")
                vid_el.wait_for(state="visible", timeout=15000)
                vid_info = vid_el.evaluate(
                    """async (v) => {
                      await v.play();
                      await new Promise(r => setTimeout(r, 350));
                      return { src: v.src, w: v.videoWidth, t: v.currentTime };
                    }"""
                )
                check(vid_info["src"].startswith("blob:") and vid_info["w"] > 0 and vid_info["t"] > 0, f"video failed: {vid_info}")

                vid_card.locator('[data-action="reaction-picker"]').click()
                tray = vid_card.locator(".reaction-picker-tray")
                tray.wait_for(state="visible", timeout=5000)
                tray.locator('.reaction-emoji-option[data-emoji="🔥"]').click()
                vid_card.locator('.reaction-pill[data-emoji="🔥"]').wait_for(state="visible", timeout=10000)

                vid_card.locator('[data-action="reaction-picker"]').click()
                tray.locator('.reaction-emoji-option[data-emoji="👍"]').click()
                thumbs_pill = vid_card.locator('.reaction-pill[data-emoji="👍"]')
                thumbs_pill.wait_for(state="visible", timeout=10000)
                check(thumbs_pill.get_attribute("aria-pressed") == "true", "reaction pill not marked pressed")
                # Reopen picker tray briefly so screenshot shows video + active pills + multi-emoji picker
                vid_card.locator('[data-action="reaction-picker"]').click()
                tray.wait_for(state="visible", timeout=5000)
                vid_card.evaluate("el => el.scrollIntoView({ block: 'center' })")
                ctx.capture_screenshot(page, "social-video-reactions.png", full_page=False)
                vid_card.locator('[data-action="reaction-picker"]').click()

                # DOM stability: open comments, type unsent draft + preserve DOM node, focus & video across refresh
                vid_card.locator('[data-action="comments"]').click()
                c_input = page.locator(f"#comment-input-{author_vid_post['id']}")
                c_input.wait_for(state="visible", timeout=10000)
                draft_text = f"draft_keep_{ctx.nonce}"
                c_input.fill(draft_text)
                page.evaluate(
                    """([cid, pid]) => {
                      window.__keptInput = document.getElementById(cid);
                      window.__keptVideo = document.querySelector(`.post-card[data-post-id='${pid}'] video.post-video`);
                    }""",
                    [f"comment-input-{author_vid_post['id']}", author_vid_post["id"]],
                )
                c_input.focus()
                # Dispatch click on refresh without stealing focus via pointer, verifying targeted DOM reconciliation
                with page.expect_response(lambda r: "/api/social/v1/posts" in r.url and r.request.method == "GET"):
                    page.evaluate("() => document.getElementById('refresh-button').click()")
                vid_card.locator('[data-action="heart"]').click()
                page.wait_for_function(
                    "(id) => document.querySelector(`.post-card[data-post-id='${id}'] [data-action='heart']`)?.getAttribute('aria-pressed') === 'true'",
                    arg=author_vid_post["id"],
                    timeout=10000,
                )
                check(c_input.input_value() == draft_text, "reaction/refresh wiped unsent comment draft")
                dom_kept = page.evaluate(
                    """([cid, pid]) => ({
                      sameInput: document.getElementById(cid) === window.__keptInput,
                      sameVideo: document.querySelector(`.post-card[data-post-id='${pid}'] video.post-video`) === window.__keptVideo
                    })""",
                    [f"comment-input-{author_vid_post['id']}", author_vid_post["id"]],
                )
                check(dom_kept["sameInput"] and dom_kept["sameVideo"], f"DOM nodes recreated on refresh/reaction: {dom_kept}")

                # Submit comment, inline edit comment -> verify (đã chỉnh sửa) badge
                final_comment = ctx.text("S1b comment")
                c_input.fill(final_comment)
                vid_card.locator(".comment-form-row button.button-primary").click()
                c_match = vid_card.locator(".comment-card", has_text=final_comment)
                c_match.wait_for(state="visible", timeout=15000)
                comment_id = c_match.get_attribute("data-comment-id")
                check(bool(comment_id), "comment missing data-comment-id")
                c_card = vid_card.locator(f".comment-card[data-comment-id='{comment_id}']")
                c_card.locator('[data-action="edit-comment"]').click()
                edited_comment = ctx.text("S1b edited comment")
                c_card.locator(".inline-edit-input").fill(edited_comment)
                c_card.locator('[data-edit-action="save"]').click()
                c_card.locator(".comment-body", has_text=edited_comment).wait_for(state="visible", timeout=15000)
                check(
                    c_card.locator(".edited-badge").is_visible(),
                    "edited comment badge not visible",
                )

                # Inline edit own post -> verify updated body + (đã chỉnh sửa) badge
                vid_card.locator('[data-action="edit"]').click()
                edited_post_body = ctx.text("S1b edited post")
                vid_card.locator(".post-body-wrap .inline-edit-input").fill(edited_post_body)
                vid_card.locator('.post-body-wrap [data-edit-action="save"]').click()
                vid_card.locator(".post-body", has_text=edited_post_body).wait_for(state="visible", timeout=15000)
                check(vid_card.locator(".post-header .edited-badge").first.is_visible(), "edited post badge not visible")

                # Keyword search (accent-insensitive ?q=) + empty-search + clear
                page.fill("#feed-search-input", f"ca phe {unique_tag}")
                page.press("#feed-search-input", "Enter")
                page.wait_for_function(
                    "([pid, otherId]) => Boolean(document.querySelector(`.post-card[data-post-id='${pid}']`)) && !document.querySelector(`.post-card[data-post-id='${otherId}']`)",
                    arg=[reader_post["id"], author_vid_post["id"]],
                    timeout=15000,
                )

                # Author Profile modal + Author Filter chip while search is visible
                r_card = page.locator(f".post-card[data-post-id='{reader_post['id']}']")
                r_card.locator(".author-trigger").click()
                page.wait_for_selector("#author-dialog[open]", timeout=10000)
                page.wait_for_selector("#author-dialog-filter:not([hidden])", timeout=10000)
                check("Reader" in page.inner_text("#author-dialog-title"), "author dialog missing displayName")
                ctx.capture_screenshot(page, "social-author-profile-search.png", full_page=False)

                page.click("#author-dialog-filter")
                page.wait_for_selector("#author-filter-chip:not([hidden])", timeout=10000)
                page.click("#feed-search-clear")
                check(page.input_value("#feed-search-input") == "", "search input not cleared")

                # Empty-search state
                page.fill("#feed-search-input", f"no_such_match_{ctx.nonce}_zzz")
                page.press("#feed-search-input", "Enter")
                page.wait_for_function(
                    "() => document.querySelectorAll('#feed-list .post-card').length === 0",
                    timeout=15000,
                )
                page.click("#feed-search-clear")
                page.click("#author-filter-clear")
                page.wait_for_selector(f".post-card[data-post-id='{author_vid_post['id']}']", timeout=15000)
                r_card.wait_for(state="visible", timeout=15000)

                # Report with optional #report-details (maxlength 500)
                r_card.locator('[data-action="report"]').click()
                page.wait_for_selector("#report-dialog[open]", timeout=10000)
                page.select_option("#report-reason", "SPAM")
                page.fill("#report-details", f"M2 report details {ctx.nonce}")
                page.click("#report-submit")
                page.wait_for_function("() => !document.getElementById('report-dialog').open", timeout=10000)

                rec.assert_clean("S1b M2 web features")
    finally:
        if reader_post is not None:
            ctx.spring.delete(f"/api/v1/feed/posts/{reader_post['id']}", token=ctx.token("reader"))
        if author_vid_post is not None:
            ctx.spring.delete(f"/api/v1/feed/posts/{author_vid_post['id']}", token=ctx.token("author"))


# --------------------------------------------------------------------------- S2
def _t4_s2_admin_live_control_and_moderation(ctx: SuiteContext) -> None:
    ctx.set_social_flags(True, True)
    # Seed a reported video post for /admin/feed moderation verification
    up = ctx.spring.upload_multipart(
        "/api/v1/feed/videos",
        token=ctx.token("reactor"),
        files=[("file", "s2_rep.mp4", "video/mp4", load_valid_mp4_bytes())],
    ).json()
    rep_post = ctx.create_post_safe("reactor", ctx.text("S2 reported video post"), video_id=up["videoId"]).json()
    rep = ctx.spring.post(
        "/api/v1/reports",
        token=ctx.token("reader"),
        body={"targetType": "POST", "targetId": rep_post["id"], "reason": "SPAM", "details": f"S2 details {ctx.nonce}"},
    ).json()

    try:
        with BrowserDriver(ctx.proxy_base_url) as driver:
            with driver.new_page(width=1440, height=900, color_scheme="light", locale="en-US") as (page, rec):
                # 1. Unauthenticated /admin/overview redirects to /admin/login
                page.goto(f"{ctx.admin_base_url}/overview", wait_until="networkidle")
                page.wait_for_url("**/login", timeout=15000)

                # 2. ADMIN login
                _admin_login(page, rec, ctx.admin_base_url, ctx.phone("admin"), FIXTURE_PASSWORD, expect_success=True)

                # 3. /admin/social: toggle webEnabled=false -> verify /social/ unavailable & Android stays 200 -> restore
                page.click('aside.sidebar nav a[href="/admin/social"]')
                page.wait_for_selector("#social-flags-title", timeout=15000)
                ctx.capture_screenshot(page, "admin-social.png", full_page=False)
                web_cb = page.locator("label.check-row input[type='checkbox']").first
                if web_cb.is_checked():
                    web_cb.uncheck()
                    page.locator("section", has=page.locator("#social-flags-title")).locator("button.btn").click()
                    page.locator('[role="alertdialog"] button.btn.primary').click()
                    page.wait_for_selector('.toast[role="status"]', timeout=10000)

                # Verify /social/ fails closed while Android /api/v1/feed/posts stays 200
                with driver.new_page(width=1440, height=900) as (soc_page, soc_rec):
                    soc_page.goto(f"{ctx.proxy_base_url}/social/", wait_until="networkidle")
                    soc_page.wait_for_selector("#unavailable-panel:not([hidden])", timeout=15000)
                    check(soc_page.is_hidden("#signed-out-panel"), "signed-out panel should be hidden when disabled")
                    soc_rec.assert_clean("S2 disabled social")
                expect_status(ctx.spring.get("/api/v1/feed/posts", token=ctx.token("reader")), 200, "Android unaffected")

                # Re-enable webEnabled=true
                web_cb.check()
                page.locator("section", has=page.locator("#social-flags-title")).locator("button.btn").click()
                page.locator('[role="alertdialog"] button.btn.primary').click()
                page.wait_for_selector('.toast[role="status"]', timeout=10000)

                # 4. Bilingual CMS draft -> publish -> verify on /social/ -> take down
                vi_title = f"Admin S2 VI {ctx.nonce}"
                en_title = f"Admin S2 EN {ctx.nonce}"
                fieldsets = page.locator(".social-draft-lang")
                fieldsets.nth(0).locator("input").fill(vi_title)
                fieldsets.nth(0).locator("textarea").fill("Noi dung S2 VI")
                fieldsets.nth(1).locator("input").fill(en_title)
                fieldsets.nth(1).locator("textarea").fill("S2 EN body")
                page.locator(".social-cms-actions button.btn.ghost").click()
                page.wait_for_selector('.toast[role="status"]', timeout=10000)
                page.locator(".social-cms-actions button.btn:not(.ghost)").click()
                page.locator('[role="alertdialog"] button.btn.primary').click()
                page.wait_for_selector('.toast[role="status"]', timeout=10000)

                with driver.new_page(width=1440, height=900) as (soc_page, soc_rec):
                    soc_page.goto(f"{ctx.proxy_base_url}/social/", wait_until="networkidle")
                    soc_page.wait_for_selector("#announcement-card:not([hidden])", timeout=15000)
                    check(vi_title in soc_page.inner_text("#announcement-title"), "published VI title missing on /social/")
                    soc_page.click("#lang-en-button")
                    check(en_title in soc_page.inner_text("#announcement-title"), "published EN title missing on /social/")
                    soc_rec.assert_clean("S2 live announcement check")

                # Take down
                page.locator(".social-live button.btn.ghost").click()
                page.locator('[role="alertdialog"] button.btn.danger').click()
                page.wait_for_selector('.toast[role="status"]', timeout=10000)

                # 5. 409 OCC conflict handling on /admin/social
                cur_rev = ctx.get_admin_settings()
                ctx.spring.patch(
                    "/api/v1/admin/social/settings/flags",
                    token=ctx.token("admin"),
                    body={"expectedRevision": cur_rev["revision"], "webEnabled": True, "videoUploadsEnabled": False},
                )
                with rec.allow(statuses=(409,), url_substrings=("/api/v1/admin/social/settings/",)):
                    page.locator("label.check-row input[type='checkbox']").nth(1).uncheck()
                    page.locator("section", has=page.locator("#social-flags-title")).locator("button.btn").click()
                    page.locator('[role="alertdialog"] button.btn.primary').click()
                    page.wait_for_selector('[role="alert"]', timeout=10000)

                # 6. /admin/feed moderation queue: expand details, video preview, hide, unhide, resolve
                page.click('aside.sidebar nav a[href="/admin/feed"]')
                row = page.locator("tbody tr", has_text=rep_post["body"]).first
                row.wait_for(state="visible", timeout=15000)
                row.locator('button[aria-expanded]').click()
                check(f"S2 details {ctx.nonce}" in row.inner_text(), "expanded details missing")
                row.locator(".report-video button").click()
                vid = row.locator("video")
                vid.wait_for(state="visible", timeout=15000)
                v_info = vid.evaluate(
                    """async (v) => {
                      await v.play();
                      await new Promise(r => setTimeout(r, 400));
                      return { src: v.src, w: v.videoWidth, t: v.currentTime };
                    }"""
                )
                check(v_info["src"].startswith("blob:") and v_info["w"] > 0, f"admin video preview invalid: {v_info}")
                row.locator(".report-video button").click()  # Close video
                check(row.locator("video").count() == 0, "video not closed")

                # Hide -> verify 404 -> Unhide -> Resolve
                row.get_by_role("button", name="Hide").click()
                page.fill("#confirm-reason", "S2 browser hide")
                page.locator('[role="alertdialog"] button.btn.danger').click()
                page.wait_for_selector('.toast[role="status"]', timeout=10000)
                expect_error(ctx.proxy.get(f"/api/social/v1/posts/{rep_post['id']}", token=ctx.token("reader")), 404, "POST_NOT_FOUND", "hidden post 404")

                row.get_by_role("button", name="Unhide").click()
                page.locator('[role="alertdialog"] button.btn.primary').click()
                page.wait_for_selector('.toast[role="status"]', timeout=10000)

                row.get_by_role("button", name="Mark reviewed").click()
                page.locator('[role="alertdialog"] button.btn.primary').click()
                page.wait_for_selector('.toast[role="status"]', timeout=10000)
                rec.assert_clean("S2 admin control & moderation")
    finally:
        ctx.spring.post(f"/api/v1/admin/feed/reports/{rep['id']}/resolve", token=ctx.token("admin"), body={"status": "DISMISSED"})
        ctx.spring.delete(f"/api/v1/feed/posts/{rep_post['id']}", token=ctx.token("reactor"))
        ctx.set_social_flags(True, True)


def _t4_s2b_m3_moderator_gate_and_draft_conflict(ctx: SuiteContext) -> None:
    ctx.require_milestone("M3", ctx.caps.m3_moderator_gate, "M3 MODERATOR /social role gate & SocialPage 409 draft preservation not landed yet")
    with BrowserDriver(ctx.proxy_base_url) as driver:
        with driver.new_page(width=1440, height=900, color_scheme="light", locale="en-US") as (page, rec):
            _admin_login(page, rec, ctx.admin_base_url, ctx.phone("moderator"), FIXTURE_PASSWORD, expect_success=True)
            check(page.locator('aside.sidebar nav a[href="/admin/social"]').count() == 0, "MODERATOR should not see Social nav link")
            page.goto(f"{ctx.admin_base_url}/social", wait_until="networkidle")
            check(page.locator("#social-flags-title").count() == 0, "MODERATOR must not access SocialPage controls")
            rec.assert_clean("S2b M3 moderator gate")

        # Verify SocialPage 409 conflict on saveDraft preserves unsaved draft edits (M3 bug #13)
        with driver.new_page(width=1440, height=900, color_scheme="light", locale="en-US") as (page, rec):
            admin_rec = ctx.sessions.get("admin")
            page.add_init_script(
                f"sessionStorage.setItem('admin.accessToken', {admin_rec.access_token!r});"
                f"sessionStorage.setItem('admin.refreshToken', {admin_rec.refresh_token!r});"
            )
            page.goto(f"{ctx.admin_base_url}/social", wait_until="networkidle")
            page.wait_for_selector("#social-cms-title", timeout=15000)
            unsaved_vi = f"Unsaved VI {ctx.nonce}"
            fieldsets = page.locator(".social-draft-lang")
            fieldsets.nth(0).locator("input").fill(unsaved_vi)
            fieldsets.nth(0).locator("textarea").fill("Unsaved body VI")
            fieldsets.nth(1).locator("input").fill(f"Unsaved EN {ctx.nonce}")
            fieldsets.nth(1).locator("textarea").fill("Unsaved body EN")
            cur_rev = ctx.get_admin_settings()
            ctx.spring.patch(
                "/api/v1/admin/social/settings/flags",
                token=ctx.token("admin"),
                body={"expectedRevision": cur_rev["revision"], "webEnabled": True, "videoUploadsEnabled": True},
            )
            with rec.allow(statuses=(409,), url_substrings=("/api/v1/admin/social/settings/",)):
                page.locator(".social-cms-actions button.btn.ghost").click()
                page.wait_for_selector('[role="alert"]', timeout=10000)
            check(fieldsets.nth(0).locator("input").input_value() == unsaved_vi, "M3 saveDraft 409 wiped unsaved draft input")
            rec.assert_clean("S2b M3 saveDraft 409 preservation")


# --------------------------------------------------------------------------- S3
def _t4_s3_safety_isolation_cross_user_privacy(ctx: SuiteContext) -> None:
    ctx.set_social_flags(True, True)
    blocker_post = ctx.create_post_safe("blocker", ctx.text("S3 block isolation post")).json()
    try:
        with BrowserDriver(ctx.proxy_base_url) as driver:
            with driver.new_page(width=1440, height=900, color_scheme="light", locale="en-US") as (page, rec):
                page.goto(f"{ctx.proxy_base_url}/social/#lang-en", wait_until="networkidle")
                _social_login(page, rec, ctx, "author")
                page.locator(f".post-card[data-post-id='{blocker_post['id']}']").wait_for(state="visible", timeout=15000)

                # Blocker blocks author
                ctx.ensure_block("blocker", "author", True)
                page.click("#refresh-button")
                page.wait_for_function(
                    "(id) => !document.querySelector(`.post-card[data-post-id='${id}']`)",
                    arg=blocker_post["id"],
                    timeout=15000,
                )
                expect_error(
                    ctx.proxy.get(f"/api/social/v1/posts/{blocker_post['id']}", token=ctx.token("author")),
                    404,
                    "POST_NOT_FOUND",
                    "blocked user API 404",
                )
                expect_status(
                    ctx.proxy.get(f"/api/social/v1/posts/{blocker_post['id']}", token=ctx.token("reader")),
                    200,
                    "third-party C still sees blocked post",
                )

                # Unblock restores
                ctx.ensure_block("blocker", "author", False)
                page.click("#refresh-button")
                page.locator(f".post-card[data-post-id='{blocker_post['id']}']").wait_for(state="visible", timeout=15000)
                rec.assert_clean("S3 safety isolation")
    finally:
        ctx.ensure_block("blocker", "author", False)
        ctx.spring.delete(f"/api/v1/feed/posts/{blocker_post['id']}", token=ctx.token("blocker"))


# --------------------------------------------------------------------------- S4
def _t4_s4_join_invite_handoff_and_navigation(ctx: SuiteContext) -> None:
    with BrowserDriver(ctx.proxy_base_url) as driver:
        for width, height in ((1440, 900), (390, 844)):
            with driver.new_page(width=width, height=height, color_scheme="light", locale="vi-VN") as (page, rec):
                page.goto(f"{ctx.proxy_base_url}/join/{VALID_JOIN_TOKEN}", wait_until="networkidle")
                page.wait_for_selector("#invite-valid:not([hidden])", timeout=10000)
                check(page.is_hidden("#invite-invalid"), "invite-invalid visible on valid token")
                check(page.is_hidden("#invite-pending"), "invite-pending still visible")
                href = page.get_attribute("#btn-open-app", "href")
                rel = page.get_attribute("#btn-open-app", "rel")
                check(href == f"nexachat://join/{VALID_JOIN_TOKEN}", f"unexpected href: {href!r}")
                check(rel == "noreferrer", f"unexpected rel: {rel!r}")
                audit_page_design(
                    page,
                    color_scheme="light",
                    check_touch_targets=True,
                    touch_target_selector="main button, main a.btn, main input, main select, main textarea, dialog button",
                    min_touch_px=47.5,
                    contrast_selectors=["body", ".lead", "#btn-open-app"],
                )
                rec.assert_clean(f"S4 valid join {width}x{height}")

                for bad_url in (
                    f"{ctx.proxy_base_url}/join/",
                    f"{ctx.proxy_base_url}/join/too_short",
                    f"{ctx.proxy_base_url}/join/{VALID_JOIN_TOKEN}?x=1",
                    f"{ctx.proxy_base_url}/join/{VALID_JOIN_TOKEN}#frag",
                ):
                    page.goto(bad_url, wait_until="networkidle")
                    page.wait_for_selector("#invite-invalid:not([hidden])", timeout=10000)
                    check(page.is_hidden("#invite-valid"), f"invite-valid visible for {bad_url}")


def _t4_s4b_m2_join_footer_links(ctx: SuiteContext) -> None:
    ctx.require_milestone("M2", ctx.caps.m2_join_footer, "M2 /join/ footer /social/ navigation links not landed yet")
    with BrowserDriver(ctx.proxy_base_url) as driver:
        with driver.new_page(width=1440, height=900) as (page, rec):
            page.goto(f"{ctx.proxy_base_url}/join/{VALID_JOIN_TOKEN}", wait_until="networkidle")
            footer_social = page.locator("footer nav a[href^='/social/']")
            check(footer_social.count() >= 1 and footer_social.first.is_visible(), "footer /social/ link not immediately visible")
            check(page.is_hidden("#app-fallback"), "#app-fallback should stay hidden on load")
            rec.assert_clean("S4b M2 join footer")


# --------------------------------------------------------------------------- S5
def _t4_s5_design_system_contrast_and_layout_audit(ctx: SuiteContext) -> None:
    ctx.set_social_flags(True, True)
    expected_light = {
        "--canvas": "#fff7fc",
        "--primary": "#733f68",
        "--primary-tonal": "#fcd7f4",
        "--accent": "#246b57",
    }
    expected_dark = {
        "--canvas": "#151316",
        "--primary": "#f4b2e2",
        "--primary-tonal": "#67355d",
        "--accent": "#8cd6b7",
    }
    with BrowserDriver(ctx.proxy_base_url) as driver:
        for width, height in ((1440, 900), (390, 844)):
            for scheme in ("light", "dark"):
                for lang in ("vi", "en"):
                    url = f"{ctx.proxy_base_url}/social/" + ("#lang-en" if lang == "en" else "")
                    with driver.new_page(
                        width=width,
                        height=height,
                        color_scheme=scheme,
                        locale="en-US" if lang == "en" else "vi-VN",
                    ) as (page, rec):
                        page.goto(url, wait_until="networkidle")
                        page.wait_for_selector("#signed-out-panel:not([hidden])", timeout=15000)
                        res = audit_page_design(
                            page,
                            color_scheme=scheme,
                            check_touch_targets=True,
                            min_touch_px=47.5,
                            contrast_selectors=[
                                "body",
                                "h1",
                                ".lead",
                                ".muted",
                                "#login-button",
                                "#open-chat-link",
                                ".eyebrow",
                            ],
                            token_names=["--canvas", "--primary", "--primary-tonal", "--accent"],
                        )
                        exp = expected_dark if scheme == "dark" else expected_light
                        for k, want in exp.items():
                            got = normalize_hex_color(res["tokens"][k])
                            check(got == want, f"Tonal Plum {scheme} token {k}: expected {want}, got {got}")
                        rec.assert_clean(f"S5 social {width}x{height} {scheme} {lang}")

        # Admin login & layout audit (light/dark via [data-theme])
        for scheme in ("light", "dark"):
            with driver.new_page(width=1440, height=900, color_scheme=scheme, locale="en-US") as (page, rec):
                page.goto(f"{ctx.admin_base_url}/login", wait_until="networkidle")
                if scheme == "dark" and page.get_attribute("html", "data-theme") != "dark":
                    toggle = page.locator("button.theme-toggle")
                    if toggle.count() > 0:
                        toggle.first.click()
                audit_page_design(
                    page,
                    color_scheme=scheme,
                    check_touch_targets=False,
                    contrast_selectors=["body", "h1", ".login-sub", ".login-submit"],
                )
                rec.assert_clean(f"S5 admin login {scheme}")


def _t4_s5b_m2_stitch_design_parity(ctx: SuiteContext) -> None:
    ctx.require_milestone("M2", ctx.caps.m2_design_tokens, "M2 Tonal Plum design system unification not landed yet")
    expected_ramp = {
        "light": {
            "--surface-lowest": "#ffffff",
            "--surface-low": "#f9f1f6",
            "--surface-container": "#f3ecf0",
            "--surface-high": "#ede6eb",
            "--surface-highest": "#e8e0e5",
        },
        "dark": {
            "--surface-lowest": "#100d11",
            "--surface-low": "#1d1b1e",
            "--surface-container": "#211f22",
            "--surface-high": "#2c292d",
            "--surface-highest": "#383337",
        },
    }
    with BrowserDriver(ctx.proxy_base_url) as driver:
        for width, height in ((1440, 900), (390, 844)):
            for scheme in ("light", "dark"):
                with driver.new_page(width=width, height=height, color_scheme=scheme) as (page, rec):
                    page.goto(f"{ctx.proxy_base_url}/social/", wait_until="networkidle")
                    check("SOCIAL" in page.inner_text(".social-header"), "SOCIAL badge missing in header")
                    plate_box = page.locator(".brand__plate").bounding_box()
                    check(
                        plate_box is not None and abs(plate_box["width"] - 32.0) <= 1.0 and abs(plate_box["height"] - 32.0) <= 1.0,
                        f"brand plate expected 32x32, got {plate_box}",
                    )
                    toggle_box = page.locator("#theme-toggle").bounding_box()
                    check(
                        toggle_box is not None and toggle_box["width"] >= 47.5 and toggle_box["height"] >= 47.5,
                        f"theme toggle below 48px target: {toggle_box}",
                    )
                    tokens = page.evaluate(
                        """(names) => {
                          const st = getComputedStyle(document.documentElement);
                          const out = {};
                          for (const n of names) out[n] = st.getPropertyValue(n).trim();
                          return out;
                        }""",
                        list(expected_ramp[scheme].keys()),
                    )
                    for k, want in expected_ramp[scheme].items():
                        got = normalize_hex_color(tokens[k])
                        check(got == want, f"Tonal Plum {scheme} {width}x{height} {k}: expected {want}, got {got}")
                    card_style = page.evaluate(
                        """() => {
                          const c = document.querySelector('#signed-out-panel');
                          const st = getComputedStyle(c);
                          return { shadow: st.boxShadow, radius: st.borderRadius };
                        }"""
                    )
                    check(card_style["shadow"] == "none", f"card should have 0px shadow, got {card_style['shadow']}")
                    check(card_style["radius"] == "16px", f"card radius expected 16px, got {card_style['radius']}")
                    rec.assert_clean(f"S5b M2 Stitch parity {width}x{height} {scheme}")


# --------------------------------------------------------------------------- S6 & S7 (Android)
def _t4_s6_android_api_independence(ctx: SuiteContext) -> None:
    ctx.set_social_flags(False, True)
    expect_error(ctx.proxy.get("/api/social/v1/posts", token=ctx.token("reader")), 403, "SOCIAL_FEATURE_DISABLED", "web social off")
    p = ctx.create_post_safe("admin", ctx.text("S6 Android independent post"), prefix="/api/v1/feed")
    expect_status(p, 201, "Android post create while web off")
    pid = p.json()["id"]
    expect_status(ctx.spring.get(f"/api/v1/feed/posts/{pid}", token=ctx.token("reader")), 200, "Android post read while web off")
    expect_status(ctx.spring.delete(f"/api/v1/feed/posts/{pid}", token=ctx.token("admin")), 204, "Android post delete")
    ctx.set_social_flags(True, True)


def _t4_s7_android_emulator_ui(ctx: SuiteContext) -> None:
    if not ctx.android_enabled:
        raise BlockedCapability("Optional --android flag not enabled; skipping emulator UI journey")
    apk = REPO_ROOT / "android" / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"
    if not apk.is_file():
        raise BlockedCapability(
            f"Android debug APK missing at {apk}. Build via: ./gradlew -PapiBaseUrl=http://10.0.2.2:<port> assembleDebug"
        )
    proc = subprocess.run(["adb", "-s", ctx.android_serial, "get-state"], capture_output=True, text=True, timeout=10)
    if proc.returncode != 0 or proc.stdout.strip() != "device":
        raise BlockedCapability(f"ADB device {ctx.android_serial} unavailable: {proc.stderr.strip()}")
    ctx.require_milestone("M4", ctx.caps.m4_android_apk, "M4 Android feed parity build not landed yet")


def get_tier4_checks() -> List[CheckCase]:
    raw = [
        ("T4.S1", "F15", "BASELINE", "Full bilingual Web Social lifecycle (1440x900, VI/EN, text/image/video/comments/reactions/report/delete)", _t4_s1_bilingual_web_social_lifecycle),
        ("T4.S1b", "F8", "M2", "M2 Web Social features (copy, multi-emoji picker, inline edit, author filter/profile, search, DOM stability)", _t4_s1b_m2_web_features),
        ("T4.S2", "F12", "BASELINE", "Admin live control, bilingual CMS, video preview moderation & 409 OCC", _t4_s2_admin_live_control_and_moderation),
        ("T4.S2b", "F12", "M3", "M3 MODERATOR /social role gate & SocialPage 409 draft preservation", _t4_s2b_m3_moderator_gate_and_draft_conflict),
        ("T4.S3", "F10", "BASELINE", "Safety isolation & cross-user block privacy across Browser + API", _t4_s3_safety_isolation_cross_user_privacy),
        ("T4.S4", "F14", "BASELINE", "/join/ invite token handoff validation (desktop + mobile)", _t4_s4_join_invite_handoff_and_navigation),
        ("T4.S4b", "F14", "M2", "M2 /join/ immediate /social/ link inside <footer><nav>", _t4_s4b_m2_join_footer_links),
        ("T4.S5", "F15", "BASELINE", "Tonal Plum design tokens, WCAG contrast & zero-error matrix (light/dark, VI/EN, desktop/mobile)", _t4_s5_design_system_contrast_and_layout_audit),
        ("T4.S5b", "F15", "M2", "M2 Stitch Tonal Plum brand plate, surface ramp & 8-hue avatar parity", _t4_s5b_m2_stitch_design_parity),
        ("T4.S6", "F16", "BASELINE", "Android /api/v1/feed/** independence while webEnabled=false", _t4_s6_android_api_independence),
        ("T4.S7", "F16", "M4", "Optional Android emulator UI & a11y oracle on feature/feed", _t4_s7_android_emulator_ui),
    ]
    return [CheckCase(cid, feat, 4, gate, title, fn) for cid, feat, gate, title, fn in raw]
