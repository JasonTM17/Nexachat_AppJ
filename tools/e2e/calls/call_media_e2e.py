#!/usr/bin/env python3
"""Verify real WebRTC media between two endpoints over NexaChat call signaling.

``call_e2e.py`` proves the signaling contract with synthetic SDP/ICE payloads.
This script stands up two real WebRTC peers (aiortc ``RTCPeerConnection``),
drives them through the live INVITE -> ACCEPT -> SDP/ICE -> END flow, then
asserts that encrypted media actually flowed: ICE connectivity, RTP stats,
and decoded audio (and, for a VIDEO call, video) frames on both directions.

Modes:
    python tools/e2e/calls/call_media_e2e.py                  # both peers in one process
    python tools/e2e/calls/call_media_e2e.py --role callee    # answer side only (other machine)
    python tools/e2e/calls/call_media_e2e.py --role caller    # offer side only (other machine)

The split roles let two machines call each other: start ``--role callee`` on
one host and ``--role caller`` on another, both pointing at the same
``--base-url``. The callee waits for the next CALL_INVITE; the caller creates
the DIRECT conversation with ``--callee-phone`` and starts the call.

Needs: aiortc, av, numpy, websocket-client. Leaves call history and system
messages in the fixture conversations; run only against a disposable/local
database. Access tokens are never printed.
"""

from __future__ import annotations

import argparse
import asyncio
import fractions
import sys
import threading
import time
import uuid
from pathlib import Path

import numpy as np
from av import AudioFrame, VideoFrame

from aiortc import RTCConfiguration, RTCIceServer, RTCPeerConnection, RTCSessionDescription
from aiortc.mediastreams import AudioStreamTrack, MediaStreamError, VideoStreamTrack
from aiortc.sdp import candidate_from_sdp

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from e2e_common import (  # noqa: E402
    Client,
    E2EFailure,
    check,
    direct_conversation,
    ensure_user,
    main_guard,
)

sys.path.insert(0, str(Path(__file__).resolve().parent))
from call_e2e import (  # noqa: E402
    StompConnection,
    assert_new_call_line,
    expect_reason,
    frame_json,
    history_item,
    message_sequence,
    signal,
)

CALLER_PHONE = "+84900000121"
CALLEE_PHONE = "+84900000122"
VIDEO_CLOCK_RATE = 90000
AUDIO_SAMPLE_RATE = 48000
AUDIO_SAMPLES = 960  # 20 ms at 48 kHz


class ToneAudioTrack(AudioStreamTrack):
    """Continuous sine tone; the receiver asserts real decoded energy."""

    kind = "audio"

    def __init__(self, frequency: float):
        super().__init__()
        self._frequency = frequency
        self._phase = 0.0
        self._start = None
        self._sent = 0

    async def recv(self):
        if self.readyState != "live":
            raise MediaStreamError
        if self._start is None:
            self._start = time.monotonic()
        self._sent += AUDIO_SAMPLES
        wait = self._start + (self._sent / AUDIO_SAMPLE_RATE) - time.monotonic()
        if wait > 0:
            await asyncio.sleep(wait)
        t = self._phase + np.arange(AUDIO_SAMPLES) / AUDIO_SAMPLE_RATE
        samples = (12000 * np.sin(2 * np.pi * self._frequency * t)).astype(np.int16)
        self._phase = (self._phase + AUDIO_SAMPLES / AUDIO_SAMPLE_RATE) % 1.0
        frame = AudioFrame.from_ndarray(samples.reshape(1, -1), format="s16", layout="mono")
        frame.pts = self._sent - AUDIO_SAMPLES
        frame.sample_rate = AUDIO_SAMPLE_RATE
        frame.time_base = fractions.Fraction(1, AUDIO_SAMPLE_RATE)
        return frame


class ColorVideoTrack(VideoStreamTrack):
    """Rolling color-bar pattern; the receiver asserts decoded frames."""

    kind = "video"

    def __init__(self, fps: int = 15):
        super().__init__()
        self._fps = fps
        self._frame_index = 0

    async def recv(self):
        pts, time_base = await self.next_timestamp()
        width, height = 320, 240
        image = np.zeros((height, width, 3), dtype=np.uint8)
        # Horizontal color bars plus a moving vertical marker so frames differ.
        bar_h = height // 4
        for i, color in enumerate(((255, 0, 0), (0, 255, 0), (0, 0, 255), (255, 255, 0))):
            image[i * bar_h : (i + 1) * bar_h, :] = color
        marker_x = (self._frame_index * 8) % width
        image[:, marker_x : marker_x + 4] = (255, 255, 255)
        self._frame_index += 1
        frame = VideoFrame.from_ndarray(image, format="rgb24")
        frame.pts = pts
        frame.time_base = time_base
        await asyncio.sleep(1 / self._fps)
        return frame


class FrameRecorder:
    """Consumes a remote track and records decoded-frame metrics."""

    def __init__(self, track):
        self.track = track
        self.frames = 0
        self.audio_level = 0.0
        self.video_size = (0, 0)
        self._task = asyncio.ensure_future(self._run())

    async def _run(self):
        try:
            while True:
                frame = await self.track.recv()
                self.frames += 1
                if self.track.kind == "audio":
                    data = frame.to_ndarray()
                    if data.size:
                        self.audio_level = max(self.audio_level, float(np.abs(data).mean()))
                else:
                    self.video_size = (frame.width, frame.height)
        except MediaStreamError:
            pass


class EventRouter:
    """Routes parsed call/error envelopes to asyncio waiters by eventType.

    A single pump thread drains the blocking StompConnection; waiters register
    queues per event type, so interleaved CALL_ICE frames are never dropped.
    """

    def __init__(self, conn: StompConnection, loop: asyncio.AbstractEventLoop):
        self._conn = conn
        self._loop = loop
        self._queues: dict[str, asyncio.Queue] = {}
        self._stop = threading.Event()
        self._closed = threading.Event()
        self._pump = threading.Thread(target=self._pump, name=f"router-{conn.label}", daemon=True)
        self._pump.start()

    def _pump(self):
        try:
            while not self._stop.is_set():
                try:
                    frame = self._conn.poll_frame(0.5)
                except E2EFailure:
                    break
                if frame is None:
                    continue
                if frame[0] != "MESSAGE":
                    continue
                envelope = frame_json(frame)
                if envelope is None:
                    continue
                self._loop.call_soon_threadsafe(self._feed, envelope)
        finally:
            self._loop.call_soon_threadsafe(self._fail_all)
            self._closed.set()

    def _feed(self, envelope: dict):
        event_type = envelope.get("eventType", "ERROR" if envelope.get("code") else "")
        # Register the queue on feed, not on wait: a frame that arrives before
        # its first waiter is buffered instead of dropped.
        self._queues.setdefault(event_type, asyncio.Queue()).put_nowait(envelope)
        # Server errors must reach every pending waiter, not a queue nobody polls.
        if envelope.get("code"):
            for other_type, queue in self._queues.items():
                if other_type != event_type:
                    queue.put_nowait(envelope)

    def _fail_all(self):
        for queue in self._queues.values():
            queue.put_nowait({"code": "CONNECTION_LOST"})

    async def wait(self, event_type: str, call_id: str | None = None, timeout: float = 20.0) -> dict:
        """Next event of `event_type`; buffered events are not lost."""
        queue = self._queues.setdefault(event_type, asyncio.Queue())
        deadline = time.monotonic() + timeout
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise E2EFailure(f"timed out after {timeout:.0f}s waiting for {event_type}")
            try:
                envelope = await asyncio.wait_for(queue.get(), timeout=remaining)
            except asyncio.TimeoutError:
                raise E2EFailure(f"timed out after {timeout:.0f}s waiting for {event_type}") from None
            if envelope.get("code"):
                raise E2EFailure(f"server returned {envelope['code']} while waiting for {event_type}")
            if call_id is None or envelope.get("callId") == call_id:
                return envelope

    async def next_ice(self, timeout: float) -> dict | None:
        """Oldest buffered CALL_ICE payload, or None on timeout."""
        queue = self._queues.setdefault("CALL_ICE", asyncio.Queue())
        try:
            envelope = await asyncio.wait_for(queue.get(), timeout=timeout)
        except asyncio.TimeoutError:
            return None
        if envelope.get("code"):
            raise E2EFailure(f"server returned {envelope['code']} while draining CALL_ICE")
        return envelope

    def close(self):
        self._stop.set()
        self._closed.wait(timeout=2)


class MediaPeer:
    """One call endpoint: STOMP signaling plus a real RTCPeerConnection."""

    def __init__(self, client: Client, ws_url: str, session: dict, label: str):
        self.client = client
        self.user_id = session["user"]["id"]
        self.token = session["accessToken"]
        self.label = label
        self.conn = StompConnection(ws_url, self.token, label)
        self.router = EventRouter(self.conn, asyncio.get_event_loop())
        self.pc: RTCPeerConnection | None = None
        self.recorders: list[FrameRecorder] = []

    def send_call(self, conversation_id: str, frame: dict) -> None:
        self.conn.send_call(conversation_id, frame)

    def fetch_ice_servers(self) -> list[RTCIceServer]:
        response = self.client.get("/api/v1/calls/ice-config", token=self.token)
        check(response.status == 200, f"{self.label}: ice-config {response.status}")
        servers = []
        for entry in (response.json() or {}).get("iceServers", []):
            servers.append(RTCIceServer(urls=entry.get("urls", [])))
        return servers

    def fetch_offer(self, call_id: str) -> dict:
        response = self.client.get(f"/api/v1/calls/{call_id}/offer", token=self.token)
        check(response.status == 200, f"{self.label}: offer fetch {response.status} {response.text[:120]}")
        return response.json()

    def new_pc(self) -> RTCPeerConnection:
        self.pc = RTCPeerConnection(RTCConfiguration(iceServers=self.fetch_ice_servers()))

        @self.pc.on("track")
        def on_track(track):
            self.recorders.append(FrameRecorder(track))

        return self.pc

    def send_local_description_and_candidates(self, conversation_id: str, call_id: str) -> None:
        local = self.pc.localDescription
        self.send_call(conversation_id, signal("SDP", call_id, sdp={
            "type": local.type, "description": local.sdp,
        }))
        for candidate in local_candidates(local.sdp):
            self.send_call(conversation_id, signal("ICE", call_id, candidate=candidate))

    async def set_remote(self, sdp_payload: dict) -> None:
        await self.pc.setRemoteDescription(
            RTCSessionDescription(sdp=sdp_payload["description"], type=sdp_payload["type"])
        )

    async def gather_complete(self, timeout: float = 10.0) -> None:
        deadline = time.monotonic() + timeout
        while self.pc.iceGatheringState != "complete":
            check(time.monotonic() < deadline, f"{self.label}: ICE gathering did not finish")
            await asyncio.sleep(0.05)

    async def drain_ice_frames(self, conversation_id: str, call_id: str, until_connected) -> None:
        """Feeds relayed CALL_ICE candidates into the peer connection."""
        while not until_connected():
            envelope = await self.router.next_ice(timeout=5.0)
            if envelope is None:
                if not until_connected():
                    continue
                return
            payload = envelope.get("payload", {})
            candidate = payload.get("candidate")
            if not candidate:
                continue
            parsed = candidate_from_sdp(candidate["candidate"])
            parsed.sdpMid = candidate.get("sdpMid")
            parsed.sdpMLineIndex = candidate.get("sdpMLineIndex")
            await self.pc.addIceCandidate(parsed)

    async def wait_connected(self, timeout: float = 20.0) -> None:
        deadline = time.monotonic() + timeout
        while self.pc.connectionState not in ("connected", "completed"):
            check(time.monotonic() < deadline,
                  f"{self.label}: WebRTC did not connect (state {self.pc.connectionState})")
            check(self.pc.connectionState != "failed",
                  f"{self.label}: ICE connectivity failed")
            await asyncio.sleep(0.05)

    async def close_pc(self) -> None:
        for recorder in self.recorders:
            recorder._task.cancel()
        self.recorders.clear()
        if self.pc is not None:
            pc, self.pc = self.pc, None
            await pc.close()

    async def close(self):
        self.router.close()
        await self.close_pc()
        self.conn.close()


def local_candidates(sdp: str) -> list[dict]:
    """a=candidate lines of a local SDP as contract-shaped ICE payloads."""
    candidates = []
    mid, mline = None, -1
    for line in sdp.splitlines():
        line = line.strip()
        if line.startswith("m="):
            mline += 1
            mid = None
        elif line.startswith("a=mid:"):
            mid = line[len("a=mid:"):]
        elif line.startswith("a=candidate:"):
            candidates.append({
                "sdpMid": mid,
                "sdpMLineIndex": mline,
                "candidate": line[len("a="):],
            })
    return candidates


async def wait_for_connection(peer: MediaPeer, conversation_id: str, call_id: str, timeout: float):
    """Drives ICE-frame ingestion until the peer connection reaches connected."""
    connected = asyncio.Event()

    async def drain():
        try:
            await peer.drain_ice_frames(
                conversation_id, call_id,
                lambda: peer.pc.connectionState in ("connected", "completed", "failed"))
        finally:
            connected.set()

    drain_task = asyncio.ensure_future(drain())
    await peer.wait_connected(timeout)
    await drain_task


async def verify_media(peers: list[MediaPeer], media_type: str, seconds: float) -> dict:
    """Streams media for `seconds`, then asserts frames flowed both ways."""
    await asyncio.sleep(seconds)
    detail = {}
    for peer in peers:
        stats = await peer.pc.getStats()
        inbound = [s for s in stats.values() if s.type == "inbound-rtp"]
        packets = sum(getattr(s, "packetsReceived", 0) for s in inbound)
        check(packets > 0, f"{peer.label}: inbound RTP packetsReceived is 0")
        detail[peer.label] = {"packets": packets}
        for recorder in peer.recorders:
            if recorder.track.kind == "audio":
                check(recorder.frames >= 30,
                      f"{peer.label}: only {recorder.frames} audio frames decoded")
                check(recorder.audio_level > 100,
                      f"{peer.label}: received audio is silent (level {recorder.audio_level:.0f})")
                detail[peer.label]["audio_frames"] = recorder.frames
            elif media_type == "VIDEO" and recorder.track.kind == "video":
                check(recorder.frames >= 15,
                      f"{peer.label}: only {recorder.frames} video frames decoded")
                check(recorder.video_size == (320, 240),
                      f"{peer.label}: video size {recorder.video_size}")
                detail[peer.label]["video_frames"] = recorder.frames
        check("audio_frames" in detail[peer.label], f"{peer.label}: no audio track received")
        if media_type == "VIDEO":
            check("video_frames" in detail[peer.label], f"{peer.label}: no video track received")
    return detail


def add_media_tracks(peer: MediaPeer, media_type: str, seed: int) -> None:
    # Distinct tones so a swapped stream is audible in the metrics.
    peer.pc.addTrack(ToneAudioTrack(440.0 + 80.0 * seed))
    if media_type == "VIDEO":
        peer.pc.addTrack(ColorVideoTrack())


async def caller_call(client: Client, caller: MediaPeer, conv_id: str,
                      media_type: str, media_seconds: float, embed_offer: bool = False):
    """Full caller half of a call. Returns (call_id, prior message seq)."""
    call_id = str(uuid.uuid4())
    before = message_sequence(client, caller.token, conv_id)

    pc = caller.new_pc()
    add_media_tracks(caller, media_type, seed=0)

    invite = signal("INVITE", call_id, mediaType=media_type)
    if embed_offer:
        # Push-wake path: the offer rides inside INVITE so a callee woken by FCM
        # can fetch it via GET /calls/{id}/offer before any STOMP relay.
        offer = await pc.createOffer()
        await pc.setLocalDescription(offer)
        await caller.gather_complete()
        invite["sdp"] = {"type": "offer", "description": pc.localDescription.sdp}
    caller.send_call(conv_id, invite)
    await caller.router.wait("CALL_ACCEPTED", call_id)

    if embed_offer:
        for candidate in local_candidates(pc.localDescription.sdp):
            caller.send_call(conv_id, signal("ICE", call_id, candidate=candidate))
    else:
        offer = await pc.createOffer()
        await pc.setLocalDescription(offer)
        await caller.gather_complete()
        caller.send_local_description_and_candidates(conv_id, call_id)

    answer = await caller.router.wait("CALL_SDP", call_id, timeout=20)
    await caller.set_remote(answer["payload"]["sdp"])

    ice_task = asyncio.ensure_future(
        caller.drain_ice_frames(conv_id, call_id,
                                lambda: caller.pc.connectionState in ("connected", "completed", "failed")))
    await caller.wait_connected(25)
    await ice_task
    return call_id, before


async def callee_call(callee: MediaPeer, invite: dict, media_type: str) -> tuple[str, str]:
    """Callee half: accept, answer, verify the REST offer path. Returns ids."""
    call_id = invite["callId"]
    conv_id = invite["conversationId"]
    check(invite.get("payload", {}).get("mediaType") == media_type,
          f"CALL_INVITE mediaType was {invite.get('payload', {}).get('mediaType')}")

    invite_sdp = (invite.get("payload") or {}).get("sdp")
    if invite_sdp is not None:
        # Offer embedded in INVITE: the REST offer route must serve the same SDP.
        offered = callee.fetch_offer(call_id)
        check(offered.get("sdp", {}).get("description") == invite_sdp.get("description"),
              "REST offer differs from the INVITE-carried SDP")
        offer_payload = invite_sdp

    callee.send_call(conv_id, signal("ACCEPT", call_id))
    await callee.router.wait("CALL_ACCEPTED", call_id)

    if invite_sdp is None:
        offer_event = await callee.router.wait("CALL_SDP", call_id, timeout=20)
        offer_payload = offer_event["payload"]["sdp"]

    pc = callee.new_pc()
    add_media_tracks(callee, media_type, seed=1)
    await callee.set_remote(offer_payload)
    answer = await pc.createAnswer()
    await pc.setLocalDescription(answer)
    await callee.gather_complete()
    callee.send_local_description_and_candidates(conv_id, call_id)
    return call_id, conv_id


async def run_call_pair(client: Client, caller: MediaPeer, callee: MediaPeer,
                        conv_id: str, media_type: str, media_seconds: float,
                        embed_offer: bool = False) -> None:
    """One complete call between the two peers, media asserted in both directions."""
    callee_task = asyncio.ensure_future(
        _callee_expect_and_answer(callee, media_type))
    caller_task = asyncio.ensure_future(
        caller_call(client, caller, conv_id, media_type, media_seconds, embed_offer))
    results = await asyncio.gather(caller_task, callee_task, return_exceptions=True)
    labels = ("caller", "callee")
    errors = [f"{label}: {r}" for label, r in zip(labels, results)
              if isinstance(r, BaseException)]
    if errors:
        raise E2EFailure(" | ".join(errors))
    (call_id, before), (call_id2, conv_id2) = results
    check(call_id == call_id2 and conv_id == conv_id2, "caller/callee saw different calls")

    callee_drain = asyncio.ensure_future(
        callee.drain_ice_frames(conv_id2, call_id2,
                                lambda: callee.pc.connectionState in ("connected", "completed", "failed")))
    await callee.wait_connected(25)
    await callee_drain

    detail = await verify_media([caller, callee], media_type, media_seconds)

    caller.send_call(conv_id, signal("END", call_id))
    ended_a = await caller.router.wait("CALL_ENDED", call_id)
    ended_b = await callee.router.wait("CALL_ENDED", call_id)
    expect_reason(ended_a, "COMPLETED")
    expect_reason(ended_b, "COMPLETED")
    item = history_item(client, caller.token, conv_id, call_id)
    check(item["endReason"] == "COMPLETED", "completed call missing from history")
    check(item["durationSeconds"] >= 1, f"durationSeconds was {item['durationSeconds']}")
    if media_type == "VIDEO":
        assert_new_call_line_video(client, caller.token, conv_id, before, call_id,
                                   caller.user_id)
    else:
        assert_new_call_line(client, caller.token, conv_id, before, "COMPLETED", call_id,
                             caller.user_id)
    suffix = "|VIDEO" if media_type == "VIDEO" else ""
    print(f"[PASS] {media_type} call: WebRTC connected, media verified "
          f"(caller {detail[caller.label]}, callee {detail[callee.label]}), "
          f"COMPLETED history{suffix}")
    await asyncio.gather(caller.close_pc(), callee.close_pc())


def assert_new_call_line_video(client: Client, token: str, conv_id: str,
                               before_seq: int, call_id: str, caller_id: str) -> None:
    """Video calls carry a 5-part terminal line: CALL|COMPLETED|dur|caller|VIDEO."""
    item = history_item(client, token, conv_id, call_id)
    expected = f"CALL|COMPLETED|{item.get('durationSeconds')}|{caller_id}|VIDEO"
    response = client.get(
        f"/api/v1/conversations/{conv_id}/messages",
        token=token, params={"afterSeq": before_seq, "limit": 100})
    check(response.status == 200, "could not read messages after the video call")
    lines = [
        m for m in (response.json() or {}).get("items", [])
        if m.get("type") == "SYSTEM" and m.get("content", "").startswith("CALL|COMPLETED|")
    ]
    check(len(lines) == 1 and lines[0].get("content") == expected,
          f"expected exactly one {expected} system line, got {[l.get('content') for l in lines]}")


async def _callee_expect_and_answer(callee: MediaPeer, media_type: str):
    invite = await callee.router.wait("CALL_INVITE", timeout=30)
    return await callee_call(callee, invite, media_type)


async def run_both(client: Client, ws_url: str, media_seconds: float) -> None:
    caller_session = ensure_user(client, CALLER_PHONE, "Call E2E Caller")
    callee_session = ensure_user(client, CALLEE_PHONE, "Call E2E Callee")
    conv_id = direct_conversation(client, caller_session["accessToken"], CALLEE_PHONE)[1]["id"]

    caller = MediaPeer(client, ws_url, caller_session, "caller")
    callee = MediaPeer(client, ws_url, callee_session, "callee")
    try:
        await asyncio.sleep(0.3)
        await run_call_pair(client, caller, callee, conv_id, "AUDIO", media_seconds)
        await run_call_pair(client, caller, callee, conv_id, "VIDEO", media_seconds)
        await run_call_pair(client, caller, callee, conv_id, "AUDIO", media_seconds,
                            embed_offer=True)
        print("[PASS] AUDIO call with offer embedded in INVITE (REST offer path)")
    finally:
        await asyncio.gather(caller.close(), callee.close())


async def run_caller(client: Client, ws_url: str, callee_phone: str, media_seconds: float) -> None:
    session = ensure_user(client, CALLER_PHONE, "Call E2E Caller")
    conv_id = direct_conversation(client, session["accessToken"], callee_phone)[1]["id"]
    caller = MediaPeer(client, ws_url, session, "caller")
    try:
        await asyncio.sleep(0.3)
        call_id, before = await caller_call(client, caller, conv_id, "AUDIO", media_seconds)
        detail = await verify_media([caller], "AUDIO", media_seconds)
        caller.send_call(conv_id, signal("END", call_id))
        ended = await caller.router.wait("CALL_ENDED", call_id)
        expect_reason(ended, "COMPLETED")
        check(history_item(client, caller.token, conv_id, call_id)["endReason"] == "COMPLETED",
              "completed call missing from history")
        assert_new_call_line(client, caller.token, conv_id, before, "COMPLETED", call_id,
                             caller.user_id)
        print(f"[PASS] caller: WebRTC connected, outbound audio verified {detail}, COMPLETED")
    finally:
        await caller.close()


async def run_callee(client: Client, ws_url: str, media_seconds: float) -> None:
    session = ensure_user(client, CALLEE_PHONE, "Call E2E Callee")
    callee = MediaPeer(client, ws_url, session, "callee")
    try:
        print("[wait] callee ready; waiting for CALL_INVITE")
        invite = await callee.router.wait("CALL_INVITE", timeout=300)
        call_id, conv_id = await callee_call(callee, invite, "AUDIO")
        callee_drain = asyncio.ensure_future(
            callee.drain_ice_frames(conv_id, call_id,
                                    lambda: callee.pc.connectionState in ("connected", "completed", "failed")))
        await callee.wait_connected(25)
        await callee_drain
        detail = await verify_media([callee], "AUDIO", media_seconds)
        ended = await callee.router.wait("CALL_ENDED", call_id, timeout=30)
        expect_reason(ended, "COMPLETED")
        print(f"[PASS] callee: WebRTC connected, inbound media verified {detail}, COMPLETED")
    finally:
        await callee.close()


def main() -> int:
    parser = argparse.ArgumentParser(description="NexaChat real-media call E2E")
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--role", choices=["both", "caller", "callee"], default="both",
                        help="'both' runs two peers in this process; 'caller'/'callee' "
                             "run one side for a two-machine call")
    parser.add_argument("--callee-phone", default=CALLEE_PHONE,
                        help="callee phone for --role caller")
    parser.add_argument("--media-seconds", type=float, default=6.0,
                        help="seconds of streamed media before ending the call")
    args = parser.parse_args()

    client = Client(args.base_url)
    ws_url = args.base_url.replace("https://", "wss://").replace("http://", "ws://") + "/ws"
    print(f"== NexaChat call media E2E ({args.role}) ==")

    try:
        if args.role == "callee":
            asyncio.run(run_callee(client, ws_url, args.media_seconds))
        elif args.role == "caller":
            asyncio.run(run_caller(client, ws_url, args.callee_phone, args.media_seconds))
        else:
            asyncio.run(run_both(client, ws_url, args.media_seconds))
    except E2EFailure as failure:
        print(f"[FAIL] {failure}")
        return 1
    return 0


if __name__ == "__main__":
    main_guard(main)
