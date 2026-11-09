/**
 * Group polls: creation, voting, close/delete and the versioned change feed.
 * Polls are member-only and exist only in GROUP conversations; all reads and
 * writes go through {@link com.nexachat.poll.PollService}, which reuses the
 * conversation membership primitives instead of duplicating authorization.
 */
package com.nexachat.poll;
