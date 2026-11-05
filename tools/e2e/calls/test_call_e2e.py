import json
import unittest
from collections import deque
from unittest.mock import patch

import call_e2e


class CallE2EDiagnosticsTest(unittest.TestCase):
    def test_connect_advertises_heartbeat_and_idle_receive_sends_one(self):
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
                    return "CONNECTED\nversion:1.2\nheart-beat:0,0\n\n\x00"
                if not self.read_heartbeat:
                    self.read_heartbeat = True
                    return "\n"
                raise call_e2e.websocket.WebSocketTimeoutException()

        socket = IdleSocket()
        with patch.object(call_e2e.websocket, "create_connection", return_value=socket):
            connection = call_e2e.StompConnection("ws://localhost/ws", "token", "test")

        self.assertIn("heart-beat:10000,10000", socket.sent[0])
        connection._next_heartbeat_at = 0
        connection._receive(timeout=0.1)
        self.assertIn("\n", socket.sent)
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
