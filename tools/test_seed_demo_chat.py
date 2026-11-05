#!/usr/bin/env python3
"""Offline checks for repeat-safe demo seeding helpers."""

import unittest
import urllib.error
import uuid
from unittest.mock import call, patch

import seed_demo_chat


class SeedDemoChatTest(unittest.TestCase):
    @patch.object(seed_demo_chat, "request_json", return_value={"id": "message-id"})
    def test_seed_message_reuses_a_stable_idempotency_key(self, request):
        seed_demo_chat.send_seed_msg("token", "conversation-id", "direct-1", "hello")
        seed_demo_chat.send_seed_msg("token", "conversation-id", "direct-1", "hello")

        first = request.call_args_list[0].kwargs["data"]["clientMessageId"]
        second = request.call_args_list[1].kwargs["data"]["clientMessageId"]
        expected = str(uuid.uuid5(uuid.NAMESPACE_URL, "nexachat-demo:conversation-id:direct-1"))
        self.assertEqual(first, expected)
        self.assertEqual(second, expected)

    @patch.object(
        seed_demo_chat,
        "request_json",
        return_value={"items": [{"id": "message-id", "reactions": [{"emoji": "👍", "byMe": True}]}]},
    )
    def test_reaction_seed_does_not_toggle_an_existing_reaction(self, request):
        seed_demo_chat.react_msg("token", "conversation-id", "message-id", "👍")

        request.assert_called_once_with(
            f"{seed_demo_chat.BASE_URL}/conversations/conversation-id/messages?limit=100",
            method="GET",
            token="token",
        )

    @patch.object(seed_demo_chat, "request_json", return_value={
        "items": [{"id": "message-id", "reactions": []}],
    })
    def test_reaction_seed_toggles_when_the_reaction_is_absent(self, request):
        seed_demo_chat.react_msg("token", "conversation-id", "message-id", "👍")

        self.assertEqual(request.call_args_list, [
            call(
                f"{seed_demo_chat.BASE_URL}/conversations/conversation-id/messages?limit=100",
                method="GET",
                token="token",
            ),
            call(
                f"{seed_demo_chat.BASE_URL}/messages/message-id/reactions",
                method="POST",
                data={"emoji": "👍"},
                token="token",
            ),
        ])

    @patch.object(seed_demo_chat, "request_json", return_value=[
        {"id": "existing-group", "type": "GROUP", "title": "Demo group"},
    ])
    def test_group_seed_reuses_an_existing_title(self, request):
        group_id = seed_demo_chat.create_or_get_group("token", "Demo group", [])

        self.assertEqual(group_id, "existing-group")
        request.assert_called_once_with(
            f"{seed_demo_chat.BASE_URL}/conversations",
            method="GET",
            token="token",
        )

    @patch.object(seed_demo_chat, "request_json")
    def test_direct_fallback_matches_the_requested_peer(self, request):
        peer_id = "target-peer"
        request.side_effect = [
            urllib.error.HTTPError("/conversations/direct", 409, "conflict", {}, None),
            [
                {"id": "other-direct", "type": "DIRECT", "members": [{"userId": "other-peer"}]},
                {"id": "target-direct", "type": "DIRECT", "members": [{"userId": peer_id}]},
            ],
        ]

        conversation_id = seed_demo_chat.create_or_get_direct("token", peer_id)

        self.assertEqual(conversation_id, "target-direct")

if __name__ == "__main__":
    unittest.main()
