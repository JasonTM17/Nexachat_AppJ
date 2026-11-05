package com.nexachat.call.ui;

/** Small state-to-view contract so the call screen stays rotation-safe. */
public final class CallUiModel {
    private final int titleRes;
    private final int subtitleRes;
    private final int hangUpLabelRes;
    private final boolean showTimer;
    private final boolean showMute;
    private final boolean showSpeaker;
    private final boolean showHangUp;

    CallUiModel(
            int titleRes,
            int subtitleRes,
            int hangUpLabelRes,
            boolean showTimer,
            boolean showMute,
            boolean showSpeaker,
            boolean showHangUp) {
        this.titleRes = titleRes;
        this.subtitleRes = subtitleRes;
        this.hangUpLabelRes = hangUpLabelRes;
        this.showTimer = showTimer;
        this.showMute = showMute;
        this.showSpeaker = showSpeaker;
        this.showHangUp = showHangUp;
    }

    public int getTitleRes() { return titleRes; }
    public int getSubtitleRes() { return subtitleRes; }
    public int getHangUpLabelRes() { return hangUpLabelRes; }
    public boolean isShowTimer() { return showTimer; }
    public boolean isShowMute() { return showMute; }
    public boolean isShowSpeaker() { return showSpeaker; }
    public boolean isShowHangUp() { return showHangUp; }
}
