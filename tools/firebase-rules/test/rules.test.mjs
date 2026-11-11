/**
 * Offline rules contract matrix for the NexaChat Firebase Realtime Database
 * candidate (tools/firebase-rules/database.rules.json).
 *
 * The suite runs against the local Realtime Database emulator with the demo
 * project id `demo-nexachat-presence`. It needs no cloud credentials and no
 * network once the emulator JAR and npm dependencies are cached.
 *
 * Every authorization mirror (control, accounts, sessions, members,
 * presenceReaders, blocked) is seeded through withSecurityRulesDisabled(),
 * which is the test stand-in for the privileged server projection writer.
 *
 * Tests marked "KNOWN GAP" assert the behaviour of the reviewed candidate
 * rules, not the desired end state. They exist so a future rules change is
 * visible; see reports/r4-322-w6.md for the findings.
 */
import { after, before, beforeEach, describe, test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { assertFails, assertSucceeds } from '@firebase/rules-unit-testing';
import { baseFixture, createEnvironment, LEASE_MS, SERVER_TIMESTAMP } from './helpers.mjs';

let env;
let leaseUntil;

before(async () => {
  env = await createEnvironment();
});

after(async () => {
  await env.cleanup();
});

beforeEach(async () => {
  await env.clearDatabase();
  const fixture = baseFixture();
  leaseUntil = fixture.sessions.alice['alice-a1'].until;
  await env.withSecurityRulesDisabled(async (context) => {
    await context.database().ref().set(fixture);
  });
});

function client(uid, bridgeId) {
  return env.authenticatedContext(uid, bridgeId === undefined ? {} : { bridgeId });
}

function database(uid, bridgeId) {
  return client(uid, bridgeId).database();
}

function reference(uid, bridgeId, path) {
  const db = database(uid, bridgeId);
  return path === '' ? db.ref() : db.ref(path);
}

function read(uid, bridgeId, path) {
  return reference(uid, bridgeId, path).once('value');
}

function write(uid, bridgeId, path, value) {
  return reference(uid, bridgeId, path).set(value);
}

function remove(uid, bridgeId, path) {
  return reference(uid, bridgeId, path).remove();
}

function anonymousDatabase() {
  return env.unauthenticatedContext().database();
}

async function adminSet(path, value) {
  await env.withSecurityRulesDisabled(async (context) => {
    await context.database().ref(path).set(value);
  });
}

async function adminRemove(path) {
  await env.withSecurityRulesDisabled(async (context) => {
    await context.database().ref(path).remove();
  });
}

function onlinePresence(until = leaseUntil) {
  return { online: true, lastSeen: 0, updatedAt: SERVER_TIMESTAMP, leaseUntil: until };
}

function offlinePresence(until = leaseUntil) {
  return { online: false, lastSeen: SERVER_TIMESTAMP, updatedAt: SERVER_TIMESTAMP, leaseUntil: until };
}

function typingPayload(overrides = {}) {
  return {
    bridgeId: 'bob-b1',
    updatedAt: SERVER_TIMESTAMP,
    expiresAt: Date.now() + 4_500,
    ...overrides,
  };
}

describe('unauthenticated and unlisted paths', () => {
  const guardedPaths = [
    'control/enabled',
    'accounts/bob/active',
    'sessions/bob/bob-b1/until',
    'members/direct-ab/bob',
    'presenceReaders/bob/alice',
    'blocked/bob/alice',
    'presence/bob',
    'typing/direct-ab/bob',
    'calls/call-ab',
  ];

  test('anonymous clients cannot read any authorization or ephemeral path', async () => {
    const db = anonymousDatabase();
    for (const path of guardedPaths) {
      await assertFails(db.ref(path).once('value'), `expected anonymous read denial at ${path}`);
    }
  });

  test('anonymous clients cannot write presence, typing or calls', async () => {
    const db = anonymousDatabase();
    await assertFails(db.ref('presence/alice/alice-a1').set(onlinePresence()));
    await assertFails(db.ref('typing/direct-ab/alice').set(typingPayload()));
    await assertFails(db.ref('calls/call-anon').set({ cid: 'direct-ab' }));
    await assertFails(db.ref('control/enabled').set(false));
  });

  test('anonymous clients cannot read the root or any parent collection', async () => {
    const db = anonymousDatabase();
    for (const path of ['', 'presence', 'typing', 'typing/direct-ab', 'calls', 'accounts', 'members']) {
      const target = path === '' ? db.ref() : db.ref(path);
      await assertFails(target.once('value'), `expected anonymous parent read denial at /${path}`);
    }
  });

  test('authenticated clients cannot read server-only mirrors', async () => {
    for (const path of [
      'control',
      'control/enabled',
      'accounts',
      'accounts/bob',
      'sessions/bob/bob-b1',
      'members/direct-ab',
      'presenceReaders/bob',
      'blocked',
    ]) {
      await assertFails(read('alice', 'alice-a1', path), `expected mirror read denial at ${path}`);
    }
  });

  test('authenticated clients cannot write or delete server-only mirrors', async () => {
    await assertFails(write('alice', 'alice-a1', 'control/enabled', false));
    await assertFails(write('alice', 'alice-a1', 'accounts/alice/active', true));
    await assertFails(write('alice', 'alice-a1', 'sessions/alice/alice-a1/until', Date.now() + LEASE_MS));
    await assertFails(write('alice', 'alice-a1', 'members/direct-ab/alice', Date.now() + LEASE_MS));
    await assertFails(write('alice', 'alice-a1', 'members/group-abc/dave', Date.now() + LEASE_MS));
    await assertFails(write('alice', 'alice-a1', 'presenceReaders/bob/alice', 'group-abc'));
    await assertFails(write('alice', 'alice-a1', 'blocked/alice/bob', true));
    await assertFails(remove('alice', 'alice-a1', 'members/direct-ab/bob'));
    await assertFails(remove('alice', 'alice-a1', 'accounts/bob'));
  });

  test('authenticated clients cannot read parent collections of granted leaves', async () => {
    for (const path of ['', 'presence', 'typing', 'typing/direct-ab', 'calls']) {
      await assertFails(read('alice', 'alice-a1', path), `expected parent read denial at /${path}`);
    }
  });

  test('unknown top-level paths stay denied', async () => {
    await assertFails(read('alice', 'alice-a1', 'messages/abc'));
    await assertFails(write('alice', 'alice-a1', 'messages/abc', 'hello'));
    await assertFails(read('alice', 'alice-a1', 'presencex/alice'));
    await assertFails(write('alice', 'alice-a1', 'presencex/alice', true));
  });

  test('a client cannot write a whole conversation collection', async () => {
    await assertFails(write('alice', 'alice-a1', 'typing/direct-ab', { alice: typingPayload({ bridgeId: 'alice-a1' }) }));
    await assertFails(write('alice', 'alice-a1', 'calls', { 'call-new': { cid: 'direct-ab' } }));
  });
});

describe('presence', () => {
  test('owner may publish and read an online record within the session lease', async () => {
    await assertSucceeds(write('alice', 'alice-a1', 'presence/alice/alice-a1', onlinePresence()));
    const snapshot = await assertSucceeds(read('alice', 'alice-a1', 'presence/alice'));
    assert.equal(snapshot.val()['alice-a1'].online, true);
  });

  test('owner may read own presence even when showPresence is false', async () => {
    await adminSet('accounts/alice/showPresence', false);
    await assertSucceeds(read('alice', 'alice-a1', 'presence/alice'));
  });

  test('a DIRECT peer with a reader edge may read presence without a contacts mirror', async () => {
    const snapshot = await assertSucceeds(read('alice', 'alice-a1', 'presence/bob'));
    assert.equal(snapshot.val()['bob-b1'].online, true);
  });

  test('denies a peer when the subject hides presence', async () => {
    await adminSet('accounts/bob/showPresence', false);
    await assertFails(read('alice', 'alice-a1', 'presence/bob'));
  });

  test('denies a peer when the subject account is inactive', async () => {
    await adminSet('accounts/bob/active', false);
    await assertFails(read('alice', 'alice-a1', 'presence/bob'));
  });

  test('denies a peer when the viewer account is inactive', async () => {
    await adminSet('accounts/alice/active', false);
    await assertFails(read('alice', 'alice-a1', 'presence/bob'));
  });

  test('denies when either account mirror is missing', async () => {
    await adminRemove('accounts/bob');
    await assertFails(read('alice', 'alice-a1', 'presence/bob'));
    await adminSet('accounts/bob', { active: true, showPresence: true });
    await adminRemove('accounts/alice');
    await assertFails(read('alice', 'alice-a1', 'presence/bob'));
  });

  test('denies when either direction is blocked', async () => {
    await adminSet('blocked/alice/bob', true);
    await assertFails(read('alice', 'alice-a1', 'presence/bob'));
    await adminSet('blocked/alice/bob', false);
    await adminSet('blocked/bob/alice', true);
    await assertFails(read('alice', 'alice-a1', 'presence/bob'));
  });

  test('denies a group-only peer because no reader edge exists', async () => {
    await assertFails(read('carol', 'carol-c1', 'presence/bob'));
  });

  test('denies when the subject left the direct conversation', async () => {
    await adminRemove('members/direct-ab/bob');
    await assertFails(read('alice', 'alice-a1', 'presence/bob'));
  });

  test('denies when the viewer left the direct conversation', async () => {
    await adminRemove('members/direct-ab/alice');
    await assertFails(read('alice', 'alice-a1', 'presence/bob'));
  });

  test('denies when either membership lease expired or is not numeric', async () => {
    await adminSet('members/direct-ab/bob', Date.now() - 1_000);
    await assertFails(read('alice', 'alice-a1', 'presence/bob'));
    await adminSet('members/direct-ab/bob', String(Date.now() + LEASE_MS));
    await assertFails(read('alice', 'alice-a1', 'presence/bob'));
  });

  test('denies when the reader edge is not a string', async () => {
    await adminSet('presenceReaders/bob/alice', { cid: 'direct-ab' });
    await assertFails(read('alice', 'alice-a1', 'presence/bob'));
  });

  test('denies when the reader edge points at a conversation without the subject', async () => {
    await adminSet('presenceReaders/bob/alice', 'direct-bc');
    await assertFails(read('alice', 'alice-a1', 'presence/bob'));
  });

  test('denies missing, expired or non-numeric bridge sessions', async () => {
    await adminRemove('sessions/alice/alice-a1');
    await assertFails(read('alice', 'alice-a1', 'presence/bob'));
    await adminSet('sessions/alice/alice-a1', { until: Date.now() - 1_000 });
    await assertFails(read('alice', 'alice-a1', 'presence/bob'));
    await adminSet('sessions/alice/alice-a1', { until: String(Date.now() + LEASE_MS) });
    await assertFails(read('alice', 'alice-a1', 'presence/bob'));
  });

  test('denies a missing bridge claim', async () => {
    await assertFails(read('alice', undefined, 'presence/bob'));
    await assertFails(read('alice', undefined, 'presence/alice'));
  });

  test('denies a foreign bridge claim', async () => {
    await assertFails(read('alice', 'bob-b1', 'presence/bob'));
    await assertFails(write('alice', 'bob-b1', 'presence/alice/bob-b1', onlinePresence()));
  });

  test('control flag gates every path', async () => {
    await adminSet('control/enabled', false);
    await assertFails(read('alice', 'alice-a1', 'presence/bob'));
    await assertFails(write('alice', 'alice-a1', 'presence/alice/alice-a1', onlinePresence()));
    await assertFails(read('alice', 'alice-a1', 'typing/direct-ab/bob'));
    await assertFails(read('alice', 'alice-a1', 'calls/call-ab'));
    await adminSet('control/enabled', 'true');
    await assertFails(read('alice', 'alice-a1', 'presence/bob'));
    await adminRemove('control/enabled');
    await assertFails(read('alice', 'alice-a1', 'presence/bob'));
  });

  test('denies an online lease beyond the session or already expired', async () => {
    await assertFails(write('alice', 'alice-a1', 'presence/alice/alice-a1', onlinePresence(leaseUntil + 1)));
    await assertFails(write('alice', 'alice-a1', 'presence/alice/alice-a1', onlinePresence(Date.now() - 1_000)));
  });

  test('denies an online payload with a non-zero lastSeen', async () => {
    const payload = { ...onlinePresence(), lastSeen: 1 };
    await assertFails(write('alice', 'alice-a1', 'presence/alice/alice-a1', payload));
  });

  test('denies a forged updatedAt', async () => {
    const payload = { ...onlinePresence(), updatedAt: Date.now() - 60_000 };
    await assertFails(write('alice', 'alice-a1', 'presence/alice/alice-a1', payload));
  });

  test('allows the disconnect payload with a server lastSeen', async () => {
    await assertSucceeds(write('alice', 'alice-a1', 'presence/alice/alice-a1', offlinePresence()));
  });

  test('denies a disconnect payload with a forged lastSeen', async () => {
    const payload = { ...offlinePresence(), lastSeen: Date.now() - 60_000 };
    await assertFails(write('alice', 'alice-a1', 'presence/alice/alice-a1', payload));
  });

  test('denies unknown fields and partial writes', async () => {
    await assertFails(write('alice', 'alice-a1', 'presence/alice/alice-a1', { ...onlinePresence(), extra: true }));
    await assertFails(write('alice', 'alice-a1', 'presence/alice/alice-a1/online', true));
    await assertFails(write('alice', 'alice-a1', 'presence/alice/alice-a1', { online: true }));
  });

  test('allows the owner to delete the own node but denies another user', async () => {
    await assertSucceeds(remove('alice', 'alice-a1', 'presence/alice/alice-a1'));
    await assertFails(remove('alice', 'alice-a1', 'presence/bob/bob-b1'));
    await adminRemove('sessions/alice/alice-a1');
    await assertFails(remove('alice', 'alice-a1', 'presence/alice/alice-a2'));
  });

  test('denies writing another user presence or a foreign bridge under the own uid', async () => {
    await assertFails(write('alice', 'alice-a1', 'presence/bob/bob-b1', onlinePresence()));
    await assertFails(write('alice', 'alice-a1', 'presence/alice/bob-b1', onlinePresence()));
  });

  test('denies an inactive owner or a revoked session', async () => {
    await adminSet('accounts/alice/active', false);
    await assertFails(write('alice', 'alice-a1', 'presence/alice/alice-a1', onlinePresence()));
    await adminSet('accounts/alice/active', true);
    await adminRemove('sessions/alice/alice-a1');
    await assertFails(write('alice', 'alice-a1', 'presence/alice/alice-a1', offlinePresence()));
  });

  test('keeps per-device records independent', async () => {
    await assertSucceeds(write('alice', 'alice-a1', 'presence/alice/alice-a1', onlinePresence()));
    await assertSucceeds(write('alice', 'alice-a2', 'presence/alice/alice-a2', onlinePresence()));
    await assertSucceeds(write('alice', 'alice-a1', 'presence/alice/alice-a1', offlinePresence()));

    const snapshot = await assertSucceeds(read('alice', 'alice-a1', 'presence/alice'));
    const value = snapshot.val();
    assert.equal(value['alice-a1'].online, false);
    assert.equal(value['alice-a2'].online, true);
  });

  test('KNOWN GAP: a permitted viewer can read an offline device lastSeen while another device is online', async () => {
    await adminSet('presence/bob/bob-b2', {
      online: false,
      lastSeen: Date.now() - 30_000,
      updatedAt: Date.now() - 30_000,
      leaseUntil: leaseUntil,
    });
    const snapshot = await assertSucceeds(read('alice', 'alice-a1', 'presence/bob'));
    const value = snapshot.val();
    assert.equal(value['bob-b1'].online, true);
    assert.equal(typeof value['bob-b2'].lastSeen, 'number');
  });

  test('KNOWN GAP: rules cannot tell a DIRECT reader edge from a group one', async () => {
    await adminSet('presenceReaders/bob/carol', 'group-abc');
    await assertSucceeds(read('carol', 'carol-c1', 'presence/bob'));
  });

  test('cancels a live listener when the reader grant is revoked', async () => {
    const db = database('alice', 'alice-a1');
    const presence = db.ref('presence/bob');
    let cancelListener;
    const cancelled = new Promise((resolve) => {
      cancelListener = resolve;
    });

    await new Promise((resolve, reject) => {
      presence.on('value', (snapshot) => {
        if (snapshot.exists()) resolve(snapshot.val());
      }, (error) => {
        cancelListener(error);
        reject(error);
      });
    });

    await adminRemove('members/direct-ab/alice');
    await adminSet('presence/bob/bob-b1/updatedAt', Date.now());

    const error = await Promise.race([
      cancelled,
      new Promise((resolve) => setTimeout(() => resolve(null), 5_000)),
    ]);
    assert.ok(error, 'expected the emulator to cancel the listener after the grant was revoked');
    assert.match(String(error.code ?? error.message), /PERMISSION_DENIED|permission/i);
    presence.off();
  });
});

describe('typing', () => {
  test('owner may publish typing after the previous record is stale', async () => {
    await assertSucceeds(write('bob', 'bob-b1', 'typing/direct-ab/bob', typingPayload()));
  });

  test('owner may publish a first typing record', async () => {
    await adminRemove('typing/direct-ab/bob');
    await assertSucceeds(write('bob', 'bob-b1', 'typing/direct-ab/bob', typingPayload()));
  });

  test('denies a second typing write within three seconds', async () => {
    await adminSet('typing/direct-ab/bob', {
      bridgeId: 'bob-b1',
      updatedAt: Date.now() - 1_000,
      expiresAt: Date.now() + 4_500,
    });
    await assertFails(write('bob', 'bob-b1', 'typing/direct-ab/bob', typingPayload()));
  });

  test('denies an excessive or already expired TTL', async () => {
    await assertFails(write('bob', 'bob-b1', 'typing/direct-ab/bob', typingPayload({ expiresAt: Date.now() + 6_000 })));
    await assertFails(write('bob', 'bob-b1', 'typing/direct-ab/bob', typingPayload({ expiresAt: Date.now() - 1_000 })));
    await assertFails(write('bob', 'bob-b1', 'typing/direct-ab/bob', typingPayload({ expiresAt: String(Date.now() + 4_000) })));
  });

  test('denies a bridge mismatch, forged timestamp, missing field or extra field', async () => {
    await assertFails(write('bob', 'bob-b1', 'typing/direct-ab/bob', typingPayload({ bridgeId: 'bob-b2' })));
    await assertFails(write('bob', 'bob-b1', 'typing/direct-ab/bob', typingPayload({ updatedAt: Date.now() - 60_000 })));
    await assertFails(write('bob', 'bob-b1', 'typing/direct-ab/bob', { bridgeId: 'bob-b1', updatedAt: SERVER_TIMESTAMP }));
    await assertFails(write('bob', 'bob-b1', 'typing/direct-ab/bob', typingPayload({ extra: true })));
  });

  test('denies a non-member, a cross-uid write and an expired membership', async () => {
    await assertFails(write('dave', 'dave-d1', 'typing/direct-ab/dave', typingPayload({ bridgeId: 'dave-d1' })));
    await assertFails(write('alice', 'alice-a1', 'typing/direct-ab/bob', typingPayload()));
    await adminSet('members/direct-ab/bob', Date.now() - 1_000);
    await assertFails(write('bob', 'bob-b1', 'typing/direct-ab/bob', typingPayload()));
  });

  test('denies writes for an inactive account or a disabled control flag', async () => {
    await adminSet('accounts/bob/active', false);
    await assertFails(write('bob', 'bob-b1', 'typing/direct-ab/bob', typingPayload()));
    await adminSet('accounts/bob/active', true);
    await adminSet('control/enabled', false);
    await assertFails(write('bob', 'bob-b1', 'typing/direct-ab/bob', typingPayload()));
  });

  test('owner may delete only the own bridge record', async () => {
    await assertSucceeds(remove('bob', 'bob-b1', 'typing/direct-ab/bob'));
    await adminSet('typing/direct-ab/bob', {
      bridgeId: 'bob-b2',
      updatedAt: Date.now() - 4_000,
      expiresAt: Date.now() + 4_500,
    });
    await assertFails(remove('bob', 'bob-b1', 'typing/direct-ab/bob'));
    await assertFails(remove('alice', 'alice-a1', 'typing/direct-ab/bob'));
  });

  test('allows a no-op delete of an absent leaf', async () => {
    await adminRemove('typing/direct-ab/bob');
    await assertSucceeds(remove('bob', 'bob-b1', 'typing/direct-ab/bob'));
  });

  test('KNOWN GAP: delete then recreate bypasses the three second throttle', async () => {
    await adminSet('typing/direct-ab/bob', {
      bridgeId: 'bob-b1',
      updatedAt: Date.now() - 1_000,
      expiresAt: Date.now() + 4_500,
    });
    await assertSucceeds(remove('bob', 'bob-b1', 'typing/direct-ab/bob'));
    await assertSucceeds(write('bob', 'bob-b1', 'typing/direct-ab/bob', typingPayload()));
  });

  test('a member may read an unexpired typing leaf and an absent one', async () => {
    const snapshot = await assertSucceeds(read('alice', 'alice-a1', 'typing/direct-ab/bob'));
    assert.equal(snapshot.val().bridgeId, 'bob-b1');
    await assertSucceeds(read('alice', 'alice-a1', 'typing/direct-ab/alice'));
  });

  test('denies an expired typing leaf', async () => {
    await adminSet('typing/direct-ab/bob', {
      bridgeId: 'bob-b1',
      updatedAt: Date.now() - 10_000,
      expiresAt: Date.now() - 1_000,
    });
    await assertFails(read('alice', 'alice-a1', 'typing/direct-ab/bob'));
  });

  test('denies reads for a non-member, expired memberships, inactive author or a block', async () => {
    await assertFails(read('dave', 'dave-d1', 'typing/direct-ab/bob'));
    await adminSet('members/direct-ab/bob', Date.now() - 1_000);
    await assertFails(read('alice', 'alice-a1', 'typing/direct-ab/bob'));
    await adminSet('members/direct-ab/bob', Date.now() + LEASE_MS);
    await adminSet('members/direct-ab/alice', Date.now() - 1_000);
    await assertFails(read('alice', 'alice-a1', 'typing/direct-ab/bob'));
    await adminSet('members/direct-ab/alice', Date.now() + LEASE_MS);
    await adminSet('accounts/bob/active', false);
    await assertFails(read('alice', 'alice-a1', 'typing/direct-ab/bob'));
    await adminSet('accounts/bob/active', true);
    await adminSet('blocked/bob/alice', true);
    await assertFails(read('alice', 'alice-a1', 'typing/direct-ab/bob'));
  });

  test('group typing ignores showPresence', async () => {
    await adminSet('accounts/bob/showPresence', false);
    await adminSet('typing/group-abc/bob', {
      bridgeId: 'bob-b1',
      updatedAt: Date.now() - 1_000,
      expiresAt: Date.now() + 4_500,
    });
    await assertSucceeds(read('carol', 'carol-c1', 'typing/group-abc/bob'));
  });

  test('owner may read the own typing leaf', async () => {
    await assertSucceeds(read('bob', 'bob-b1', 'typing/direct-ab/bob'));
  });
});

describe('calls', () => {
  test('either participant may read an unexpired projection and its fields', async () => {
    const asCaller = await assertSucceeds(read('alice', 'alice-a1', 'calls/call-ab'));
    assert.equal(asCaller.val().state, 'RINGING');
    await assertSucceeds(read('bob', 'bob-b1', 'calls/call-ab'));
    await assertSucceeds(read('bob', 'bob-b1', 'calls/call-ab/state'));
  });

  test('denies a member who is not a participant and a non-member participant', async () => {
    await assertFails(read('carol', 'carol-c1', 'calls/call-ab'));
    await adminSet('calls/call-bd', {
      cid: 'direct-ab',
      callerId: 'alice',
      calleeId: 'dave',
      state: 'RINGING',
      updatedAt: Date.now(),
      expiresAt: Date.now() + 40_000,
    });
    await assertFails(read('dave', 'dave-d1', 'calls/call-bd'));
    await assertFails(read('alice', 'alice-a1', 'calls/call-bd'));
  });

  test('denies a viewer who is not a member of the projected conversation', async () => {
    await adminSet('calls/call-bc', {
      cid: 'direct-bc',
      callerId: 'bob',
      calleeId: 'carol',
      state: 'ACTIVE',
      updatedAt: Date.now(),
      expiresAt: Date.now() + 40_000,
    });
    await assertFails(read('alice', 'alice-a1', 'calls/call-bc'));
    await assertSucceeds(read('bob', 'bob-b1', 'calls/call-bc'));
  });

  test('denies expired, missing or non-numeric expiry', async () => {
    await adminSet('calls/call-ab/expiresAt', Date.now() - 1_000);
    await assertFails(read('alice', 'alice-a1', 'calls/call-ab'));
    await adminRemove('calls/call-ab/expiresAt');
    await assertFails(read('alice', 'alice-a1', 'calls/call-ab'));
    await adminSet('calls/call-ab/expiresAt', String(Date.now() + 40_000));
    await assertFails(read('alice', 'alice-a1', 'calls/call-ab'));
  });

  test('denies blocked or inactive participants', async () => {
    await adminSet('blocked/alice/bob', true);
    await assertFails(read('alice', 'alice-a1', 'calls/call-ab'));
    await adminSet('blocked/alice/bob', false);
    await adminSet('accounts/bob/active', false);
    await assertFails(read('alice', 'alice-a1', 'calls/call-ab'));
    await adminSet('accounts/bob/active', true);
    await adminSet('accounts/alice/active', false);
    await assertFails(read('alice', 'alice-a1', 'calls/call-ab'));
  });

  test('denies a projection whose participant is not a member', async () => {
    await adminRemove('members/direct-ab/bob');
    await assertFails(read('alice', 'alice-a1', 'calls/call-ab'));
  });

  test('denies every client write to calls', async () => {
    await assertFails(write('alice', 'alice-a1', 'calls/call-new', {
      cid: 'direct-ab',
      callerId: 'alice',
      calleeId: 'bob',
      state: 'RINGING',
      updatedAt: Date.now(),
      expiresAt: Date.now() + 40_000,
    }));
    await assertFails(write('alice', 'alice-a1', 'calls/call-ab/state', 'ENDED'));
    await assertFails(remove('alice', 'alice-a1', 'calls/call-ab'));
  });

  test('control flag gates call reads', async () => {
    await adminSet('control/enabled', false);
    await assertFails(read('alice', 'alice-a1', 'calls/call-ab'));
  });
});

describe('contract sanity', () => {
  test('the candidate file denies every unspecified path at the root', async () => {
    const rules = JSON.parse(
      fs.readFileSync(new URL('../database.rules.json', import.meta.url), 'utf8'),
    );
    assert.equal(rules.rules['.read'], false);
    assert.equal(rules.rules['.write'], false);
    for (const mirror of ['control', 'accounts', 'sessions', 'members', 'presenceReaders', 'blocked']) {
      assert.equal(rules.rules[mirror]['.read'], false, `${mirror} read`);
      assert.equal(rules.rules[mirror]['.write'], false, `${mirror} write`);
    }
    assert.equal(rules.rules.calls['$callId']['.write'], false);
  });
});
