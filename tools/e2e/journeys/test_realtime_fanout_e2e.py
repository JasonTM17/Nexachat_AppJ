"""Unit tests for the pure helpers in realtime_fanout_e2e.py (no network)."""

from __future__ import annotations

import importlib.util
import json
import sys
import unittest
from pathlib import Path


def _load_journey():
    path = Path(__file__).resolve().parent / "realtime_fanout_e2e.py"
    spec = importlib.util.spec_from_file_location("realtime_fanout_e2e_under_test", path)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    sys.modules["realtime_fanout_e2e_under_test"] = module
    spec.loader.exec_module(module)
    return module


mod = _load_journey()

MESSAGE_FRAME = (
    "MESSAGE\nsubscription:{sub}\ndestination:{dest}\nmessage-id:m-1\n\n"
    "{body}\x00"
)


def frame_for(sub: str, dest: str, message_id: str, cid: str, sender: str = "u") -> str:
    body = json.dumps({
        "eventType": "MESSAGE_CREATED",
        "conversationId": "conv-1",
        "payload": {"id": message_id, "clientMessageId": cid,
                    "senderId": sender, "content": "hello"},
    })
    return MESSAGE_FRAME.format(sub=sub, dest=dest, body=body)


class ParseFramesTest(unittest.TestCase):
    def test_single_message_frame(self):
        frames, rest = mod.parse_frames(frame_for("s0", "/topic/x", "id-1", "c-1"))
        self.assertEqual(rest, "")
        self.assertEqual(len(frames), 1)
        command, headers, body = frames[0]
        self.assertEqual(command, "MESSAGE")
        self.assertEqual(headers["subscription"], "s0")
        self.assertIn("MESSAGE_CREATED", body)

    def test_multiple_frames_and_heartbeats(self):
        buffer = ("\n" + frame_for("s0", "/topic/x", "id-1", "c-1")
                  + "\n" + frame_for("s1", "/user/queue/inbox", "id-2", "c-2"))
        frames, rest = mod.parse_frames(buffer)
        self.assertEqual(rest, "")
        self.assertEqual(len(frames), 2)
        self.assertEqual(frames[0][1]["subscription"], "s0")
        self.assertEqual(frames[1][1]["subscription"], "s1")

    def test_partial_frame_stays_in_remainder(self):
        complete = frame_for("s0", "/topic/x", "id-1", "c-1")
        frames, rest = mod.parse_frames(complete + "MESSAGE\nsubscription:s0\n")
        self.assertEqual(len(frames), 1)
        self.assertTrue(rest.startswith("MESSAGE"))

    def test_heartbeat_only_leaves_no_frames(self):
        frames, _rest = mod.parse_frames("\n\n\n")
        self.assertEqual(frames, [])


class MessageCreatedEnvelopeTest(unittest.TestCase):
    def _frame(self, sub="s0", body=None):
        payload = body if body is not None else {
            "eventType": "MESSAGE_CREATED",
            "conversationId": "conv-1",
            "payload": {"id": "m-1", "clientMessageId": "c-1"},
        }
        return ("MESSAGE", {"subscription": sub}, json.dumps(payload))

    def test_message_created_returns_envelope(self):
        envelope = mod.message_created_envelope(self._frame())
        self.assertEqual(envelope["payload"]["id"], "m-1")

    def test_other_commands_and_event_types_are_skipped(self):
        self.assertIsNone(mod.message_created_envelope(
            ("CONNECTED", {}, "")))
        self.assertIsNone(mod.message_created_envelope(
            self._frame(body={"eventType": "USER_ONLINE", "payload": {}})))
        self.assertIsNone(mod.message_created_envelope(
            ("MESSAGE", {}, "not json")))


class MatrixTest(unittest.TestCase):
    def _delivery(self, client, message_id, subscription="s0"):
        return {"client": client, "message_id": message_id,
                "subscription": subscription, "arrived": 1.0}

    def test_unique_deliveries_dedupes_by_client_and_id(self):
        deliveries = [
            self._delivery(0, "m-1"), self._delivery(0, "m-1"),
            self._delivery(1, "m-1"), self._delivery(0, "m-2"),
        ]
        self.assertEqual(len(mod.unique_deliveries(deliveries)), 3)
        self.assertEqual(len(mod.unique_deliveries(deliveries, "s1")), 0)

    def test_missing_deliveries_reports_matrix_gaps(self):
        deliveries = [
            self._delivery(0, "m-1"), self._delivery(0, "m-2"),
            self._delivery(1, "m-1"), self._delivery(2, "m-2"),
        ]
        missing = mod.missing_deliveries(
            deliveries, 3, {"m-1", "m-2"}, "s0")
        self.assertEqual(sorted(missing), [(1, "m-2"), (2, "m-1")])

    def test_full_matrix_has_no_missing(self):
        deliveries = [
            self._delivery(c, m)
            for c in range(3) for m in ("m-1", "m-2", "m-3")
        ]
        self.assertEqual(
            mod.missing_deliveries(deliveries, 3, {"m-1", "m-2", "m-3"}, "s0"),
            [])


class LatencyTest(unittest.TestCase):
    def test_first_arrival_only(self):
        deliveries = [
            {"client": 0, "message_id": "m-1", "arrived": 10.0},
            {"client": 0, "message_id": "m-1", "arrived": 12.0},
            {"client": 1, "message_id": "m-1", "arrived": 10.5},
        ]
        latencies = mod.first_arrival_latencies(deliveries, {"m-1": 9.0})
        self.assertEqual(sorted(latencies), [1.0, 1.5])

    def test_unknown_message_ids_are_ignored(self):
        deliveries = [{"client": 0, "message_id": "other", "arrived": 1.0}]
        self.assertEqual(mod.first_arrival_latencies(deliveries, {}), [])

    def test_percentile(self):
        self.assertIsNone(mod.percentile([], 0.5))
        self.assertEqual(mod.percentile([1.0, 2.0, 3.0, 4.0], 0.5), 2.0)
        self.assertEqual(mod.percentile([1.0, 2.0, 3.0, 4.0], 0.95), 4.0)


if __name__ == "__main__":
    unittest.main()
