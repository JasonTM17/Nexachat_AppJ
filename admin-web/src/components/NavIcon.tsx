export type NavIconName =
  | 'overview'
  | 'users'
  | 'alerts'
  | 'feed'
  | 'rules'
  | 'audit'
  | 'system'
  | 'logout';

export function NavIcon({ name }: { name: NavIconName }) {
  return (
    <svg className="nav-ico" viewBox="0 0 24 24" aria-hidden="true">
      {iconPaths(name)}
    </svg>
  );
}

function iconPaths(name: NavIconName) {
  switch (name) {
    case 'overview':
      return (
        <>
          <rect x="3.5" y="3.5" width="7" height="7" rx="1.5" />
          <rect x="13.5" y="3.5" width="7" height="7" rx="1.5" />
          <rect x="3.5" y="13.5" width="7" height="7" rx="1.5" />
          <rect x="13.5" y="13.5" width="7" height="7" rx="1.5" />
        </>
      );
    case 'users':
      return (
        <>
          <path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2" />
          <circle cx="9" cy="7" r="3" />
          <path d="M22 21v-2a4 4 0 0 0-3-3.87" />
          <path d="M16 3.13a4 4 0 0 1 0 7.75" />
        </>
      );
    case 'alerts':
      return (
        <>
          <path d="M12 3.5 21 19.5H3Z" />
          <path d="M12 9.5v4.5" />
          <path d="M12 17h.01" />
        </>
      );
    case 'feed':
      return (
        <>
          <path d="M8 6h13" />
          <path d="M8 12h13" />
          <path d="M8 18h13" />
          <path d="M3 6h.01" />
          <path d="M3 12h.01" />
          <path d="M3 18h.01" />
        </>
      );
    case 'rules':
      return (
        <>
          <path d="M9 6h11" />
          <path d="M9 12h11" />
          <path d="M9 18h11" />
          <path d="M3.5 6.2 5.1 7.8 7.4 5" />
          <path d="M3.5 12.2 5.1 13.8 7.4 11" />
          <path d="M3.5 18.2 5.1 19.8 7.4 17" />
        </>
      );
    case 'audit':
      return (
        <>
          <circle cx="12" cy="12" r="8" />
          <path d="M12 8v5l3 2" />
        </>
      );
    case 'system':
      return <path d="M3 12h4l2.5-6 4 12L16 12h5" />;
    case 'logout':
      return (
        <>
          <path d="M9 6H5.5A1.5 1.5 0 0 0 4 7.5v9A1.5 1.5 0 0 0 5.5 18H9" />
          <path d="M14 16.5 18.5 12 14 7.5" />
          <path d="M18 12H9" />
        </>
      );
  }
}
