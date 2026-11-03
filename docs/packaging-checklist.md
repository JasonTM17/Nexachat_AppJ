# Packaging & Acceptance Checklist

> Rubric acceptance checklist (master design §20.1) — tick every box before
> submission. The packaging checklist below (adapted from §20.1-C1, §15–P12,
> and phase-14 tasks — plan §20 has no separate §20.3) is what C1 verifies on a
> clean machine.

## C1–C12 rubric acceptance table (§20.1 — tick before submitting)

| # | Hạng mục | Bằng chứng yêu cầu | ☐ |
|---|---|---|---|
| C1 | Bài nộp (R1) | Zip `GroupXX_MSSV1…zip`; có `.git/`; **không** có `build/`, `node_modules/`, keystore, `keystore.properties`; APK; `.docx`; thư mục screenshots; README khai báo AI | ☐ |
| C2 | Chat trực tiếp realtime + history + unread + read receipt | TC-C1..C7 pass + demo script bước 3–4 | ☐ |
| C3 | Chat nhóm + member mgmt + authZ | TC-GRP pass + demo bước 5 | ☐ |
| C4 | Tìm kiếm + thêm bằng SĐT (E.164) | TC-CONTACT pass + demo bước 2 | ☐ |
| C5 | Quản lý tài khoản (register/login/logout/profile/đổi mk) | TC-AUTH pass + demo bước 1 | ☐ |
| C6 | Đa ngôn ngữ Vi/En chuyển trong app | TC-I18N + demo bước 8 | ☐ |
| C7 | Multimedia & file (ảnh/video/audio/file + progress + open) | TC-FILE + demo bước 6 | ☐ |
| C8 | Xoay màn hình không mất state | TC-UI-ROT + demo bước 8 | ☐ |
| C9 | Creative: reply/reaction/pin | TC-INT + demo bước 7 | ☐ |
| C10 | UI rõ ràng (M3, mọi state) + toàn bộ unit test pass | CI run cuối xanh 100% + screenshots | ☐ |
| C11 | Thuyết trình/demo/vấn đáp | Diễn tập ≥2 lần; mỗi member giải thích slice mình; Q&A bank học thuộc | ☐ |
| C12 | Đóng góp thực chất | `git shortlog -sn` cân đối; mỗi member ≥N PR merged | ☐ |

## Submission packaging checklist

Run on a **clean machine** (fresh clone from the zip) before archiving:

- [ ] Zip named `GroupXX_MSSV1_MSSV2_….zip` per course convention.
- [ ] Zip **contains** `.git/` (full history — contribution evidence).
- [ ] Zip contains `README.md` with the completed AI Usage Declaration,
      `docs/report.docx`, `docs/screenshots/` (all features, both languages),
      `demo/`, and the release-signed APK.
- [ ] Zip does **not** contain: `build/`, `**/build/`, `target/`,
      `node_modules/`, `.gradle/`, `*.keystore`, `*.jks`,
      `keystore.properties`, `.env*`, `local.properties`,
      `google-services.json`, `server/uploads/`, `data/`, `*.log`.
      Verify: `git ls-files | grep -Ei 'keystore|\.env|local\.properties'`
      returns nothing.
- [ ] APK `release` is signed with the LOCAL keystore (keystore itself is
      never committed/submitted); tag `v1.0.0` exists.
- [ ] Fresh-clone verification: `docker compose up -d db` → `mvn -B verify`
      green → `demo/seed.sql` loads → 2 devices log in and chat over LAN.
- [ ] CI on the final commit is all green (server-test, android-test,
      secret-scan).
- [ ] `git shortlog -sn` shows balanced contributions across members
      (coefficient evidence).
