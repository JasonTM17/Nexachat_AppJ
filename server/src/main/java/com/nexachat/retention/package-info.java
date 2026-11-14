/**
 * Bounded data-retention cleanup: {@code RetentionJobRunner} runs one skip-locked batch
 * per enabled job (expired tokens, invites, tombstones, caches, attachments, polls and
 * more). Off by default and dry-run by default via {@code nexachat.retention.*}.
 */
package com.nexachat.retention;
