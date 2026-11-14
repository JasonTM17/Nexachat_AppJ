/**
 * Group invite links: hashed tokens, link create/rotate/revoke and join-request
 * approval for admins ({@code GroupInviteController}), safe previews and joins for
 * authenticated users ({@code InviteResolveController}), bans for removed members, and
 * per-user/per-IP rate limits. {@code InviteService} owns the rules.
 */
package com.nexachat.invite;
