/**
 * Operational helpers: PostgreSQL advisory locks that keep {@code @Scheduled} tasks from
 * running concurrently across instances, and their stable lock ids.
 */
package com.nexachat.ops;
