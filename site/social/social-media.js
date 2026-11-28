const UUID_PATH = '[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}';
const LEGACY_IMAGE_RE = new RegExp(`^/api/v1/feed/images/(${UUID_PATH})$`);
const CANONICAL_IMAGE_RE = new RegExp(`^/api/social/v1/images/(${UUID_PATH})$`);
const CANONICAL_VIDEO_RE = new RegExp(`^/api/social/v1/videos/(${UUID_PATH})$`);
const LEGACY_VIDEO_RE = new RegExp(`^/api/v1/feed/videos/(${UUID_PATH})$`);

const MEDIA_UUID_RE = new RegExp(`^${UUID_PATH}$`);

export function isMediaId(value) {
  return typeof value === 'string' && MEDIA_UUID_RE.test(value);
}

export const ALLOWED_IMAGE_MIME_TYPES = new Set(['image/jpeg', 'image/png', 'image/webp']);
export const ALLOWED_VIDEO_MIME_TYPES = new Set(['video/mp4']);

export function canonicalImageRequestPath(url) {
  if (typeof url !== 'string') {
    return null;
  }
  const match = LEGACY_IMAGE_RE.exec(url) || CANONICAL_IMAGE_RE.exec(url);
  return match ? `/images/${encodeURIComponent(match[1].toLowerCase())}` : null;
}

export function canonicalVideoRequestPath(url) {
  if (typeof url !== 'string') {
    return null;
  }
  const match = CANONICAL_VIDEO_RE.exec(url) || LEGACY_VIDEO_RE.exec(url);
  return match ? `/videos/${encodeURIComponent(match[1].toLowerCase())}` : null;
}

export function imageUuid(url) {
  const path = canonicalImageRequestPath(url);
  if (!path) {
    return null;
  }
  return decodeURIComponent(path.slice('/images/'.length));
}

export function videoUuid(url) {
  const path = canonicalVideoRequestPath(url);
  if (!path) {
    return null;
  }
  return decodeURIComponent(path.slice('/videos/'.length));
}
