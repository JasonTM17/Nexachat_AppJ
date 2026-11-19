import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  api,
  type Page,
  type UserDetail,
  type UserSummary,
} from '../api';
import { useI18n, type StringKey } from '../i18n';
import { useSession } from '../auth';
import { useDebouncedValue, useLatestRequest } from '../lib/async';
import {
  Chip,
  ConfirmDialog,
  Drawer,
  Empty,
  ErrorBlock,
  Loading,
  Pager,
  fmtTime,
} from '../components/ui';

const PAGE_SIZE = 25;

type Action =
  | { kind: 'suspend' }
  | { kind: 'unsuspend' }
  | { kind: 'revoke' }
  | { kind: 'warn' }
  | { kind: 'role'; role: string };

function statusOf(u: UserSummary): StringKey {
  if (u.deletedAt) return 'users.status.deleted';
  if (u.suspendedAt) return 'users.status.suspended';
  return 'users.status.active';
}

export default function UsersPage() {
  const { t } = useI18n();
  const { isAdmin } = useSession();
  const [search, setSearch] = useState('');
  const [status, setStatus] = useState('');
  const [role, setRole] = useState('');
  const [page, setPage] = useState(0);
  const [data, setData] = useState<Page<UserSummary> | null>(null);
  const [failed, setFailed] = useState(false);
  const [selected, setSelected] = useState<UserDetail | null>(null);
  const [action, setAction] = useState<Action | null>(null);
  const [busy, setBusy] = useState(false);
  const [showDeleted, setShowDeleted] = useState(false);
  const [deleted, setDeleted] = useState<UserSummary[] | null>(null);
  const [actionError, setActionError] = useState(false);
  const searchInput = useDebouncedValue(search, 300);
  const latest = useLatestRequest();

  const params = useMemo(() => {
    const q = new URLSearchParams();
    if (searchInput.trim()) q.set('search', searchInput.trim());
    if (status) q.set('status', status);
    if (role) q.set('role', role);
    q.set('page', String(page));
    q.set('size', String(PAGE_SIZE));
    return q.toString();
  }, [searchInput, status, role, page]);

  const load = useCallback(() => {
    setFailed(false);
    latest(api<Page<UserSummary>>(`/api/v1/admin/users?${params}`), (data) => {
      if (data) setData(data);
      else setFailed(true);
    });
  }, [params, latest]);

  useEffect(() => {
    load();
  }, [load]);

  const openDetail = (id: string) => {
    setActionError(false);
    api<UserDetail>(`/api/v1/admin/users/${id}`)
      .then(setSelected)
      .catch(() => setActionError(true));
  };

  const runAction = async (reason: string) => {
    if (!selected || !action) return;
    setBusy(true);
    setActionError(false);
    const id = selected.user.id;
    try {
      switch (action.kind) {
        case 'suspend':
          await api(`/api/v1/admin/users/${id}/suspend`, {
            method: 'POST',
            body: { reason },
          });
          break;
        case 'unsuspend':
          await api(`/api/v1/admin/users/${id}/unsuspend`, {
            method: 'POST',
            body: { reason: reason || undefined },
          });
          break;
        case 'revoke':
          await api(`/api/v1/admin/users/${id}/revoke-sessions`, {
            method: 'POST',
            body: { reason: reason || undefined },
          });
          break;
        case 'warn':
          await api(`/api/v1/admin/users/${id}/actions`, {
            method: 'POST',
            body: { reason },
          });
          break;
        case 'role':
          await api(`/api/v1/admin/users/${id}/role`, {
            method: 'PATCH',
            body: { role: action.role, reason },
          });
          break;
      }
      setAction(null);
      openDetail(id);
      load();
    } catch {
      setActionError(true);
      setAction(null);
    } finally {
      setBusy(false);
    }
  };

  const toggleDeleted = () => {
    if (showDeleted) {
      setShowDeleted(false);
      return;
    }
    api<UserSummary[]>('/api/v1/admin/users/deleted?limit=50')
      .then((rows) => {
        setDeleted(rows);
        setShowDeleted(true);
      })
      .catch(() => setActionError(true));
  };

  const totalPages = data ? Math.max(1, Math.ceil(data.total / PAGE_SIZE)) : 1;

  return (
    <>
      <div className="filters">
        <label className="filter-field">
          <span>{t('common.search')}</span>
          <input
            className="input"
            placeholder={t('common.search')}
            aria-label={t('common.search')}
            value={search}
            onChange={(e) => {
              setSearch(e.target.value);
              setPage(0);
            }}
          />
        </label>
        <label className="filter-field">
          <span>{t('users.col.status')}</span>
          <select
            className="select"
            aria-label={t('users.col.status')}
            value={status}
            onChange={(e) => {
              setStatus(e.target.value);
              setPage(0);
            }}
          >
            <option value="">{t('common.all')}</option>
            <option value="active">{t('users.status.active')}</option>
            <option value="suspended">{t('users.status.suspended')}</option>
            <option value="deleted">{t('users.status.deleted')}</option>
          </select>
        </label>
        <label className="filter-field">
          <span>{t('users.col.role')}</span>
          <select
            className="select"
            aria-label={t('users.col.role')}
            value={role}
            onChange={(e) => {
              setRole(e.target.value);
              setPage(0);
            }}
          >
            <option value="">{t('common.all')}</option>
            <option value="USER">USER</option>
            <option value="MODERATOR">MODERATOR</option>
            <option value="ADMIN">ADMIN</option>
          </select>
        </label>
        <button className="btn ghost" onClick={toggleDeleted} aria-pressed={showDeleted}>
          {t('users.deleted')}
        </button>
      </div>

      {actionError && (
        <div className="form-error" role="alert">
          {t('common.error')}
        </div>
      )}

      {showDeleted && deleted ? (
        deleted.length === 0 ? (
          <Empty />
        ) : (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th scope="col">{t('users.col.user')}</th>
                  <th scope="col">{t('users.col.joined')}</th>
                  <th scope="col">{t('users.col.status')}</th>
                </tr>
              </thead>
              <tbody>
                {deleted.map((u) => (
                  <tr className="plain" key={u.id}>
                    <td>
                      <div>{u.displayName ?? '—'}</div>
                      <div className="muted mono">{u.phone ?? '—'}</div>
                    </td>
                    <td>{fmtTime(u.createdAt)}</td>
                    <td>
                      <Chip tone="low">{t('users.status.deleted')}</Chip>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )
      ) : failed ? (
        <ErrorBlock onRetry={load} />
      ) : !data ? (
        <Loading />
      ) : data.items.length === 0 ? (
        <Empty
          onClearFilters={
            search || status || role
              ? () => {
                  setSearch('');
                  setStatus('');
                  setRole('');
                  setPage(0);
                }
              : undefined
          }
        />
      ) : (
        <>
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th scope="col">{t('users.col.user')}</th>
                  <th scope="col" className="col-lo">{t('users.col.role')}</th>
                  <th scope="col">{t('users.col.status')}</th>
                  <th scope="col" className="col-lo">{t('users.col.joined')}</th>
                  <th scope="col" className="col-lo">{t('users.col.lastSeen')}</th>
                </tr>
              </thead>
              <tbody>
                {data.items.map((u) => (
                  <tr
                    key={u.id}
                    tabIndex={0}
                    onClick={() => openDetail(u.id)}
                    onKeyDown={(e) => e.key === 'Enter' && openDetail(u.id)}
                  >
                    <td>
                      <div>{u.displayName ?? '—'}</div>
                      <div className="muted mono">{u.phone ?? '—'}</div>
                    </td>
                    <td className="col-lo">
                      <Chip>{u.role}</Chip>
                    </td>
                    <td>
                      <Chip
                        tone={
                          u.suspendedAt ? 'high' : u.deletedAt ? 'low' : 'success'
                        }
                      >
                        {t(statusOf(u))}
                      </Chip>
                    </td>
                    <td className="col-lo">{fmtTime(u.createdAt)}</td>
                    <td className="col-lo">{fmtTime(u.lastSeenAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <Pager page={page} totalPages={totalPages} onPage={setPage} />
        </>
      )}

      {selected && (
        <Drawer
          title={selected.user.displayName ?? selected.user.phone ?? selected.user.id}
          sub={selected.user.id}
          onClose={() => setSelected(null)}
        >
          <div className="action-row">
            {!selected.user.suspendedAt ? (
              <button
                className="btn danger sm"
                onClick={() => setAction({ kind: 'suspend' })}
              >
                {t('users.action.suspend')}
              </button>
            ) : (
              <button
                className="btn primary sm"
                onClick={() => setAction({ kind: 'unsuspend' })}
              >
                {t('users.action.unsuspend')}
              </button>
            )}
            <button
              className="btn ghost sm"
              onClick={() => setAction({ kind: 'revoke' })}
            >
              {t('users.action.revoke')}
            </button>
            {isAdmin && (
              <>
                <select
                  className="select"
                  style={{ width: 'auto' }}
                  aria-label={t('users.action.role')}
                  value=""
                  onChange={(e) => {
                    if (e.target.value) setAction({ kind: 'role', role: e.target.value });
                    e.target.value = '';
                  }}
                >
                  <option value="">{t('users.action.role')}</option>
                  {['USER', 'MODERATOR', 'ADMIN']
                    .filter((r) => r !== selected.user.role)
                    .map((r) => (
                      <option key={r} value={r}>{r}</option>
                    ))}
                </select>
              </>
            )}
            <button
              className="btn ghost sm"
              onClick={() => setAction({ kind: 'warn' })}
            >
              {t('users.action.warn')}
            </button>
          </div>
          <section>
            <h3>{t('users.col.status')}</h3>
            <div>
              <Chip tone={selected.user.suspendedAt ? 'high' : 'success'}>
                {t(statusOf(selected.user))}
              </Chip>{' '}
              <Chip>{selected.user.role}</Chip>
            </div>
            {selected.user.suspendedReason && (
              <div className="snippet">{selected.user.suspendedReason}</div>
            )}
          </section>
          <section>
            <h3>{t('users.col.user')}</h3>
            <table>
              <tbody>
                <tr className="plain">
                  <td className="muted">{t('users.detail.sessions')}</td>
                  <td>{selected.sessionCount}</td>
                </tr>
                <tr className="plain">
                  <td className="muted">{t('users.detail.devices')}</td>
                  <td>{selected.deviceCount}</td>
                </tr>
                <tr className="plain">
                  <td className="muted">{t('users.detail.conversations')}</td>
                  <td>{selected.conversationCount}</td>
                </tr>
                <tr className="plain">
                  <td className="muted">{t('users.detail.alerts')}</td>
                  <td>{selected.alertCount}</td>
                </tr>
              </tbody>
            </table>
          </section>
        </Drawer>
      )}

      {action && (
        <ConfirmDialog
          title={
            action.kind === 'suspend'
              ? t('users.action.suspend')
              : action.kind === 'unsuspend'
                ? t('users.action.unsuspend')
                : action.kind === 'revoke'
                  ? t('users.action.revoke')
                  : action.kind === 'warn'
                    ? t('users.action.warn')
                    : t('users.action.role.to', { role: action.role })
          }
          requireReason={action.kind !== 'revoke' && action.kind !== 'unsuspend'}
          confirmDanger={
            action.kind === 'suspend' ||
            action.kind === 'revoke' ||
            (action.kind === 'role' && action.role === 'ADMIN')
          }
          busy={busy}
          onConfirm={(reason) => void runAction(reason)}
          onCancel={() => setAction(null)}
        />
      )}
    </>
  );
}
