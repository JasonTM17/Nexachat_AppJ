"""Offline unit tests for call E2E diagnostics; no live backend is required."""

import json
import time
import unittest
from collections import deque
from unittest.mock import patch

import call_e2e


class CallE2EDiagnosticsTest(unittest.TestCase):
    def test_connect_advertises_and_background_sender_keeps_idle_socket_active(self):
        class IdleSocket:
            def __init__(self):
                self.sent = []
                self.read_connected = False
                self.read_heartbeat = False

            def settimeout(self, _timeout):
                pass

            def send(self, frame):
                self.sent.append(frame)

            def close(self):
                pass

            def recv(self):
                if not self.read_connected:
                    self.read_connected = True
                    return "CONNECTED\nversion:1.2\nheart-beat:10000,10000\n\n\x00"
                if not self.read_heartbeat:
                    self.read_heartbeat = True
                    return "\n"
                raise call_e2e.websocket.WebSocketTimeoutException()

        socket = IdleSocket()
        with patch.object(call_e2e, "HEARTBEAT_INTERVAL_SECONDS", 0.01):
            with patch.object(call_e2e.websocket, "create_connection", return_value=socket):
                connection = call_e2e.StompConnection("ws://localhost/ws", "token", "test")
            time.sleep(0.1)

        self.assertIn("heart-beat:10000,10000", socket.sent[0])
        self.assertGreaterEqual(socket.sent.count("\n"), 2)
        connection._receive(timeout=0.1)
        self.assertEqual(connection._inbound_heartbeats, 1)
        connection.close()

    def test_timeout_reports_unmatched_call_event_summary(self):
        connection = call_e2e.StompConnection.__new__(call_e2e.StompConnection)
        connection.label = "caller"
        connection._frames = deque([(
            "MESSAGE",
            {},
            json.dumps({"eventType": "CALL_ENDED", "callId": "other-call"}),
        )])
        connection._observed_frames = deque(maxlen=20)

        with patch.object(connection, "_receive"):
            with self.assertRaises(call_e2e.E2EFailure) as raised:
                connection.wait_event("CALL_TIMEOUT", "expected-call", timeout=0)

        self.assertIn("CALL_ENDED", str(raised.exception))
        self.assertIn("other-call", str(raised.exception))


if __name__ == "__main__":
    unittest.main()
