/**
 * Chat streaks: the per-conversation "both of us wrote today" chain, the
 * app-wide check-in streak, and the opt-in daily reminder. The rules live in
 * the dependency-free {@link com.nexachat.streak.StreakRules}; persistence and
 * the message-send hook are in {@link com.nexachat.streak.StreakService}.
 */
package com.nexachat.streak;
