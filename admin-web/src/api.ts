// Typed fetch layer for the admin API. Access + refresh tokens live in
// sessionStorage (per-tab, cleared on tab close); on a 401 with TOKEN_EXPIRED
// one refresh attempt is made before the session is abandoned.

export const API_BASE: string =
  (import.meta.env.VITE_API_URL as string | undefined)?.replace(/\/$/, '') ?? '';

export interface ApiError {
  status: number;
  code: string;
  message: string;
}

export class ApiException extends Error implements ApiError {
  constructor(
    public status: number,
    public code: string,
    message: string,
  ) {
    super(message);
  }
}

const ACCESS_KEY = 'admin.accessToken';
const REFRESH_KEY = 'admin.refreshToken';

export const tokenStore = {
  get access() {
    return sessionStorage.getItem(ACCESS_KEY);
  },
  get refresh() {
    return sessionStorage.getItem(REFRESH_KEY);
  },
  set(access: string, refresh: string) {
    sessionStorage.setItem(ACCESS_KEY, access);
    sessionStorage.setItem(REFRESH_KEY, refresh);
  },
  clear() {
    sessionStorage.removeItem(ACCESS_KEY);
    sessionStorage.removeItem(REFRESH_KEY);
  },
};

async function parseError(res: Response): Promise<ApiException> {
  try {
    const body = await res.json();
    const err = body?.error;
    return new ApiException(res.status, err?.code ?? 'UNKNOWN', err?.message ?? res.statusText);
  } catch {
    return new ApiException(res.status, 'UNKNOWN', res.statusText);
  }
}

let refreshing: Promise<boolean> | null = null;

async function tryRefresh(): Promise<boolean> {
  if (!tokenStore.refresh) return false;
  refreshing ??= (async () => {
    try {
      const res = await fetch(`${API_BASE}/api/v1/auth/refresh`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ refreshToken: tokenStore.refresh }),
      });
      if (!res.ok) return false;
      const body = await res.json();
      tokenStore.set(body.accessToken, body.refreshToken);
      return true;
    } catch {
      return false;
    } finally {
      refreshing = null;
    }
  })();
  return refreshing;
}

export async function api<T>(
  path: string,
  options: { method?: string; body?: unknown; auth?: boolean } = {},
): Promise<T> {
  const { method = 'GET', body, auth = true } = options;
  const request = () =>
    fetch(`${API_BASE}${path}`, {
      method,
      headers: {
        ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
        ...(auth && tokenStore.access ? { Authorization: `Bearer ${tokenStore.access}` } : {}),
      },
      body: body !== undefined ? JSON.stringify(body) : undefined,
    });
  let res = await request();
  if (res.status === 401 && auth && (await tryRefresh())) {
    res = await request();
  }
  if (!res.ok) throw await parseError(res);
  if (res.status === 204) return undefined as T;
  const text = await res.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

export async function login(phone: string, password: string) {
  const body = await api<{
    accessToken: string;
    refreshToken: string;
    user: { id: string };
  }>('/api/v1/auth/login', {
    method: 'POST',
    body: { phone, password },
    auth: false,
  });
  tokenStore.set(body.accessToken, body.refreshToken);
  return body;
}

export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
  hasMore: boolean;
}

export interface UserSummary {
  id: string;
  phone: string | null;
  displayName: string | null;
  avatarUrl: string | null;
  email: string | null;
  role: string;
  suspendedAt: string | null;
  suspendedReason: string | null;
  createdAt: string;
  lastSeenAt: string | null;
  deletedAt: string | null;
}

export interface UserDetail {
  user: UserSummary;
  deviceCount: number;
  sessionCount: number;
  conversationCount: number;
  alertCount: number;
}

export interface AlertSummary {
  id: string;
  messageId: string;
  conversationId: string;
  senderId: string;
  messageSeq: number;
  category: string;
  severity: string;
  ruleNames: string[];
  snippet: string | null;
  snippetTombstoned: boolean;
  status: string;
  assigneeId: string | null;
  escalated: boolean;
  dryRun: boolean;
  createdAt: string;
}

export interface AlertNote {
  id: number;
  alertId: string;
  authorId: string;
  body: string;
  createdAt: string;
}

export interface AlertDetail {
  alert: AlertSummary;
  notes: AlertNote[];
  senderDisplayName: string | null;
}

export interface RevealedMessage {
  id: string;
  seq: number;
  senderId: string;
  content: string | null;
  createdAt: string;
}

export interface AlertRule {
  id: string;
  name: string;
  category: string;
  severity: string;
  patternType: string;
  pattern: string | null;
  locale: string;
  builtIn: boolean;
  enabled: boolean;
  description: string | null;
}

export interface AuditEntry {
  id: number;
  actorId: string;
  actorRole: string;
  action: string;
  targetType: string;
  targetId: string | null;
  reason: string | null;
  detail: Record<string, unknown> | null;
  ip: string | null;
  createdAt: string;
}

export interface Overview {
  kpis: {
    registrationsToday: number;
    dau: number;
    wau: number;
    messagesToday: number;
    activeCalls: number;
    databaseBytes: number;
    uploadsBytes: number;
    pendingAlerts: number;
    errorRate: number;
    queue: {
      depth: number;
      dropped: number;
      scanned: number;
      alertsCreated: number;
      enabled: boolean;
      dryRun: boolean;
    };
  };
  days: number;
  series: { date: string; registrations: number; messages: number }[];
  generatedAt: string;
}

export interface SystemHealth {
  status: string;
  checkedAt: string;
  uptimeSeconds: number;
  services: { name: string; status: string; detail: string }[];
}

export interface MeResponse {
  userId: string;
  role: string;
  displayName: string | null;
}

export type FeedReportStatus = 'OPEN' | 'REVIEWED' | 'DISMISSED';
export type FeedTargetType = 'POST' | 'COMMENT';
export type FeedTargetState = 'VISIBLE' | 'HIDDEN' | 'DELETED';

export interface FeedReport {
  id: string;
  targetType: FeedTargetType;
  targetId: string;
  reason: string;
  details: string | null;
  messageSnapshot: string | null;
  status: FeedReportStatus;
  createdAt: string;
  reporterId: string;
  reporterName: string | null;
  authorId: string | null;
  authorName: string | null;
  targetBody: string | null;
  targetDeleted: boolean;
  targetHidden: boolean;
  targetCreatedAt: string | null;
}

export function feedReportsPath(status: FeedReportStatus, page: number, size: number): string {
  const q = new URLSearchParams({ status, page: String(page), size: String(size) });
  return `/api/v1/admin/feed/reports?${q.toString()}`;
}

export function feedTargetPath(
  type: FeedTargetType,
  id: string,
  action: 'hide' | 'unhide',
): string {
  const kind = type === 'POST' ? 'posts' : 'comments';
  return `/api/v1/admin/feed/${kind}/${encodeURIComponent(id)}/${action}`;
}

export function feedResolvePath(reportId: string): string {
  return `/api/v1/admin/feed/reports/${encodeURIComponent(reportId)}/resolve`;
}
