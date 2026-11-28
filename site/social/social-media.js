const UUID_PATH = '[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}';
const LEGACY_IMAGE_RE = new RegExp(`^/api/v1/feed/images/(${UUID_PATH})$`);
const CANONICAL_IMAGE_RE = new RegExp(`^/api/social/v1/images/(${UUID_PATH})$`);

export const ALLOWED_IMAGE_MIME_TYPES = new Set(['image/jpeg', 'image/png', 'image/webp']);

export function canonicalImageRequestPath(url) {
  if (typeof url !== 'string') {
    return null;
  }
  const match = LEGACY_IMAGE_RE.exec(url) || CANONICAL_IMAGE_RE.exec(url);
  return match ? `/images/${encodeURIComponent(match[1].toLowerCase())}` : null;
}

export function imageUuid(url) {
  const path = canonicalImageRequestPath(url);
  if (!path) {
    return null;
  }
  return decodeURIComponent(path.slice('/images/'.length));
}
