# NexaChat Screen Recording & GIF Tooling

Tools and standard operating procedures for recording demonstration GIFs of core user journeys on Android emulators or test devices.

## 1. Tooling Usage

`tools/gif/record.py` records the device screen via `adb shell screenrecord` and converts the resulting MP4 into an optimized GIF using `ffmpeg` with two-pass palette generation.

### Requirements
- Python 3.8+ (standard library only)
- `adb` (Android SDK Platform-Tools)
- `ffmpeg` (available in `PATH`)

### Command
```bash
python tools/gif/record.py --out docs/media/gif/01-login-register.gif --seconds 15 [--serial <device-id>]
```

- **Resolution & Framerate**: Recorded at `720x1280` (bit-rate 4 Mbps). Converted at `fps=10`, width `360px` (Lanczos scaling) using a 96-color palette with Bayer dithering (`bayer_scale=4`).
- **Size Cap & Fallback**: GIFs must remain under **6 MB**. If the primary pass exceeds 6 MB, the tool automatically retries conversion at `fps=8` and width `320px`. If it still exceeds 6 MB, execution halts with an error.
- **Safety**: Temporary MP4 files are cleaned up from both device (`/data/local/tmp/`) and host disk.

---

## 2. The 7-GIF Demonstration Shot List

All demo scenarios use generic fixture accounts (e.g. User Alpha and User Beta from synthetic test seeds) rather than real personal phone numbers or credentials.

| # | Topic / Journey | Output File | Target Duration | Preconditions | Generic Fixture Setup | Action Sequence | Expected Visual Proof |
|---|---|---|---|---|---|---|---|
| 1 | **Login + Register** | `docs/media/gif/01-login-register.gif` | 15s | Fresh app launch or signed-out state; server running | Synthetic new user handle (e.g. `UserAlpha`) with standard test credentials | 1. Open app.<br/>2. Tap register tab, fill display name and test number.<br/>3. Submit to reach inbox.<br/>4. Sign out and sign back in via login card. | Auth tokens minted, transition from splash/auth to inbox card list. |
| 2 | **Realtime Send / Receive** | `docs/media/gif/02-realtime-send-receive.gif` | 12s | Two active test sessions (User Alpha and User Beta); 1-1 chat open on screen | Fixture users Alpha and Beta connected over WebSocket | 1. Sender types generic message text.<br/>2. Tap send.<br/>3. Message bubble appears instantly with single tick.<br/>4. Status updates immediately to delivered / read upon peer receipt. | Live typing indicator, instant optimistic bubble, 1-tick to 2-tick delivery status transition. |
| 3 | **Offline Queue & Reconnect / Ticks** | `docs/media/gif/03-offline-queue-reconnect.gif` | 18s | Airplane mode toggled or network disconnected; 1-1 chat active | Fixture sender account with pending outgoing text | 1. Enter offline mode (offline banner appears).<br/>2. Send message (bubble enters queued/sending state).<br/>3. Re-enable network.<br/>4. Outbox sync worker drains queue automatically. | Offline top banner, queued bubble state, automatic outbox drain on reconnection, progression to delivered ticks. |
| 4 | **Image Send with Blur Placeholder** | `docs/media/gif/04-image-blur-placeholder.gif` | 14s | Chat screen open; test image accessible in device gallery | Fixture conversation with mock image file fixture | 1. Tap attachment button.<br/>2. Pick sample test image.<br/>3. Tap send.<br/>4. View initial progressive BlurHash thumbnail preview before full image load completes. | BlurHash placeholder rendering progressively into full crisp image thumbnail with rounded corners. |
| 5 | **Voice Message** | `docs/media/gif/05-voice-message.gif` | 14s | Chat screen open; audio recording permission granted | Fixture sender and recipient accounts | 1. Press and hold microphone button to record audio snippet.<br/>2. Release to send.<br/>3. Tap inline playback control on the resulting audio bubble. | Recording waveform / timer indicator, audio bubble insertion, playback progress bar moving smoothly. |
| 6 | **1-to-1 Voice Call (Incoming / Active / End)** | `docs/media/gif/06-call-flow.gif` | 18s | Two devices or emulators on local network; DIRECT conversation | Caller User Alpha and Callee User Beta | 1. Alpha taps voice call icon in chat header.<br/>2. Beta receives incoming full-screen / banner ringing.<br/>3. Beta accepts.<br/>4. Call connects (active call timer ticks for 3s).<br/>5. Alpha ends call. | Outgoing ringing screen, incoming ringing UI, active call timer with mute/speaker affordances, call log line written in chat. |
| 7 | **AI Conversation Summary with Consent** | `docs/media/gif/07-ai-summary-consent.gif` | 16s | Conversation with $\ge 12$ messages; AI summary consent toggle initially off | Fixture conversation history with generic multi-user discussion | 1. Open conversation overflow menu -> "Catch-up Summary".<br/>2. Prompted with consent gate.<br/>3. Enable AI consent in settings sheet.<br/>4. Tap summarize -> view bottom sheet with Overview, Key Points, and Citations. | Consent prompt sheet, summary generation loading state, rendered summary with citation pills (`↗ #seq`). |

---

## 3. Privacy & Cleanliness Checklist

Before recording or committing any demonstration GIF:

- [ ] **No Real Personal Data**: Never use real phone numbers, real full names, email addresses, or actual passwords. Use synthetic fixtures (`User Alpha`, `User Beta`, `+84900000001`, `demo-password`).
- [ ] **No Live API Tokens or Secrets**: Ensure debug overlays, logs, or inspect menus displaying JWTs, Firebase tokens, or API keys are hidden.
- [ ] **Clean System Tray / Status Bar**:
  - Set Android Demo Mode via adb:
    ```bash
    adb shell settings put global sysui_demo_allowed 1
    adb shell am broadcast -a com.android.systemui.demo -e command enter
    adb shell am broadcast -a com.android.systemui.demo -e command clock -e hhmm 0900
    adb shell am broadcast -a com.android.systemui.demo -e command battery -e level 100 -e plugged false
    adb shell am broadcast -a com.android.systemui.demo -e command network -e wifi show -e level 4
    adb shell am broadcast -a com.android.systemui.demo -e command notifications -e visible false
    ```
- [ ] **No Third-Party Notifications**: Suppress all heads-up notifications, SMS alerts, or personal chat popups from host or emulator environment.
- [ ] **File Size Verification**: Ensure the generated GIF is $\le 6\,\text{MB}$.
