import fs from 'node:fs';
import { initializeTestEnvironment } from '@firebase/rules-unit-testing';

export const PROJECT_ID = 'demo-nexachat-presence';
export const DATABASE_HOST = '127.0.0.1';
export const DATABASE_PORT = 19007;
export const SERVER_TIMESTAMP = Object.freeze({ '.sv': 'timestamp' });
export const LEASE_MS = 600_000;

const RULES_PATH = new URL('../database.rules.json', import.meta.url);

export async function createEnvironment() {
  return initializeTestEnvironment({
    projectId: PROJECT_ID,
    database: {
      rules: fs.readFileSync(RULES_PATH, 'utf8'),
      host: DATABASE_HOST,
      port: DATABASE_PORT,
    },
  });
}

/**
 * Mirrors the server-projected authorization state described in
 * reports/r4-307-sol-firebase-design.md. These fields are written by the
 * privileged REST client only; every client-facing read/write is denied.
 */
export function baseFixture() {
  const now = Date.now();
  const until = now + LEASE_MS;
  return {
    control: { enabled: true, presencePrivacyVersion: 2 },
    accounts: {
      alice: { active: true, showPresence: true },
      bob: { active: true, showPresence: true },
      carol: { active: true, showPresence: true },
      dave: { active: true, showPresence: true },
      erin: { active: false, showPresence: true },
    },
    sessions: {
      alice: { 'alice-a1': { until }, 'alice-a2': { until } },
      bob: { 'bob-b1': { until }, 'bob-b2': { until } },
      carol: { 'carol-c1': { until } },
      dave: { 'dave-d1': { until } },
      erin: { 'erin-e1': { until } },
    },
    members: {
      'direct-ab': { alice: until, bob: until },
      'group-abc': { alice: until, bob: until, carol: until },
      'direct-bc': { bob: until, carol: until },
    },
    presenceReaders: {
      bob: { alice: { cid: 'direct-ab', type: 'DIRECT' } },
      alice: { bob: { cid: 'direct-ab', type: 'DIRECT' } },
    },
    presence: {
      bob: {
        'bob-b1': { online: true, lastSeen: 0, updatedAt: now, leaseUntil: until },
      },
    },
    typing: {
      'direct-ab': {
        bob: { bridgeId: 'bob-b1', updatedAt: now - 4_000, expiresAt: now + 4_500 },
      },
    },
    calls: {
      'call-ab': {
        cid: 'direct-ab',
        callerId: 'alice',
        calleeId: 'bob',
        state: 'RINGING',
        updatedAt: now,
        expiresAt: now + 40_000,
      },
    },
    blocked: {},
  };
}
