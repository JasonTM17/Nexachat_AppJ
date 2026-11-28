import { createSocialClient, fetchSocialConfig, SocialApiError, SocialSessionError } from './social-client.js';
import {
  ALLOWED_IMAGE_MIME_TYPES,
  ALLOWED_VIDEO_MIME_TYPES,
  canonicalImageRequestPath,
  canonicalVideoRequestPath,
  imageUuid,
  isMediaId,
  safeAvatarFilePath,
  videoUuid,
} from './social-media.js';

const HEART = '❤️';
const REACTION_EMOJIS = ['❤️', '👍', '😂', '😮', '😢', '🔥'];
const THEME_KEY = 'social.theme';

const TEXT = {
  vi: {
    skip: 'Bỏ qua đến nội dung',
    android: 'Tải Android',
    eyebrow: 'Bảng tin NexaChat',
    title: 'Chia sẻ công khai với tài khoản NexaChat của bạn.',
    titleCompact: 'NexaChat Social',
    leadCompact: 'Chia sẻ công khai với các tài khoản NexaChat.',
    lead: 'Đăng bài, ảnh hoặc video, bình luận, chỉnh sửa, tìm kiếm và cảm xúc. Bài viết hiển thị với mọi tài khoản NexaChat và không được mã hóa đầu cuối.',
    publicBadge: 'Công khai · Không E2EE',
    signinTitle: 'Đăng nhập',
    signinHelp: 'Dùng số điện thoại hoặc email đã xác minh cùng mật khẩu NexaChat của bạn.',
    identifier: 'Số điện thoại hoặc email đã xác minh',
    password: 'Mật khẩu',
    signinButton: 'Đăng nhập',
    signingIn: 'Đang đăng nhập…',
    signinFailed: 'Không đăng nhập được. Kiểm tra thông tin đăng nhập rồi thử lại.',
    signinValidation: 'Nhập số điện thoại hoặc email và mật khẩu.',
    sessionTitle: 'Phiên đăng nhập',
    signedInAs: '{name}',
    signout: 'Đăng xuất',
    signingOut: 'Đang đăng xuất…',
    signedOut: 'Đã đăng xuất trên trình duyệt này.',
    signoutUnconfirmed: 'Đã đăng xuất cục bộ, nhưng máy chủ chưa xác nhận thu hồi phiên.',
    composerTitle: 'Bài viết mới',
    composerLabel: 'Nội dung bài viết',
    composerPlaceholder: 'Chia sẻ suy nghĩ hoặc cập nhật với cộng đồng…',
    mediaLabel: 'Ảnh hoặc video',
    mediaPicker: 'Đính kèm ảnh / video',
    mediaHelp: 'JPEG, PNG, WebP hoặc MP4 tối đa 4 MiB. Ảnh có thể đăng một mình; video cần kèm nội dung chú thích.',
    mediaHelpImagesOnly: 'JPEG, PNG, WebP tối đa 4 MiB. Ảnh có thể đăng một mình.',
    mediaVideoDisabled: 'Đăng video đang tạm tắt. Bạn vẫn có thể đăng ảnh.',
    unavailableTitle: 'Bảng tin tạm thời không khả dụng',
    socialDisabled: 'Bảng tin web đang tạm đóng. Vui lòng quay lại sau.',
    configUnavailable: 'Không tải được trạng thái bảng tin. Kiểm tra kết nối rồi tải lại trang.',
    mediaRemove: 'Gỡ tệp',
    mediaTypeError: 'Chỉ nhận ảnh JPEG, PNG, WebP hoặc video MP4.',
    mediaSizeError: 'Tệp phải nhỏ hơn hoặc bằng 4 MiB.',
    videoNeedsCaption: 'Video cần kèm nội dung chú thích trước khi đăng.',
    uploading: 'Đang tải lên…',
    uploadFailed: 'Không tải được tệp lên. Thử lại khi bạn sẵn sàng.',
    loadVideo: 'Tải video',
    videoLoading: 'Đang tải video…',
    videoUnavailable: 'Không tải được video.',
    videoPlaybackFailed: 'Không phát được video này. Thử lại hoặc dùng MP4 H.264.',
    retryVideo: 'Thử lại',
    videoAlt: 'Video của bài viết',
    postButton: 'Đăng bài',
    posting: 'Đang đăng…',
    postFailed: 'Không đăng được bài. Thử lại khi bạn sẵn sàng.',
    postEmpty: 'Viết nội dung trước khi đăng.',
    disclosure: 'Bài viết hiển thị với mọi tài khoản NexaChat và không được mã hóa.',
    feedTitle: 'Bảng tin',
    refresh: 'Làm mới',
    loadMore: 'Tải thêm',
    loading: 'Đang tải…',
    empty: 'Chưa có bài viết nào. Hãy là người đầu tiên chia sẻ.',
    noSearchResults: 'Không tìm thấy bài viết phù hợp.',
    searchPlaceholder: 'Tìm bài viết theo từ khóa…',
    searchAria: 'Tìm kiếm bài viết',
    clearSearch: 'Xóa tìm kiếm',
    authorFilterLabel: 'Đang lọc theo: {name}',
    clearAuthorFilter: 'Bỏ lọc tác giả',
    feedError: 'Không tải được bảng tin. Thử lại sau.',
    commentsError: 'Không tải được bình luận.',
    retry: 'Thử lại',
    sessionExpired: 'Phiên đã hết hạn. Đăng nhập lại để tiếp tục.',
    comments: '{count} bình luận',
    commentsOne: '1 bình luận',
    commentsZero: 'Bình luận',
    commentEmpty: 'Chưa có bình luận nào.',
    commentLabel: 'Viết bình luận',
    commentPlaceholder: 'Viết bình luận…',
    commentButton: 'Gửi bình luận',
    commenting: 'Đang gửi…',
    commentFailed: 'Không gửi được bình luận.',
    commentValidation: 'Viết bình luận trước khi gửi.',
    react: 'Thả tim',
    removeReact: 'Bỏ tim',
    addReaction: 'Chọn cảm xúc',
    reactWith: 'Thả cảm xúc {emoji}',
    copy: 'Sao chép',
    copied: 'Đã sao chép.',
    copyFailed: 'Không sao chép được.',
    edit: 'Sửa',
    save: 'Lưu',
    saving: 'Đang lưu…',
    edited: '(đã chỉnh sửa)',
    editFailed: 'Không lưu được chỉnh sửa.',
    editEmpty: 'Nội dung không được để trống.',
    delete: 'Xóa',
    report: 'Báo cáo',
    confirmDeletePost: 'Xóa bài viết này? Bài viết sẽ biến mất với mọi người.',
    confirmDeleteComment: 'Xóa bình luận này?',
    deleted: 'Đã xóa.',
    deleteFailed: 'Không xóa được.',
    reactionFailed: 'Không cập nhật được cảm xúc.',
    imageUnavailable: 'Không tải được ảnh.',
    imageAlt: 'Ảnh của bài viết',
    reportTitle: 'Báo cáo nội dung',
    reportReason: 'Lý do',
    reportDetailsLabel: 'Chi tiết bổ sung (không bắt buộc)',
    reportDetailsPlaceholder: 'Mô tả thêm ngữ cảnh nếu cần…',
    cancel: 'Hủy',
    close: 'Đóng',
    reportSubmit: 'Gửi báo cáo',
    reporting: 'Đang gửi báo cáo…',
    reportSent: 'Đã gửi báo cáo.',
    reportFailed: 'Không gửi được báo cáo.',
    reasonSpam: 'Spam',
    reasonHarassment: 'Quấy rối',
    reasonViolence: 'Bạo lực',
    reasonSexual: 'Nội dung khiêu dâm',
    reasonScam: 'Lừa đảo',
    reasonOther: 'Khác',
    authorPostCount: '{count} bài viết',
    authorPostCountOne: '1 bài viết',
    authorProfileError: 'Không tải được hồ sơ tác giả.',
    filterByAuthor: 'Xem bài viết của tác giả',
    themeToDark: 'Chuyển sang giao diện tối',
    themeToLight: 'Chuyển sang giao diện sáng',
    contextTitle: 'Ai xem được bảng tin?',
    contextBody: 'Mọi tài khoản NexaChat đều có thể đọc bài viết công khai. Nội dung ở đây không phải tin nhắn riêng tư và không được mã hóa đầu cuối.',
    chatTitle: 'Trò chuyện riêng',
    chatBody: 'Nhắn tin riêng vẫn nằm trong ứng dụng Android NexaChat. Trang này không cung cấp trò chuyện trên trình duyệt.',
    openChat: 'Tải NexaChat cho Android',
    downloadHash: '/download.html#lang-vi',
    locale: 'vi-VN',
  },
  en: {
    skip: 'Skip to content',
    android: 'Get Android',
    eyebrow: 'NexaChat Social',
    title: 'Share publicly with your NexaChat account.',
    titleCompact: 'NexaChat Social',
    leadCompact: 'Share publicly with NexaChat accounts.',
    lead: 'Post text, photos, or videos, edit, search, comment, react, and report. Posts are visible to every NexaChat account and are not end-to-end encrypted.',
    publicBadge: 'Public · Not E2EE',
    signinTitle: 'Sign in',
    signinHelp: 'Use your NexaChat phone number or verified email with your password.',
    identifier: 'Phone number or verified email',
    password: 'Password',
    signinButton: 'Sign in',
    signingIn: 'Signing in…',
    signinFailed: 'Could not sign in. Check the account details and try again.',
    signinValidation: 'Enter a phone number or email and the password.',
    sessionTitle: 'Signed-in session',
    signedInAs: '{name}',
    signout: 'Sign out',
    signingOut: 'Signing out…',
    signedOut: 'Signed out in this browser.',
    signoutUnconfirmed: 'Signed out locally, but the server did not confirm session revocation.',
    composerTitle: 'New post',
    composerLabel: 'Post body',
    composerPlaceholder: 'Share a thought or update with the community…',
    mediaLabel: 'Photo or video',
    mediaPicker: 'Attach photo or video',
    mediaHelp: 'JPEG, PNG, WebP, or MP4 up to 4 MiB. A photo can be posted alone; a video needs a caption.',
    mediaHelpImagesOnly: 'JPEG, PNG, or WebP up to 4 MiB. A photo can be posted alone.',
    mediaVideoDisabled: 'Video posting is temporarily off. You can still post photos.',
    unavailableTitle: 'Social is temporarily unavailable',
    socialDisabled: 'The web feed is currently turned off. Please check back later.',
    configUnavailable: 'Could not load the feed status. Check the connection and reload the page.',
    mediaRemove: 'Remove file',
    mediaTypeError: 'Only JPEG, PNG, WebP images or MP4 videos are accepted.',
    mediaSizeError: 'The file must be at most 4 MiB.',
    videoNeedsCaption: 'A video needs a caption before posting.',
    uploading: 'Uploading…',
    uploadFailed: 'Could not upload the file. Try again when ready.',
    loadVideo: 'Load video',
    videoLoading: 'Loading video…',
    videoUnavailable: 'Could not load the video.',
    videoPlaybackFailed: 'This video could not play. Try again or use an H.264 MP4.',
    retryVideo: 'Retry',
    videoAlt: 'Post video',
    postButton: 'Post',
    posting: 'Posting…',
    postFailed: 'Could not create the post. Try again when ready.',
    postEmpty: 'Write something before posting.',
    disclosure: 'Posts are visible to NexaChat accounts and are not encrypted.',
    feedTitle: 'Feed',
    refresh: 'Refresh',
    loadMore: 'Load more',
    loading: 'Loading…',
    empty: 'No posts yet. Be the first to share.',
    noSearchResults: 'No matching posts found.',
    searchPlaceholder: 'Search posts by keyword…',
    searchAria: 'Search posts',
    clearSearch: 'Clear search',
    authorFilterLabel: 'Filtering by: {name}',
    clearAuthorFilter: 'Clear author filter',
    feedError: 'Could not load the feed. Try again later.',
    commentsError: 'Could not load comments.',
    retry: 'Retry',
    sessionExpired: 'The session expired. Sign in again to continue.',
    comments: '{count} comments',
    commentsOne: '1 comment',
    commentsZero: 'Comments',
    commentEmpty: 'No comments yet.',
    commentLabel: 'Write a comment',
    commentPlaceholder: 'Write a comment…',
    commentButton: 'Send comment',
    commenting: 'Sending…',
    commentFailed: 'Could not send the comment.',
    commentValidation: 'Write a comment before sending.',
    react: 'Add heart',
    removeReact: 'Remove heart',
    addReaction: 'Add reaction',
    reactWith: 'React with {emoji}',
    copy: 'Copy',
    copied: 'Copied.',
    copyFailed: 'Could not copy.',
    edit: 'Edit',
    save: 'Save',
    saving: 'Saving…',
    edited: '(edited)',
    editFailed: 'Could not save changes.',
    editEmpty: 'Content cannot be blank.',
    delete: 'Delete',
    report: 'Report',
    confirmDeletePost: 'Delete this post? It disappears for everyone.',
    confirmDeleteComment: 'Delete this comment?',
    deleted: 'Deleted.',
    deleteFailed: 'Could not delete.',
    reactionFailed: 'Could not update the reaction.',
    imageUnavailable: 'Could not load the image.',
    imageAlt: 'Post image',
    reportTitle: 'Report content',
    reportReason: 'Reason',
    reportDetailsLabel: 'Additional details (optional)',
    reportDetailsPlaceholder: 'Add context if helpful…',
    cancel: 'Cancel',
    close: 'Close',
    reportSubmit: 'Send report',
    reporting: 'Sending report…',
    reportSent: 'Report sent.',
    reportFailed: 'Could not send the report.',
    reasonSpam: 'Spam',
    reasonHarassment: 'Harassment',
    reasonViolence: 'Violence',
    reasonSexual: 'Sexual content',
    reasonScam: 'Scam',
    reasonOther: 'Other',
    authorPostCount: '{count} posts',
    authorPostCountOne: '1 post',
    authorProfileError: 'Could not load author profile.',
    filterByAuthor: 'Filter by author',
    themeToDark: 'Switch to dark theme',
    themeToLight: 'Switch to light theme',
    contextTitle: 'Who can see the feed?',
    contextBody: 'Every NexaChat account can read public posts. This is not private messaging and it is not end-to-end encrypted.',
    chatTitle: 'Private chat',
    chatBody: 'Private messaging stays in the NexaChat Android app. This page does not provide browser chat.',
    openChat: 'Get NexaChat for Android',
    downloadHash: '/download.html#lang-en',
    locale: 'en-US',
  },
};

const MAX_MEDIA_BYTES = 4 * 1024 * 1024;
const ACCEPTED_MEDIA_TYPES = new Set(['image/jpeg', 'image/png', 'image/webp', 'video/mp4']);

let client = null;
let els = {};
let lang = 'vi';
let account = null;
let posts = [];
let nextCursor = null;
let feedLoading = false;
let feedRequestSeq = 0;
let searchQuery = '';
let searchDebounceTimer = null;
let authorFilter = null;
let commentsState = new Map();
let commentDrafts = new Map();
let editingPosts = new Map();
let editingComments = new Map();
let openReactionPickers = new Set();
let imageCache = new Map();
let videoCache = new Map();
let avatarCache = new Map();
let selectedFile = null;
let selectedPreviewUrl = null;
let pendingUpload = null;
let reportTarget = null;
let reportReturnFocus = null;
let authorDialogReturnFocus = null;
let authorDialogProfile = null;
let authorDialogSeq = 0;
let uiEpoch = 0;
// Runtime feature state. 'loading' is the boot value: every interactive
// panel stays hidden until the public config proves the surface enabled —
// an unreachable or malformed config fails closed to 'unavailable'.
let surfaceState = 'loading';
let featureConfig = null;
let configRequest = 0;
let postDraft = null;
let env = {};

function $(id) {
  return env.document.getElementById(id);
}

function t(key, values = {}) {
  let value = (TEXT[lang] && TEXT[lang][key]) || TEXT.vi[key] || key;
  for (const [name, replacement] of Object.entries(values)) {
    value = value.replaceAll(`{${name}}`, String(replacement));
  }
  return value;
}

function setStatus(element, key, info = false) {
  if (!element) {
    return;
  }
  element.dataset.statusKey = key || '';
  element.textContent = key ? t(key) : '';
  element.classList.toggle('info', info);
}

// Single arbiter of which coarse panels the surface state allows. When the
// surface is not enabled the interactive panels hide and the localized
// unavailable state shows instead; a published announcement stays visible
// either way, because it usually explains the state.
function applySurfaceState() {
  const enabled = surfaceState === 'enabled';
  // 'loading' keeps every panel hidden too: the unavailable card only
  // appears once the config resolves to a terminal off state, so the
  // boot window never flashes a misleading empty notice.
  els.unavailablePanel.hidden = enabled || surfaceState === 'loading';
  if (!enabled) {
    setStatus(els.unavailableBody, surfaceState === 'disabled' ? 'socialDisabled'
      : surfaceState === 'unavailable' ? 'configUnavailable' : '');
    els.signedOut.hidden = true;
    els.session.hidden = true;
    els.composer.hidden = true;
    els.feedPanel.hidden = true;
    return;
  }
  setStatus(els.unavailableBody, '');
  els.feedPanel.hidden = false;
  els.signedOut.hidden = Boolean(account);
  els.session.hidden = !account;
  els.composer.hidden = !account;
}

function renderAnnouncement() {
  const announcement = featureConfig && featureConfig.announcement;
  if (!announcement) {
    els.announcementCard.hidden = true;
    return;
  }
  els.announcementTitle.textContent = lang === 'en' ? announcement.titleEn : announcement.titleVi;
  els.announcementBody.textContent = lang === 'en' ? announcement.bodyEn : announcement.bodyVi;
  els.announcementCard.hidden = false;
}

// New video uploads can be switched off independently: the picker then
// accepts images only and the help text says so. Existing feed videos keep
// playing regardless — the switch never gates reads.
function applyVideoGate() {
  const videoOff = Boolean(featureConfig) && !featureConfig.videoUploadsEnabled;
  els.postMedia.accept = videoOff
    ? 'image/jpeg,image/png,image/webp'
    : 'image/jpeg,image/png,image/webp,video/mp4';
  els.mediaHelp.dataset.i18n = videoOff ? 'mediaHelpImagesOnly' : 'mediaHelp';
}

function readStoredTheme() {
  try {
    const stored = env.localStorage && env.localStorage.getItem(THEME_KEY);
    return stored === 'dark' || stored === 'light' ? stored : null;
  } catch {
    return null;
  }
}

function isDarkActive() {
  const stored = readStoredTheme();
  if (stored === 'dark') {
    return true;
  }
  if (stored === 'light') {
    return false;
  }
  return Boolean(
    env.window
    && typeof env.window.matchMedia === 'function'
    && env.window.matchMedia('(prefers-color-scheme: dark)').matches
  );
}

function updateThemeToggleUi() {
  if (!els.themeToggle) {
    return;
  }
  const dark = isDarkActive();
  els.themeToggle.setAttribute('aria-pressed', String(dark));
  const label = dark ? t('themeToLight') : t('themeToDark');
  els.themeToggle.setAttribute('aria-label', label);
  els.themeToggle.setAttribute('title', label);
  if (els.themeIconMoon) {
    els.themeIconMoon.hidden = dark;
  }
  if (els.themeIconSun) {
    els.themeIconSun.hidden = !dark;
  }
}

function applyInitialTheme() {
  const stored = readStoredTheme();
  if (stored) {
    env.document.documentElement.setAttribute('data-theme', stored);
  } else {
    env.document.documentElement.removeAttribute('data-theme');
  }
  updateThemeToggleUi();
}

function toggleTheme() {
  const next = isDarkActive() ? 'light' : 'dark';
  try {
    if (env.localStorage) {
      env.localStorage.setItem(THEME_KEY, next);
    }
  } catch {
    // Ignore storage quota/privacy-mode errors; still update current view.
  }
  env.document.documentElement.setAttribute('data-theme', next);
  updateThemeToggleUi();
}

async function bootstrapConfig() {
  const request = ++configRequest;
  const config = await fetchSocialConfig(env.fetch).catch(() => null);
  // An earlier refresh can finish after the current tab's config read.
  if (request !== configRequest) {
    return;
  }
  featureConfig = config;
  if (config) {
    surfaceState = config.webEnabled ? 'enabled' : 'disabled';
  } else {
    surfaceState = 'unavailable';
  }
  applySurfaceState();
  applyLanguage();
}

function applyLanguage() {
  env.document.documentElement.lang = lang;
  const signedIn = Boolean(account);
  els.mainLayout.classList.toggle('signed-in', signedIn);
  applyVideoGate();
  els.heroTitle.dataset.i18n = signedIn ? 'titleCompact' : 'title';
  els.heroLead.dataset.i18n = signedIn ? 'leadCompact' : 'lead';
  for (const node of env.document.querySelectorAll('[data-i18n]')) {
    node.textContent = t(node.dataset.i18n);
  }
  for (const node of env.document.querySelectorAll('[data-status-key]')) {
    const key = node.dataset.statusKey;
    node.textContent = key ? t(key) : '';
  }
  if (els.postBody) {
    els.postBody.placeholder = t('composerPlaceholder');
  }
  if (els.feedSearchInput) {
    els.feedSearchInput.placeholder = t('searchPlaceholder');
    els.feedSearchInput.setAttribute('aria-label', t('searchAria'));
  }
  if (els.feedSearchClear) {
    els.feedSearchClear.setAttribute('aria-label', t('clearSearch'));
  }
  if (els.authorFilterClear) {
    els.authorFilterClear.setAttribute('aria-label', t('clearAuthorFilter'));
  }
  if (els.reportDetails) {
    els.reportDetails.placeholder = t('reportDetailsPlaceholder');
  }
  els.downloadLink.href = t('downloadHash');
  els.openChatLink.href = t('downloadHash');
  els.viButton.setAttribute('aria-pressed', String(lang === 'vi'));
  els.enButton.setAttribute('aria-pressed', String(lang === 'en'));
  updateThemeToggleUi();
  updatePostCounter();
  updateReportDetailsCounter();
  renderAuthorFilterChip();
  if (account) {
    const name = displayName(account);
    els.sessionUser.textContent = t('signedInAs', { name });
    if (els.composerAuthorName) {
      els.composerAuthorName.textContent = name;
    }
  }
  renderAnnouncement();
  reconcileFeed();
}

function setLanguage(next) {
  lang = next === 'en' ? 'en' : 'vi';
  if (env.history && typeof env.history.replaceState === 'function') {
    if (lang === 'en') {
      env.history.replaceState(null, '', '#lang-en');
    } else {
      env.history.replaceState(null, '', env.window.location.pathname);
    }
  }
  applyLanguage();
}

function el(tag, className, text) {
  const node = env.document.createElement(tag);
  if (className) {
    node.className = className;
  }
  if (text !== undefined) {
    node.textContent = text;
  }
  return node;
}

function actionButton(text, className = '') {
  const node = el('button', `action-pill ${className}`.trim(), text);
  node.type = 'button';
  return node;
}

function displayName(userOrAuthor) {
  return (userOrAuthor && userOrAuthor.displayName) || 'NexaChat';
}

function initials(name) {
  const words = String(name || '').trim().split(/\s+/).filter(Boolean);
  if (!words.length) {
    return '?';
  }
  return (words[0][0] + (words.length > 1 ? words[words.length - 1][0] : '')).toUpperCase();
}

/**
 * Exact JS port of Android AvatarPalette.avatarPaletteIndex(name) (0..7).
 */
export function avatarPaletteIndex(name) {
  const trimmed = String(name || '').trim();
  if (!trimmed) {
    return 0;
  }
  const normalized = trimmed.toLowerCase();
  let hash = 0;
  for (let i = 0; i < normalized.length; i += 1) {
    hash = (Math.imul(hash, 31) + normalized.charCodeAt(i)) | 0;
  }
  return (hash & 0x7fffffff) % 8;
}

function createAvatarNode(userOrAuthor, sizeClass = '') {
  const name = displayName(userOrAuthor);
  const tone = avatarPaletteIndex(name);
  const node = el('span', `avatar-initial avatar-tone-${tone} ${sizeClass}`.trim(), initials(name));
  node.dataset.avatarName = name;
  const safePath = safeAvatarFilePath(userOrAuthor && userOrAuthor.avatarUrl);
  if (safePath) {
    node.dataset.avatarUrl = safePath;
    void loadAvatarIntoNode(safePath, node);
  }
  return node;
}

async function loadAvatarIntoNode(filePath, container) {
  const epoch = uiEpoch;
  if (!client || typeof client.requestFile !== 'function') {
    return;
  }
  let entry = avatarCache.get(filePath);
  try {
    let objectUrl = entry && entry.objectUrl;
    if (!objectUrl) {
      if (entry && entry.failed) {
        return;
      }
      if (entry && entry.promise) {
        objectUrl = await entry.promise;
      } else {
        const nextEntry = { promise: null, objectUrl: null, failed: false };
        nextEntry.promise = client.requestFile(filePath)
          .then((blob) => {
            if (!blob || !ALLOWED_IMAGE_MIME_TYPES.has(blob.type)) {
              throw new Error('unsupported avatar type');
            }
            if (avatarCache.get(filePath) !== nextEntry) {
              throw new Error('stale avatar request');
            }
            nextEntry.objectUrl = env.URL.createObjectURL(blob);
            return nextEntry.objectUrl;
          })
          .catch((error) => {
            nextEntry.failed = true;
            throw error;
          });
        avatarCache.set(filePath, nextEntry);
        objectUrl = await nextEntry.promise;
      }
    }
    if (epoch !== uiEpoch || !container.isConnected || container.dataset.avatarUrl !== filePath || !objectUrl) {
      return;
    }
    const existingImg = container.querySelector('img.avatar-img');
    if (existingImg && existingImg.src === objectUrl) {
      return;
    }
    const img = el('img', 'avatar-img');
    img.src = objectUrl;
    img.alt = '';
    img.addEventListener('error', () => {
      img.remove();
    });
    container.append(img);
  } catch {
    // Keep initials fallback silently on missing/unproxied/failed avatars.
  }
}

function revokeAllObjectUrls() {
  for (const cache of [imageCache, videoCache, avatarCache]) {
    for (const entry of cache.values()) {
      if (entry.objectUrl) {
        env.URL.revokeObjectURL(entry.objectUrl);
      }
    }
  }
  imageCache = new Map();
  videoCache = new Map();
  avatarCache = new Map();
}

function pruneMedia() {
  const wantedImages = new Set(posts.map((post) => imageUuid(post.image && post.image.url)).filter(Boolean));
  for (const [id, entry] of imageCache) {
    if (!wantedImages.has(id)) {
      if (entry.objectUrl) {
        env.URL.revokeObjectURL(entry.objectUrl);
      }
      imageCache.delete(id);
    }
  }
  const wantedVideos = new Set(posts.map((post) => videoUuid(post.video && post.video.url)).filter(Boolean));
  for (const [id, entry] of videoCache) {
    if (!wantedVideos.has(id)) {
      if (entry.objectUrl) {
        env.URL.revokeObjectURL(entry.objectUrl);
      }
      videoCache.delete(id);
    }
  }
}

async function loadImage(post, container) {
  const epoch = uiEpoch;
  const id = imageUuid(post.image && post.image.url);
  if (!id) {
    container.textContent = t('imageUnavailable');
    return;
  }
  const cached = imageCache.get(id);
  try {
    let objectUrl = cached && cached.objectUrl;
    if (!objectUrl) {
      const promise = cached && cached.promise;
      if (promise) {
        objectUrl = await promise;
      } else {
        const entry = { promise: null, objectUrl: null };
        entry.promise = client.request(canonicalImageRequestPath(post.image.url), { asBlob: true })
          .then((blob) => {
            if (!ALLOWED_IMAGE_MIME_TYPES.has(blob.type)) {
              throw new Error('unsupported image type');
            }
            if (imageCache.get(id) !== entry) {
              throw new Error('stale image request');
            }
            entry.objectUrl = env.URL.createObjectURL(blob);
            return entry.objectUrl;
          })
          .catch((error) => {
            if (imageCache.get(id) === entry) {
              imageCache.delete(id);
            }
            throw error;
          });
        imageCache.set(id, entry);
        objectUrl = await entry.promise;
      }
    }
    if (!posts.some((item) => item.id === post.id) || imageCache.get(id)?.objectUrl !== objectUrl || !container.isConnected) {
      return;
    }
    container.textContent = '';
    const image = el('img', 'post-image');
    image.src = objectUrl;
    image.alt = t('imageAlt');
    image.loading = 'lazy';
    if (post.image.width) {
      image.width = post.image.width;
    }
    if (post.image.height) {
      image.height = post.image.height;
    }
    container.append(image);
  } catch (error) {
    if (error instanceof SocialSessionError) {
      if (epoch === uiEpoch) {
        expireSession();
      }
      return;
    }
    if (epoch === uiEpoch && container.isConnected) {
      container.textContent = t('imageUnavailable');
    }
  }
}

function renderVideoArea(post, container) {
  container.replaceChildren();
  const id = videoUuid(post.video && post.video.url);
  const cached = id ? videoCache.get(id) : null;
  if (cached && cached.objectUrl) {
    appendVideoElement(container, cached.objectUrl, post);
    return;
  }
  const loadButton = actionButton(t('loadVideo'), 'video-load-button');
  loadButton.type = 'button';
  loadButton.addEventListener('click', () => void loadVideo(post, container));
  container.append(loadButton);
}

function appendVideoElement(container, objectUrl, post) {
  const video = env.document.createElement('video');
  video.className = 'post-video';
  video.controls = true;
  video.playsInline = true;
  video.preload = 'auto';
  video.src = objectUrl;
  video.setAttribute('aria-label', t('videoAlt'));
  video.addEventListener('error', () => {
    if (!container.isConnected || video.src !== objectUrl) {
      return;
    }
    showVideoFailure(container, post, () => {
      if (container.isConnected) {
        appendVideoElement(container, objectUrl, post);
      }
    }, true);
  });
  container.replaceChildren(video);
}

function showVideoFailure(container, post, retry, playbackFailed) {
  const hadFocus = container.contains(env.document.activeElement);
  container.replaceChildren();
  const message = el('p', 'video-error', playbackFailed ? t('videoPlaybackFailed') : t('videoUnavailable'));
  const retryButton = actionButton(t('retryVideo'), 'video-retry-button');
  retryButton.type = 'button';
  retryButton.addEventListener('click', () => retry());
  container.append(message, retryButton);
  if (hadFocus) {
    retryButton.focus();
  }
}

async function loadVideo(post, container) {
  const epoch = uiEpoch;
  const id = videoUuid(post.video && post.video.url);
  if (!id) {
    container.textContent = t('videoUnavailable');
    return;
  }
  container.textContent = t('videoLoading');
  const cached = videoCache.get(id);
  try {
    let objectUrl = cached && cached.objectUrl;
    if (!objectUrl) {
      const promise = cached && cached.promise;
      if (promise) {
        objectUrl = await promise;
      } else {
        const entry = { promise: null, objectUrl: null };
        entry.promise = client.request(canonicalVideoRequestPath(post.video.url), { asBlob: true })
          .then((blob) => {
            if (!ALLOWED_VIDEO_MIME_TYPES.has(blob.type)) {
              throw new Error('unsupported video type');
            }
            if (videoCache.get(id) !== entry) {
              throw new Error('stale video request');
            }
            entry.objectUrl = env.URL.createObjectURL(blob);
            return entry.objectUrl;
          })
          .catch((error) => {
            if (videoCache.get(id) === entry) {
              videoCache.delete(id);
            }
            throw error;
          });
        videoCache.set(id, entry);
        objectUrl = await entry.promise;
      }
    }
    if (!posts.some((item) => item.id === post.id) || videoCache.get(id)?.objectUrl !== objectUrl || !container.isConnected) {
      return;
    }
    const hadFocus = container.contains(env.document.activeElement);
    appendVideoElement(container, objectUrl, post);
    if (hadFocus) {
      const video = container.querySelector('video');
      if (video) {
        video.focus();
      }
    }
  } catch (error) {
    if (error instanceof SocialSessionError) {
      if (epoch === uiEpoch) {
        expireSession();
      }
      return;
    }
    if (epoch === uiEpoch && container.isConnected) {
      showVideoFailure(container, post, () => void loadVideo(post, container), false);
    }
  }
}

function updateUserIdentityChrome() {
  if (!account) {
    if (els.sessionAvatar) {
      els.sessionAvatar.replaceChildren();
    }
    if (els.composerAvatar) {
      els.composerAvatar.replaceChildren();
    }
    return;
  }
  const name = displayName(account);
  els.sessionUser.textContent = t('signedInAs', { name });
  if (els.composerAuthorName) {
    els.composerAuthorName.textContent = name;
  }
  if (els.sessionAvatar) {
    els.sessionAvatar.replaceChildren(createAvatarNode(account, 'avatar-sm'));
  }
  if (els.composerAvatar) {
    els.composerAvatar.replaceChildren(createAvatarNode(account));
  }
}

function setSignedIn(user) {
  uiEpoch += 1;
  account = user;
  applySurfaceState();
  els.refreshButton.hidden = false;
  updateUserIdentityChrome();
  els.loginIdentifier.value = '';
  els.loginPassword.value = '';
  setStatus(els.loginStatus, '');
  setStatus(els.feedStatus, '');
  applyLanguage();
}

function clearSocialState() {
  uiEpoch += 1;
  account = null;
  posts = [];
  nextCursor = null;
  feedLoading = false;
  feedRequestSeq += 1;
  searchQuery = '';
  if (searchDebounceTimer) {
    clearTimeout(searchDebounceTimer);
    searchDebounceTimer = null;
  }
  authorFilter = null;
  commentsState = new Map();
  commentDrafts = new Map();
  editingPosts = new Map();
  editingComments = new Map();
  openReactionPickers = new Set();
  postDraft = { id: env.crypto.randomUUID(), submittedBody: null, file: null };
  revokeAllObjectUrls();
  clearMediaSelection();
  if (els.feedSearchInput) {
    els.feedSearchInput.value = '';
  }
  if (els.feedSearchClear) {
    els.feedSearchClear.hidden = true;
  }
  renderAuthorFilterChip();
  updateUserIdentityChrome();
  els.feedList.replaceChildren();
  applySurfaceState();
  els.refreshButton.hidden = true;
  els.loadMoreButton.hidden = true;
  els.postBody.value = '';
  els.postBody.disabled = false;
  els.postMedia.disabled = false;
  els.mediaRemove.disabled = false;
  els.postSubmit.disabled = false;
  els.postSubmit.textContent = t('postButton');
  els.refreshButton.disabled = false;
  els.loadMoreButton.disabled = false;
  setStatus(els.feedStatus, '');
  setStatus(els.composerStatus, '');
  closeReport();
  closeAuthorDialog();
  updatePostCounter();
  applyLanguage();
}

function expireSession() {
  if (!account) {
    return;
  }
  clearSocialState();
  setStatus(els.loginStatus, 'sessionExpired');
}

function handleError(error, fallbackKey, statusElement) {
  if (error instanceof SocialSessionError) {
    expireSession();
    return;
  }
  if (error instanceof SocialApiError && error.payload && error.payload.error
      && error.payload.error.code === 'SOCIAL_FEATURE_DISABLED') {
    featureConfig = null;
    surfaceState = 'disabled';
    applySurfaceState();
    applyLanguage();
    // This error may also mean uploads alone are off. Re-read the current flags
    // and supersede any read started before the request was refused.
    void bootstrapConfig();
    return;
  }
  setStatus(statusElement, fallbackKey);
}

function updatePostCounter() {
  if (els.postCounter && els.postBody) {
    els.postCounter.textContent = `${els.postBody.value.length}/1000`;
  }
}

function updateReportDetailsCounter() {
  if (els.reportDetailsCounter && els.reportDetails) {
    els.reportDetailsCounter.textContent = `${els.reportDetails.value.length}/500`;
  }
}

function renderAuthorFilterChip() {
  if (!els.authorFilterChip || !els.authorFilterLabel) {
    return;
  }
  if (!authorFilter) {
    els.authorFilterChip.hidden = true;
    els.authorFilterLabel.textContent = '';
    return;
  }
  els.authorFilterLabel.textContent = t('authorFilterLabel', { name: authorFilter.displayName });
  els.authorFilterChip.hidden = false;
}

function buildPostsPath(cursor) {
  const params = new URLSearchParams();
  params.set('limit', '20');
  if (cursor) {
    params.set('cursor', cursor);
  }
  if (authorFilter && authorFilter.userId) {
    params.set('authorId', authorFilter.userId);
  }
  if (searchQuery) {
    params.set('q', searchQuery);
  }
  return `/posts?${params.toString()}`;
}

function emptyFeedStatusKey() {
  return searchQuery || authorFilter ? 'noSearchResults' : 'empty';
}

async function loadFeed(reset) {
  if (!account) {
    return;
  }
  if (!reset && feedLoading) {
    return;
  }
  const epoch = uiEpoch;
  const seq = ++feedRequestSeq;
  feedLoading = true;
  els.refreshButton.disabled = true;
  els.loadMoreButton.disabled = true;
  setStatus(els.feedStatus, 'loading', true);
  try {
    const cursor = reset ? null : nextCursor;
    const page = await client.request(buildPostsPath(cursor));
    if (!account || epoch !== uiEpoch || seq !== feedRequestSeq) {
      return;
    }
    const items = Array.isArray(page.items) ? page.items : [];
    posts = reset ? items : posts.concat(items.filter((item) => !posts.some((post) => post.id === item.id)));
    nextCursor = page.nextCursor || null;
    pruneMedia();
    reconcileFeed();
    setStatus(els.feedStatus, posts.length ? '' : emptyFeedStatusKey(), !posts.length);
  } catch (error) {
    if (epoch === uiEpoch && seq === feedRequestSeq) {
      handleError(error, 'feedError', els.feedStatus);
    }
  } finally {
    if (epoch === uiEpoch && seq === feedRequestSeq) {
      feedLoading = false;
      els.refreshButton.disabled = false;
      els.loadMoreButton.disabled = false;
      els.loadMoreButton.hidden = !nextCursor;
    }
  }
}

function heartSummary(post) {
  return (post.reactions || []).find((reaction) => reaction.emoji === HEART) || null;
}

function reconcileFeed() {
  if (!account) {
    els.feedList.replaceChildren();
    els.loadMoreButton.hidden = true;
    return;
  }
  const existingById = new Map();
  for (const child of Array.from(els.feedList.children)) {
    const id = child.dataset && child.dataset.postId;
    if (id) {
      existingById.set(id, child);
    }
  }
  const wantedIds = new Set(posts.map((post) => post.id));
  for (const [id, node] of existingById) {
    if (!wantedIds.has(id)) {
      node.remove();
      existingById.delete(id);
    }
  }

  let cursorNode = els.feedList.firstElementChild;
  for (const post of posts) {
    let card = existingById.get(post.id);
    if (!card) {
      card = createPostCard(post);
    } else {
      syncPostCard(card, post);
    }
    if (card !== cursorNode) {
      els.feedList.insertBefore(card, cursorNode);
    } else {
      cursorNode = cursorNode.nextElementSibling;
    }
  }
  els.loadMoreButton.hidden = !nextCursor;
}

function findCardForPost(postId) {
  for (const child of els.feedList.children) {
    if (child.dataset && child.dataset.postId === postId) {
      return child;
    }
  }
  return null;
}

function buildAuthorHeader(author, createdAt, editedAt) {
  const header = el('header', 'post-header');
  const trigger = el('button', 'author-trigger');
  trigger.type = 'button';
  trigger.append(createAvatarNode(author));
  const meta = el('div', 'author-meta');
  meta.append(el('span', 'author-name', displayName(author)));
  const metaLine = el('div', 'post-meta-line');
  const time = el('time', 'post-time', formatTime(createdAt));
  time.dateTime = createdAt || '';
  metaLine.append(time);
  const edited = el('span', 'edited-badge', t('edited'));
  edited.hidden = !editedAt;
  if (editedAt) {
    edited.title = formatTime(editedAt);
  }
  metaLine.append(edited);
  meta.append(metaLine);
  trigger.append(meta);
  if (author && author.id) {
    trigger.addEventListener('click', () => void openAuthorProfile(author.id, displayName(author), trigger));
  }
  header.append(trigger);
  return header;
}

function createPostCard(post) {
  const article = el('article', 'card post-card');
  article.id = `post-${post.id}`;
  article.dataset.postId = post.id;

  const header = buildAuthorHeader(post.author, post.createdAt, post.editedAt);
  const bodyWrap = el('div', 'post-body-wrap');
  const imageWrap = el('div', 'post-image-wrap');
  const videoWrap = el('div', 'post-video-wrap');
  const actions = el('div', 'post-actions');
  const pickerTray = el('div', 'reaction-picker-tray');
  pickerTray.hidden = true;
  const comments = createCommentsRegion(post);

  article.append(header, bodyWrap, imageWrap, videoWrap, actions, pickerTray, comments);
  syncPostCard(article, post);
  return article;
}

function syncPostCard(article, post) {
  article._post = post;
  // 1. Header metadata (author name & edited badge)
  const nameEl = article.querySelector('.post-header .author-name');
  if (nameEl) {
    nameEl.textContent = displayName(post.author);
  }
  const timeEl = article.querySelector('.post-header .post-time');
  if (timeEl) {
    timeEl.textContent = formatTime(post.createdAt);
    timeEl.dateTime = post.createdAt || '';
  }
  const editedEl = article.querySelector('.post-header .edited-badge');
  if (editedEl) {
    editedEl.hidden = !post.editedAt;
    editedEl.textContent = t('edited');
    if (post.editedAt) {
      editedEl.title = formatTime(post.editedAt);
    }
  }

  // 2. Body or inline post editor (preserve active inline edit textarea)
  const bodyWrap = article.querySelector('.post-body-wrap');
  const editState = editingPosts.get(post.id);
  if (editState) {
    let editForm = bodyWrap.querySelector('.inline-edit-form');
    if (!editForm) {
      editForm = createInlineEditForm({
        initialValue: editState.draft,
        maxLength: 1000,
        busy: editState.saving,
        errorKey: editState.error,
        onInput: (val) => {
          editState.draft = val;
        },
        onCancel: () => {
          editingPosts.delete(post.id);
          syncPostCard(article, post);
        },
        onSave: () => void savePostEdit(post, article),
      });
      bodyWrap.replaceChildren(editForm);
    } else {
      syncInlineEditForm(editForm, editState, 1000);
    }
  } else if (post.body) {
    let bodyP = bodyWrap.querySelector('.post-body');
    if (!bodyP) {
      bodyP = el('p', 'post-body');
      bodyWrap.replaceChildren(bodyP);
    }
    if (bodyP.textContent !== post.body) {
      bodyP.textContent = post.body;
    }
  } else {
    bodyWrap.replaceChildren();
  }

  // 3. Image (preserved if URL unchanged)
  const imageWrap = article.querySelector('.post-image-wrap');
  const nextImgUrl = (post.image && post.image.url) || '';
  if (nextImgUrl) {
    imageWrap.hidden = false;
    if (imageWrap.dataset.mediaUrl !== nextImgUrl) {
      imageWrap.dataset.mediaUrl = nextImgUrl;
      imageWrap.textContent = t('loading');
      void loadImage(post, imageWrap);
    }
  } else {
    imageWrap.hidden = true;
    imageWrap.dataset.mediaUrl = '';
    imageWrap.replaceChildren();
  }

  // 4. Video (preserved if URL unchanged — never interrupts playback)
  const videoWrap = article.querySelector('.post-video-wrap');
  const nextVidUrl = (post.video && post.video.url) || '';
  if (nextVidUrl) {
    videoWrap.hidden = false;
    if (videoWrap.dataset.mediaUrl !== nextVidUrl) {
      videoWrap.dataset.mediaUrl = nextVidUrl;
      renderVideoArea(post, videoWrap);
    }
  } else {
    videoWrap.hidden = true;
    videoWrap.dataset.mediaUrl = '';
    videoWrap.replaceChildren();
  }

  // 5. Actions & Reactions
  const actions = article.querySelector('.post-actions');
  syncPostActions(actions, article, post);

  // 6. Reaction picker tray
  const pickerTray = article.querySelector('.reaction-picker-tray');
  syncReactionPickerTray(pickerTray, article, post);

  // 7. Comments region (reconciled in place; never wipes draft/focus)
  const commentsRegion = article.querySelector('.comments-region');
  syncCommentsRegion(commentsRegion, post);
}

function syncPostActions(actions, article, post) {
  const heart = heartSummary(post);
  const heartMine = Boolean(heart && heart.mine);
  const heartText = `${HEART} ${heart ? heart.count : 0}`;
  const commentsOpen = Boolean(commentState(post.id).open);
  const pickerOpen = openReactionPickers.has(post.id);

  let heartBtn = actions.querySelector('[data-action="heart"]');
  let commentBtn = actions.querySelector('[data-action="comments"]');
  let pickerBtn = actions.querySelector('[data-action="reaction-picker"]');
  let reactionBar = actions.querySelector('.reaction-pills-group');
  let copyBtn = actions.querySelector('[data-action="copy"]');
  let editBtn = actions.querySelector('[data-action="edit"]');
  let deleteBtn = actions.querySelector('[data-action="delete"]');
  let reportBtn = actions.querySelector('[data-action="report"]');

  if (!heartBtn) {
    actions.replaceChildren();
    heartBtn = actionButton(heartText, heartMine ? 'active' : '');
    heartBtn.dataset.action = 'heart';
    heartBtn.addEventListener('click', () => void toggleReaction(article._post, HEART, heartBtn));

    commentBtn = actionButton(commentLabel(post));
    commentBtn.dataset.action = 'comments';
    commentBtn.addEventListener('click', () => void toggleComments(article._post));

    pickerBtn = el('button', 'reaction-picker-trigger', '😊+');
    pickerBtn.type = 'button';
    pickerBtn.dataset.action = 'reaction-picker';
    pickerBtn.addEventListener('click', () => {
      if (openReactionPickers.has(article._post.id)) {
        openReactionPickers.delete(article._post.id);
      } else {
        openReactionPickers.add(article._post.id);
      }
      syncPostCard(article, article._post);
    });

    reactionBar = el('span', 'reaction-pills-group');

    copyBtn = actionButton(t('copy'));
    copyBtn.dataset.action = 'copy';
    copyBtn.addEventListener('click', () => void copyPost(article._post));

    actions.append(heartBtn, commentBtn, pickerBtn, reactionBar, copyBtn);

    if (post.mine) {
      editBtn = actionButton(t('edit'));
      editBtn.dataset.action = 'edit';
      editBtn.addEventListener('click', () => {
        const cur = article._post;
        if (editingPosts.has(cur.id)) {
          editingPosts.delete(cur.id);
        } else {
          editingPosts.set(cur.id, { draft: cur.body || '', saving: false, error: '' });
        }
        syncPostCard(article, cur);
        const ta = article.querySelector('.post-body-wrap textarea');
        if (ta) {
          ta.focus();
        }
      });

      deleteBtn = actionButton(t('delete'), 'danger');
      deleteBtn.dataset.action = 'delete';
      deleteBtn.addEventListener('click', () => void deletePost(article._post, deleteBtn));
      actions.append(editBtn, deleteBtn);
    } else {
      reportBtn = actionButton(t('report'));
      reportBtn.dataset.action = 'report';
      reportBtn.addEventListener('click', () => openReport('POST', article._post.id));
      actions.append(reportBtn);
    }
  }

  heartBtn.textContent = heartText;
  heartBtn.classList.toggle('active', heartMine);
  heartBtn.setAttribute('aria-label', heartMine ? t('removeReact') : t('react'));
  heartBtn.setAttribute('aria-pressed', String(heartMine));
  heartBtn.disabled = false;

  commentBtn.textContent = commentLabel(post);
  commentBtn.setAttribute('aria-expanded', String(commentsOpen));

  pickerBtn.setAttribute('aria-label', t('addReaction'));
  pickerBtn.setAttribute('aria-expanded', String(pickerOpen));
  pickerBtn.classList.toggle('active', pickerOpen);

  copyBtn.textContent = t('copy');
  if (editBtn) {
    editBtn.textContent = t('edit');
  }
  if (deleteBtn) {
    deleteBtn.textContent = t('delete');
  }
  if (reportBtn) {
    reportBtn.textContent = t('report');
  }

  // Sync non-heart reaction pills in place
  const nonHeart = (post.reactions || []).filter((r) => r.emoji !== HEART && r.count > 0);
  const existingPills = new Map();
  for (const pill of Array.from(reactionBar.children)) {
    if (pill.dataset && pill.dataset.emoji) {
      existingPills.set(pill.dataset.emoji, pill);
    }
  }
  const wantedEmojis = new Set(nonHeart.map((r) => r.emoji));
  for (const [emoji, pill] of existingPills) {
    if (!wantedEmojis.has(emoji)) {
      pill.remove();
      existingPills.delete(emoji);
    }
  }
  let cursorPill = reactionBar.firstElementChild;
  for (const reaction of nonHeart) {
    let pill = existingPills.get(reaction.emoji);
    if (!pill) {
      pill = el('button', 'reaction-pill');
      pill.type = 'button';
      pill.dataset.emoji = reaction.emoji;
      const emojiVal = reaction.emoji;
      pill.addEventListener('click', () => void toggleReaction(article._post, emojiVal, pill));
    }
    pill.textContent = `${reaction.emoji} ${reaction.count}`;
    pill.classList.toggle('active', Boolean(reaction.mine));
    pill.setAttribute('aria-pressed', String(Boolean(reaction.mine)));
    pill.setAttribute('aria-label', t('reactWith', { emoji: reaction.emoji }));
    pill.disabled = false;
    if (pill !== cursorPill) {
      reactionBar.insertBefore(pill, cursorPill);
    } else {
      cursorPill = cursorPill.nextElementSibling;
    }
  }
}

function syncReactionPickerTray(tray, article, post) {
  const open = openReactionPickers.has(post.id);
  tray.hidden = !open;
  if (!open) {
    return;
  }
  const reactionsByEmoji = new Map((post.reactions || []).map((r) => [r.emoji, r]));
  if (tray.children.length !== REACTION_EMOJIS.length) {
    tray.replaceChildren();
    for (const emoji of REACTION_EMOJIS) {
      const btn = el('button', 'reaction-emoji-option', emoji);
      btn.type = 'button';
      btn.dataset.emoji = emoji;
      btn.addEventListener('click', () => {
        openReactionPickers.delete(article._post.id);
        void toggleReaction(article._post, emoji, btn);
      });
      tray.append(btn);
    }
  }
  for (const btn of Array.from(tray.children)) {
    const emoji = btn.dataset.emoji;
    const summary = reactionsByEmoji.get(emoji);
    const mine = Boolean(summary && summary.mine);
    btn.classList.toggle('active', mine);
    btn.setAttribute('aria-pressed', String(mine));
    btn.setAttribute('aria-label', t('reactWith', { emoji }));
  }
}

function createInlineEditForm({ initialValue, maxLength, busy, errorKey, onInput, onCancel, onSave }) {
  const form = el('div', 'inline-edit-form');
  const textarea = el('textarea', 'text-input inline-edit-input');
  textarea.maxLength = maxLength;
  textarea.rows = 3;
  textarea.value = initialValue;
  textarea.disabled = busy;
  const counter = el('span', 'muted post-counter', `${initialValue.length}/${maxLength}`);
  textarea.addEventListener('input', () => {
    counter.textContent = `${textarea.value.length}/${maxLength}`;
    onInput(textarea.value);
  });
  const status = el('p', 'status-message');
  status.setAttribute('role', 'alert');
  setStatus(status, errorKey);

  const actionsRow = el('div', 'inline-edit-actions');
  const btnGroup = el('div', 'inline-edit-buttons');
  const cancelBtn = el('button', 'button button-secondary', t('cancel'));
  cancelBtn.type = 'button';
  cancelBtn.dataset.editAction = 'cancel';
  cancelBtn.disabled = busy;
  cancelBtn.addEventListener('click', onCancel);

  const saveBtn = el('button', 'button button-primary', busy ? t('saving') : t('save'));
  saveBtn.type = 'button';
  saveBtn.dataset.editAction = 'save';
  saveBtn.disabled = busy;
  saveBtn.addEventListener('click', onSave);

  btnGroup.append(cancelBtn, saveBtn);
  actionsRow.append(counter, btnGroup);
  form.append(textarea, status, actionsRow);
  return form;
}

function syncInlineEditForm(form, editState, maxLength) {
  const textarea = form.querySelector('textarea');
  const counter = form.querySelector('.post-counter');
  const status = form.querySelector('.status-message');
  const cancelBtn = form.querySelector('[data-edit-action="cancel"]');
  const saveBtn = form.querySelector('[data-edit-action="save"]');
  if (textarea) {
    textarea.disabled = editState.saving;
  }
  if (counter && textarea) {
    counter.textContent = `${textarea.value.length}/${maxLength}`;
  }
  if (status) {
    setStatus(status, editState.error);
  }
  if (cancelBtn) {
    cancelBtn.textContent = t('cancel');
    cancelBtn.disabled = editState.saving;
  }
  if (saveBtn) {
    saveBtn.textContent = editState.saving ? t('saving') : t('save');
    saveBtn.disabled = editState.saving;
  }
}

async function savePostEdit(post, article) {
  const editState = editingPosts.get(post.id);
  if (!editState || editState.saving) {
    return;
  }
  const nextBody = editState.draft.trim();
  if (!nextBody) {
    editState.error = 'editEmpty';
    syncPostCard(article, post);
    const ta = article.querySelector('.post-body-wrap textarea');
    if (ta) {
      ta.focus();
    }
    return;
  }
  const epoch = uiEpoch;
  editState.saving = true;
  editState.error = '';
  syncPostCard(article, post);
  try {
    const updated = await client.request(`/posts/${encodeURIComponent(post.id)}`, {
      method: 'PATCH',
      body: { body: nextBody },
    });
    if (epoch !== uiEpoch || !account) {
      return;
    }
    Object.assign(post, updated);
    editingPosts.delete(post.id);
    syncPostCard(article, post);
  } catch (error) {
    if (epoch !== uiEpoch) {
      return;
    }
    editState.saving = false;
    if (error instanceof SocialSessionError) {
      expireSession();
      return;
    }
    if (error instanceof SocialApiError && error.payload?.error?.code === 'SOCIAL_FEATURE_DISABLED') {
      handleError(error, 'editFailed', els.feedStatus);
      return;
    }
    editState.error = 'editFailed';
    syncPostCard(article, post);
  }
}

async function copyPost(post) {
  const permalink = `${env.window.location.origin || ''}${env.window.location.pathname || '/social/'}#post-${post.id}`;
  const textToCopy = (post.body && post.body.trim()) ? post.body.trim() : permalink;
  try {
    if (env.navigator && env.navigator.clipboard && typeof env.navigator.clipboard.writeText === 'function') {
      await env.navigator.clipboard.writeText(textToCopy);
      setStatus(els.feedStatus, 'copied', true);
    } else {
      setStatus(els.feedStatus, 'copyFailed');
    }
  } catch {
    setStatus(els.feedStatus, 'copyFailed');
  }
}

function formatTime(value) {
  const date = value ? new Date(value) : null;
  if (!date || Number.isNaN(date.valueOf())) {
    return '';
  }
  return new Intl.DateTimeFormat(t('locale'), { dateStyle: 'medium', timeStyle: 'short' }).format(date);
}

function commentLabel(post) {
  if (!post.commentCount) {
    return t('commentsZero');
  }
  return post.commentCount === 1 ? t('commentsOne') : t('comments', { count: post.commentCount });
}

function commentState(postId) {
  if (!commentsState.has(postId)) {
    commentsState.set(postId, { open: false, loaded: false, loading: false, items: [], nextCursor: null, error: '' });
  }
  return commentsState.get(postId);
}

function commentDraft(postId) {
  if (!commentDrafts.has(postId)) {
    commentDrafts.set(postId, { body: '', submittedBody: null, clientCommentId: env.crypto.randomUUID(), sending: false });
  }
  return commentDrafts.get(postId);
}

async function toggleReaction(post, emoji, button) {
  if (!account) {
    return;
  }
  const epoch = uiEpoch;
  if (button) {
    button.disabled = true;
  }
  const existing = (post.reactions || []).find((r) => r.emoji === emoji);
  const active = Boolean(existing && existing.mine);
  try {
    const result = await client.request(`/posts/${encodeURIComponent(post.id)}/reactions/${encodeURIComponent(emoji)}`, {
      method: active ? 'DELETE' : 'PUT',
    });
    if (epoch === uiEpoch && account) {
      post.reactions = result.reactions || [];
      const card = findCardForPost(post.id);
      if (card) {
        syncPostCard(card, post);
      }
    }
  } catch (error) {
    if (epoch === uiEpoch) {
      handleError(error, 'reactionFailed', els.feedStatus);
      const card = findCardForPost(post.id);
      if (card && account) {
        syncPostCard(card, post);
      } else if (button) {
        button.disabled = false;
      }
    }
  }
}

async function deletePost(post, button) {
  if (!env.window.confirm(t('confirmDeletePost'))) {
    return;
  }
  const epoch = uiEpoch;
  button.disabled = true;
  try {
    await client.request(`/posts/${encodeURIComponent(post.id)}`, { method: 'DELETE' });
    if (epoch === uiEpoch && account) {
      posts = posts.filter((item) => item.id !== post.id);
      editingPosts.delete(post.id);
      openReactionPickers.delete(post.id);
      pruneMedia();
      reconcileFeed();
      setStatus(els.feedStatus, posts.length ? 'deleted' : emptyFeedStatusKey(), true);
    }
  } catch (error) {
    if (epoch === uiEpoch) {
      handleError(error, 'deleteFailed', els.feedStatus);
      button.disabled = false;
    }
  }
}

async function toggleComments(post) {
  const state = commentState(post.id);
  state.open = !state.open;
  const card = findCardForPost(post.id);
  if (card) {
    syncPostCard(card, post);
  }
  if (state.open && !state.loaded && !state.loading) {
    await loadComments(post, true);
  }
}

async function loadComments(post, reset) {
  const state = commentState(post.id);
  if (state.loading) {
    return;
  }
  const epoch = uiEpoch;
  state.loading = true;
  state.error = '';
  const cardBefore = findCardForPost(post.id);
  if (cardBefore) {
    syncCommentsRegion(cardBefore.querySelector('.comments-region'), post);
  }
  try {
    const cursor = reset ? null : state.nextCursor;
    const suffix = cursor ? `&cursor=${encodeURIComponent(cursor)}` : '';
    const page = await client.request(`/posts/${encodeURIComponent(post.id)}/comments?limit=20${suffix}`);
    if (epoch !== uiEpoch || !account) {
      return;
    }
    const items = Array.isArray(page.items) ? page.items : [];
    state.items = reset ? items : state.items.concat(items.filter((item) => !state.items.some((comment) => comment.id === item.id)));
    state.nextCursor = page.nextCursor || null;
    state.loaded = true;
  } catch (error) {
    if (epoch !== uiEpoch) {
      return;
    }
    if (error instanceof SocialSessionError) {
      expireSession();
      return;
    }
    if (error instanceof SocialApiError && error.payload?.error?.code === 'SOCIAL_FEATURE_DISABLED') {
      handleError(error, 'commentsError', els.feedStatus);
      return;
    }
    state.error = 'commentsError';
  } finally {
    state.loading = false;
    if (epoch === uiEpoch && account) {
      const cardAfter = findCardForPost(post.id);
      if (cardAfter) {
        syncCommentsRegion(cardAfter.querySelector('.comments-region'), post);
      }
    }
  }
}

function createCommentsRegion(post) {
  const region = el('section', 'comments-region');
  region.hidden = !commentState(post.id).open;

  const errorBanner = el('div', 'comments-error-banner');
  errorBanner.setAttribute('role', 'alert');
  errorBanner.hidden = true;
  const errorText = el('span', 'comments-error-text');
  const retryBtn = actionButton(t('retry'), 'comments-retry-button');
  retryBtn.addEventListener('click', () => {
    const curPost = region.closest('.post-card')._post;
    const st = commentState(curPost.id);
    st.error = '';
    void loadComments(curPost, !st.loaded);
  });
  errorBanner.append(errorText, retryBtn);

  const list = el('div', 'comment-list');
  const emptyNotice = el('p', 'muted comments-status-text');
  emptyNotice.hidden = true;

  const moreBtn = actionButton(t('loadMore'), 'comments-load-more');
  moreBtn.hidden = true;
  moreBtn.addEventListener('click', () => {
    const curPost = region.closest('.post-card')._post;
    void loadComments(curPost, false);
  });

  const draft = commentDraft(post.id);
  const composerWrap = el('div', 'comment-composer');
  const label = el('label', 'field-label', t('commentLabel'));
  const inputId = `comment-input-${post.id}`;
  label.htmlFor = inputId;
  const input = el('textarea', 'text-input comment-input');
  input.id = inputId;
  input.maxLength = 500;
  input.rows = 2;
  input.placeholder = t('commentPlaceholder');
  input.value = draft.body;
  const row = el('div', 'comment-form-row');
  const counter = el('span', 'muted comment-counter', `${draft.body.length}/500`);
  input.addEventListener('input', () => {
    const curDraft = commentDraft(post.id);
    curDraft.body = input.value;
    counter.textContent = `${input.value.length}/500`;
  });
  const send = el('button', 'button button-primary', t('commentButton'));
  send.type = 'button';
  send.addEventListener('click', () => {
    const curPost = region.closest('.post-card')._post;
    void submitComment(curPost, input, send);
  });
  row.append(counter, send);
  composerWrap.append(label, input, row);

  region.append(errorBanner, list, emptyNotice, moreBtn, composerWrap);
  return region;
}

function syncCommentsRegion(region, post) {
  if (!region) {
    return;
  }
  const state = commentState(post.id);
  region.hidden = !state.open;
  if (!state.open) {
    return;
  }

  const errorBanner = region.querySelector('.comments-error-banner');
  const errorText = region.querySelector('.comments-error-text');
  const retryBtn = region.querySelector('.comments-retry-button');
  if (state.error) {
    errorBanner.hidden = false;
    errorText.textContent = t(state.error);
    retryBtn.textContent = t('retry');
  } else {
    errorBanner.hidden = true;
    errorText.textContent = '';
  }

  const list = region.querySelector('.comment-list');
  const emptyNotice = region.querySelector('.comments-status-text');
  if (state.loading && !state.items.length) {
    list.replaceChildren();
    emptyNotice.hidden = false;
    emptyNotice.textContent = t('loading');
  } else if (!state.items.length) {
    list.replaceChildren();
    emptyNotice.hidden = Boolean(state.error);
    emptyNotice.textContent = state.error ? '' : t('commentEmpty');
  } else {
    emptyNotice.hidden = true;
    reconcileCommentList(list, post, state.items);
  }

  const moreBtn = region.querySelector('.comments-load-more');
  moreBtn.hidden = !state.nextCursor;
  moreBtn.disabled = state.loading;
  moreBtn.textContent = t('loadMore');

  const draft = commentDraft(post.id);
  const label = region.querySelector('.comment-composer .field-label');
  const input = region.querySelector('.comment-input');
  const counter = region.querySelector('.comment-counter');
  const send = region.querySelector('.comment-composer .button-primary');
  if (label) {
    label.textContent = t('commentLabel');
  }
  if (input) {
    input.placeholder = t('commentPlaceholder');
    input.disabled = draft.sending;
    if (input.value !== draft.body && env.document.activeElement !== input) {
      input.value = draft.body;
    }
  }
  if (counter && input) {
    counter.textContent = `${input.value.length}/500`;
  }
  if (send) {
    send.disabled = draft.sending;
    send.textContent = draft.sending ? t('commenting') : t('commentButton');
  }
}

function reconcileCommentList(list, post, items) {
  const existingById = new Map();
  for (const child of Array.from(list.children)) {
    if (child.dataset && child.dataset.commentId) {
      existingById.set(child.dataset.commentId, child);
    }
  }
  const wanted = new Set(items.map((c) => c.id));
  for (const [id, node] of existingById) {
    if (!wanted.has(id)) {
      node.remove();
      existingById.delete(id);
    }
  }
  let cursor = list.firstElementChild;
  for (const comment of items) {
    let card = existingById.get(comment.id);
    if (!card) {
      card = createCommentCard(post, comment);
    } else {
      syncCommentCard(card, post, comment);
    }
    if (card !== cursor) {
      list.insertBefore(card, cursor);
    } else {
      cursor = cursor.nextElementSibling;
    }
  }
}

function createCommentCard(post, comment) {
  const card = el('article', 'comment-card');
  card.dataset.commentId = comment.id;

  const header = el('header', 'post-header');
  const trigger = el('button', 'author-trigger');
  trigger.type = 'button';
  trigger.append(createAvatarNode(comment.author, 'avatar-sm'));
  const meta = el('div', 'author-meta');
  meta.append(el('span', 'author-name', displayName(comment.author)));
  const metaLine = el('div', 'post-meta-line');
  const time = el('time', 'post-time', formatTime(comment.createdAt));
  time.dateTime = comment.createdAt || '';
  metaLine.append(time);
  const edited = el('span', 'edited-badge', t('edited'));
  edited.hidden = !comment.editedAt;
  metaLine.append(edited);
  meta.append(metaLine);
  trigger.append(meta);
  if (comment.author && comment.author.id) {
    trigger.addEventListener('click', () => void openAuthorProfile(comment.author.id, displayName(comment.author), trigger));
  }
  header.append(trigger);

  const bodyWrap = el('div', 'comment-body-wrap');
  const actions = el('div', 'comment-actions');
  card.append(header, bodyWrap, actions);
  syncCommentCard(card, post, comment);
  return card;
}

function syncCommentCard(card, post, comment) {
  card._post = post;
  card._comment = comment;

  const nameEl = card.querySelector('.author-name');
  if (nameEl) {
    nameEl.textContent = displayName(comment.author);
  }
  const timeEl = card.querySelector('.post-time');
  if (timeEl) {
    timeEl.textContent = formatTime(comment.createdAt);
    timeEl.dateTime = comment.createdAt || '';
  }
  const editedEl = card.querySelector('.edited-badge');
  if (editedEl) {
    editedEl.hidden = !comment.editedAt;
    editedEl.textContent = t('edited');
    if (comment.editedAt) {
      editedEl.title = formatTime(comment.editedAt);
    }
  }

  const bodyWrap = card.querySelector('.comment-body-wrap');
  const editState = editingComments.get(comment.id);
  if (editState) {
    let editForm = bodyWrap.querySelector('.inline-edit-form');
    if (!editForm) {
      editForm = createInlineEditForm({
        initialValue: editState.draft,
        maxLength: 500,
        busy: editState.saving,
        errorKey: editState.error,
        onInput: (val) => {
          editState.draft = val;
        },
        onCancel: () => {
          editingComments.delete(comment.id);
          syncCommentCard(card, card._post, card._comment);
        },
        onSave: () => void saveCommentEdit(card._post, card._comment, card),
      });
      bodyWrap.replaceChildren(editForm);
    } else {
      syncInlineEditForm(editForm, editState, 500);
    }
  } else {
    let p = bodyWrap.querySelector('.comment-body');
    if (!p) {
      p = el('p', 'comment-body');
      bodyWrap.replaceChildren(p);
    }
    if (p.textContent !== (comment.body || '')) {
      p.textContent = comment.body || '';
    }
  }

  const actions = card.querySelector('.comment-actions');
  if (!actions.children.length) {
    if (comment.mine) {
      const editButton = actionButton(t('edit'));
      editButton.dataset.action = 'edit-comment';
      editButton.addEventListener('click', () => {
        const cur = card._comment;
        if (editingComments.has(cur.id)) {
          editingComments.delete(cur.id);
        } else {
          editingComments.set(cur.id, { draft: cur.body || '', saving: false, error: '' });
        }
        syncCommentCard(card, card._post, cur);
        const ta = card.querySelector('.comment-body-wrap textarea');
        if (ta) {
          ta.focus();
        }
      });
      const deleteButton = actionButton(t('delete'), 'danger');
      deleteButton.dataset.action = 'delete-comment';
      deleteButton.addEventListener('click', () => void deleteComment(card._post, card._comment, deleteButton));
      actions.append(editButton, deleteButton);
    } else {
      const reportButton = actionButton(t('report'));
      reportButton.dataset.action = 'report-comment';
      reportButton.addEventListener('click', () => openReport('COMMENT', card._comment.id));
      actions.append(reportButton);
    }
  } else {
    const editBtn = actions.querySelector('[data-action="edit-comment"]');
    const deleteBtn = actions.querySelector('[data-action="delete-comment"]');
    const reportBtn = actions.querySelector('[data-action="report-comment"]');
    if (editBtn) editBtn.textContent = t('edit');
    if (deleteBtn) deleteBtn.textContent = t('delete');
    if (reportBtn) reportBtn.textContent = t('report');
  }
}

async function saveCommentEdit(post, comment, card) {
  const editState = editingComments.get(comment.id);
  if (!editState || editState.saving) {
    return;
  }
  const nextBody = editState.draft.trim();
  if (!nextBody) {
    editState.error = 'editEmpty';
    syncCommentCard(card, post, comment);
    const ta = card.querySelector('.comment-body-wrap textarea');
    if (ta) {
      ta.focus();
    }
    return;
  }
  const epoch = uiEpoch;
  editState.saving = true;
  editState.error = '';
  syncCommentCard(card, post, comment);
  try {
    const updated = await client.request(`/comments/${encodeURIComponent(comment.id)}`, {
      method: 'PATCH',
      body: { body: nextBody },
    });
    if (epoch !== uiEpoch || !account) {
      return;
    }
    Object.assign(comment, updated);
    editingComments.delete(comment.id);
    syncCommentCard(card, post, comment);
  } catch (error) {
    if (epoch !== uiEpoch) {
      return;
    }
    editState.saving = false;
    if (error instanceof SocialSessionError) {
      expireSession();
      return;
    }
    if (error instanceof SocialApiError && error.payload?.error?.code === 'SOCIAL_FEATURE_DISABLED') {
      handleError(error, 'editFailed', els.feedStatus);
      return;
    }
    editState.error = 'editFailed';
    syncCommentCard(card, post, comment);
  }
}

async function submitComment(post, input, button) {
  const draft = commentDraft(post.id);
  const body = input.value.trim();
  if (!body) {
    setStatus(els.feedStatus, 'commentValidation');
    input.focus();
    return;
  }
  if (draft.submittedBody !== null && draft.submittedBody !== body) {
    draft.clientCommentId = env.crypto.randomUUID();
  }
  draft.submittedBody = body;
  const epoch = uiEpoch;
  draft.sending = true;
  button.disabled = true;
  input.disabled = true;
  button.textContent = t('commenting');
  try {
    await client.request(`/posts/${encodeURIComponent(post.id)}/comments`, {
      method: 'POST',
      body: { body, clientCommentId: draft.clientCommentId },
    });
    const freshPost = await client.request(`/posts/${encodeURIComponent(post.id)}`);
    if (epoch !== uiEpoch || !account) {
      return;
    }
    Object.assign(post, freshPost);
    input.value = '';
    commentDrafts.set(post.id, {
      body: '',
      submittedBody: null,
      clientCommentId: env.crypto.randomUUID(),
      sending: false,
    });
    const state = commentState(post.id);
    state.loaded = false;
    state.items = [];
    state.nextCursor = null;
    state.error = '';
    await loadComments(post, true);
    if (epoch === uiEpoch && account) {
      const card = findCardForPost(post.id);
      if (card) {
        syncPostCard(card, post);
      }
    }
  } catch (error) {
    draft.sending = false;
    if (epoch === uiEpoch) {
      handleError(error, 'commentFailed', els.feedStatus);
      if (account) {
        const card = findCardForPost(post.id);
        if (card) {
          syncPostCard(card, post);
        }
      }
    }
  }
}

async function deleteComment(post, comment, button) {
  if (!env.window.confirm(t('confirmDeleteComment'))) {
    return;
  }
  const epoch = uiEpoch;
  button.disabled = true;
  try {
    await client.request(`/comments/${encodeURIComponent(comment.id)}`, { method: 'DELETE' });
    if (epoch === uiEpoch && account) {
      const state = commentState(post.id);
      state.items = state.items.filter((item) => item.id !== comment.id);
      editingComments.delete(comment.id);
      post.commentCount = Math.max(0, post.commentCount - 1);
      const card = findCardForPost(post.id);
      if (card) {
        syncPostCard(card, post);
      }
    }
  } catch (error) {
    if (epoch === uiEpoch) {
      handleError(error, 'deleteFailed', els.feedStatus);
      button.disabled = false;
    }
  }
}

function openReport(targetType, targetId) {
  reportTarget = { targetType, targetId };
  reportReturnFocus = env.document.activeElement && typeof env.document.activeElement.focus === 'function'
    ? env.document.activeElement
    : null;
  els.reportStatus.textContent = '';
  els.reportSubmit.disabled = false;
  els.reportReason.value = 'SPAM';
  if (els.reportDetails) {
    els.reportDetails.value = '';
    updateReportDetailsCounter();
  }
  if (!els.reportDialog.open && typeof els.reportDialog.showModal === 'function') {
    els.reportDialog.showModal();
  } else {
    els.reportDialog.setAttribute('open', '');
  }
  els.reportReason.focus();
}

function restoreReportFocus() {
  if (reportReturnFocus && reportReturnFocus.isConnected) {
    reportReturnFocus.focus();
  }
  reportReturnFocus = null;
}

function closeReport() {
  reportTarget = null;
  if (els.reportDialog && els.reportDialog.open) {
    if (typeof els.reportDialog.close === 'function') {
      els.reportDialog.close();
    } else {
      els.reportDialog.removeAttribute('open');
    }
  }
  restoreReportFocus();
}

async function submitReport() {
  if (!reportTarget) {
    return;
  }
  const epoch = uiEpoch;
  els.reportSubmit.disabled = true;
  setStatus(els.reportStatus, 'reporting', true);
  const detailsText = els.reportDetails ? els.reportDetails.value.trim() : '';
  const body = {
    targetType: reportTarget.targetType,
    targetId: reportTarget.targetId,
    reason: els.reportReason.value,
  };
  if (detailsText) {
    body.details = detailsText;
  }
  try {
    await client.request('/reports', {
      method: 'POST',
      body,
    });
    closeReport();
    if (epoch === uiEpoch && account) {
      setStatus(els.feedStatus, 'reportSent', true);
    }
  } catch (error) {
    if (epoch !== uiEpoch) {
      return;
    }
    els.reportSubmit.disabled = false;
    if (error instanceof SocialSessionError) {
      closeReport();
      expireSession();
    } else {
      setStatus(els.reportStatus, 'reportFailed');
    }
  }
}

async function openAuthorProfile(authorId, fallbackName, triggerEl) {
  if (!account || !els.authorDialog) {
    return;
  }
  const epoch = uiEpoch;
  const seq = ++authorDialogSeq;
  authorDialogProfile = null;
  authorDialogReturnFocus = triggerEl && typeof triggerEl.focus === 'function' ? triggerEl : null;

  els.authorDialogAvatar.replaceChildren(createAvatarNode({ displayName: fallbackName }, 'avatar-lg'));
  els.authorDialogTitle.textContent = fallbackName;
  els.authorDialogCount.textContent = t('loading');
  els.authorDialogFilter.hidden = true;
  setStatus(els.authorDialogStatus, '');

  if (!els.authorDialog.open && typeof els.authorDialog.showModal === 'function') {
    els.authorDialog.showModal();
  } else {
    els.authorDialog.setAttribute('open', '');
  }
  els.authorDialogClose.focus();

  try {
    const profile = await client.request(`/authors/${encodeURIComponent(authorId)}`);
    if (epoch !== uiEpoch || seq !== authorDialogSeq || !account) {
      return;
    }
    authorDialogProfile = profile;
    const name = displayName(profile);
    els.authorDialogAvatar.replaceChildren(createAvatarNode(profile, 'avatar-lg'));
    els.authorDialogTitle.textContent = name;
    const count = Number(profile.postCount || 0);
    els.authorDialogCount.textContent = count === 1
      ? t('authorPostCountOne')
      : t('authorPostCount', { count });
    els.authorDialogFilter.hidden = false;
  } catch (error) {
    if (epoch !== uiEpoch || seq !== authorDialogSeq) {
      return;
    }
    if (error instanceof SocialSessionError) {
      closeAuthorDialog();
      expireSession();
      return;
    }
    els.authorDialogCount.textContent = '';
    setStatus(els.authorDialogStatus, 'authorProfileError');
  }
}

function closeAuthorDialog() {
  authorDialogSeq += 1;
  authorDialogProfile = null;
  if (els.authorDialog && els.authorDialog.open) {
    if (typeof els.authorDialog.close === 'function') {
      els.authorDialog.close();
    } else {
      els.authorDialog.removeAttribute('open');
    }
  }
  if (authorDialogReturnFocus && authorDialogReturnFocus.isConnected) {
    authorDialogReturnFocus.focus();
  }
  authorDialogReturnFocus = null;
}

function applyAuthorFilter(profile) {
  if (!profile || !profile.userId) {
    return;
  }
  authorFilter = {
    userId: profile.userId,
    displayName: displayName(profile),
  };
  renderAuthorFilterChip();
  closeAuthorDialog();
  void loadFeed(true);
}

function formatBytes(size) {
  if (!Number.isFinite(size) || size <= 0) {
    return '';
  }
  if (size < 1024) {
    return `${size} B`;
  }
  if (size < 1024 * 1024) {
    return `${(size / 1024).toFixed(1)} KiB`;
  }
  return `${(size / (1024 * 1024)).toFixed(2)} MiB`;
}

function revokeSelectedPreview() {
  if (selectedPreviewUrl) {
    env.URL.revokeObjectURL(selectedPreviewUrl);
    selectedPreviewUrl = null;
  }
  if (els.mediaPreviewThumb) {
    els.mediaPreviewThumb.replaceChildren();
  }
}

function updateMediaSelection() {
  const file = els.postMedia.files && els.postMedia.files[0] ? els.postMedia.files[0] : null;
  selectedFile = null;
  pendingUpload = null;
  revokeSelectedPreview();
  els.mediaSelected.hidden = true;
  els.mediaName.textContent = '';
  if (els.mediaSize) {
    els.mediaSize.textContent = '';
  }
  if (!file) {
    return;
  }
  if (!ACCEPTED_MEDIA_TYPES.has(file.type)) {
    els.postMedia.value = '';
    setStatus(els.composerStatus, 'mediaTypeError');
    return;
  }
  if (file.type === 'video/mp4' && featureConfig && !featureConfig.videoUploadsEnabled) {
    els.postMedia.value = '';
    setStatus(els.composerStatus, 'mediaVideoDisabled');
    return;
  }
  if (file.size > MAX_MEDIA_BYTES) {
    els.postMedia.value = '';
    setStatus(els.composerStatus, 'mediaSizeError');
    return;
  }
  selectedFile = file;
  els.mediaName.textContent = file.name || file.type;
  if (els.mediaSize) {
    els.mediaSize.textContent = formatBytes(file.size);
  }
  if (els.mediaPreviewThumb && env.URL && typeof env.URL.createObjectURL === 'function') {
    try {
      selectedPreviewUrl = env.URL.createObjectURL(file);
      if (file.type === 'video/mp4') {
        const vid = env.document.createElement('video');
        vid.src = selectedPreviewUrl;
        vid.muted = true;
        vid.playsInline = true;
        els.mediaPreviewThumb.append(vid);
      } else {
        const img = env.document.createElement('img');
        img.src = selectedPreviewUrl;
        img.alt = '';
        els.mediaPreviewThumb.append(img);
      }
    } catch {
      // Ignore object URL errors in non-browser environments.
    }
  }
  els.mediaSelected.hidden = false;
  setStatus(els.composerStatus, '');
}

function clearMediaSelection() {
  selectedFile = null;
  pendingUpload = null;
  revokeSelectedPreview();
  if (els.postMedia) {
    els.postMedia.value = '';
  }
  if (els.mediaSelected) {
    els.mediaSelected.hidden = true;
  }
  if (els.mediaName) {
    els.mediaName.textContent = '';
  }
  if (els.mediaSize) {
    els.mediaSize.textContent = '';
  }
}

async function submitPost() {
  const body = els.postBody.value.trim();
  const file = selectedFile;
  const fileIsVideo = Boolean(file && file.type === 'video/mp4');
  if (!body && !file) {
    setStatus(els.composerStatus, 'postEmpty');
    els.postBody.focus();
    return;
  }
  if (fileIsVideo && !body) {
    setStatus(els.composerStatus, 'videoNeedsCaption');
    els.postBody.focus();
    return;
  }
  // The post key survives an uncertain retry of the same caption and file;
  // it rotates as soon as either one actually changes.
  if (postDraft.submittedBody !== null && (postDraft.submittedBody !== body || postDraft.file !== file)) {
    postDraft = { id: env.crypto.randomUUID(), submittedBody: null, file: null };
  }
  postDraft.submittedBody = body;
  postDraft.file = file;
  const epoch = uiEpoch;
  let phase = 'upload';
  els.postSubmit.disabled = true;
  els.postBody.disabled = true;
  els.postMedia.disabled = true;
  els.mediaRemove.disabled = true;
  els.postSubmit.textContent = t('posting');
  try {
    let asset = null;
    if (file) {
      if (!(pendingUpload && pendingUpload.file === file && pendingUpload.assetId)) {
        setStatus(els.composerStatus, 'uploading', true);
        const form = new env.FormData();
        form.append('file', file, file.name || (fileIsVideo ? 'video.mp4' : 'image'));
        const uploaded = await client.request(fileIsVideo ? '/videos' : '/images', {
          method: 'POST',
          body: form,
        });
        if (epoch !== uiEpoch || !account) {
          return;
        }
        const assetId = uploaded && uploaded[fileIsVideo ? 'videoId' : 'imageId'];
        if (!isMediaId(assetId)) {
          throw new Error('Upload response did not contain a media ID');
        }
        // The upload is confirmed: a later publish retry reuses this asset
        // instead of uploading the same bytes again.
        pendingUpload = { file, video: fileIsVideo, assetId };
      }
      asset = pendingUpload;
    }
    phase = 'post';
    setStatus(els.composerStatus, 'posting', true);
    const payload = { body: body || null, clientPostId: postDraft.id };
    if (asset && asset.assetId) {
      if (asset.video) {
        payload.videoId = asset.assetId;
      } else {
        payload.imageId = asset.assetId;
      }
    }
    const post = await client.request('/posts', {
      method: 'POST',
      body: payload,
    });
    if (epoch !== uiEpoch || !account) {
      return;
    }
    posts = [post].concat(posts.filter((item) => item.id !== post.id));
    postDraft = { id: env.crypto.randomUUID(), submittedBody: null, file: null };
    els.postBody.value = '';
    clearMediaSelection();
    updatePostCounter();
    setStatus(els.composerStatus, '');
    setStatus(els.feedStatus, '');
    reconcileFeed();
  } catch (error) {
    if (epoch === uiEpoch) {
      handleError(error, phase === 'upload' ? 'uploadFailed' : 'postFailed', els.composerStatus);
    }
  } finally {
    if (epoch === uiEpoch) {
      els.postSubmit.disabled = false;
      els.postBody.disabled = false;
      els.postMedia.disabled = false;
      els.mediaRemove.disabled = false;
      els.postSubmit.textContent = t('postButton');
    }
  }
}

async function signIn(event) {
  event.preventDefault();
  const identifier = els.loginIdentifier.value.trim();
  const password = els.loginPassword.value;
  if (!identifier || !password) {
    setStatus(els.loginStatus, 'signinValidation');
    return;
  }
  els.loginButton.disabled = true;
  els.loginButton.textContent = t('signingIn');
  setStatus(els.loginStatus, '');
  try {
    const payload = await client.login(identifier, password);
    setSignedIn(payload.user || null);
    await loadFeed(true);
  } catch (error) {
    if (error instanceof SocialApiError && error.payload && error.payload.error
        && error.payload.error.code === 'SOCIAL_FEATURE_DISABLED') {
      handleError(error, 'signinFailed', els.loginStatus);
      return;
    }
    if (!account) {
      setStatus(els.loginStatus, 'signinFailed');
    }
  } finally {
    els.loginPassword.value = '';
    els.loginButton.disabled = false;
    els.loginButton.textContent = t('signinButton');
  }
}

async function signOut() {
  els.logoutButton.disabled = true;
  els.logoutButton.textContent = t('signingOut');
  const revoke = client.logout();
  clearSocialState();
  const result = await revoke;
  els.logoutButton.disabled = false;
  els.logoutButton.textContent = t('signout');
  if (!account) {
    setStatus(els.loginStatus, result.revoked ? 'signedOut' : 'signoutUnconfirmed', result.revoked);
  }
}

function handleSearchInput() {
  const next = els.feedSearchInput.value.trim();
  if (els.feedSearchClear) {
    els.feedSearchClear.hidden = !els.feedSearchInput.value;
  }
  if (searchDebounceTimer) {
    clearTimeout(searchDebounceTimer);
  }
  searchDebounceTimer = setTimeout(() => {
    searchDebounceTimer = null;
    if (searchQuery !== next) {
      searchQuery = next;
      void loadFeed(true);
    }
  }, 300);
}

function triggerImmediateSearch() {
  if (searchDebounceTimer) {
    clearTimeout(searchDebounceTimer);
    searchDebounceTimer = null;
  }
  const next = els.feedSearchInput.value.trim();
  if (els.feedSearchClear) {
    els.feedSearchClear.hidden = !els.feedSearchInput.value;
  }
  if (searchQuery !== next) {
    searchQuery = next;
    void loadFeed(true);
  }
}

function clearSearch() {
  if (searchDebounceTimer) {
    clearTimeout(searchDebounceTimer);
    searchDebounceTimer = null;
  }
  els.feedSearchInput.value = '';
  if (els.feedSearchClear) {
    els.feedSearchClear.hidden = true;
  }
  const changed = Boolean(searchQuery);
  searchQuery = '';
  els.feedSearchInput.focus();
  if (changed) {
    void loadFeed(true);
  }
}

export function initSocialApp(overrides = {}) {
  env = {
    document: overrides.document || globalThis.document,
    window: overrides.window || globalThis.window,
    fetch: overrides.fetch || globalThis.fetch,
    localStorage: 'localStorage' in overrides ? overrides.localStorage : globalThis.localStorage,
    navigator: overrides.navigator || globalThis.navigator,
    history: overrides.history || (globalThis.window && globalThis.window.history),
    URL: overrides.URL || globalThis.URL,
    crypto: overrides.crypto || globalThis.crypto,
    FormData: overrides.FormData || globalThis.FormData,
  };

  client = createSocialClient(env.fetch);
  els = {
    viButton: $('lang-vi-button'),
    enButton: $('lang-en-button'),
    themeToggle: $('theme-toggle'),
    themeIconMoon: $('theme-icon-moon'),
    themeIconSun: $('theme-icon-sun'),
    downloadLink: $('download-link'),
    openChatLink: $('open-chat-link'),
    signedOut: $('signed-out-panel'),
    session: $('session-panel'),
    composer: $('composer-panel'),
    loginForm: $('login-form'),
    loginIdentifier: $('login-identifier'),
    loginPassword: $('login-password'),
    loginButton: $('login-button'),
    loginStatus: $('login-status'),
    sessionAvatar: $('session-avatar'),
    sessionUser: $('session-user'),
    logoutButton: $('logout-button'),
    composerAvatar: $('composer-avatar'),
    composerAuthorName: $('composer-author-name'),
    postBody: $('post-body'),
    postMedia: $('post-media'),
    mediaSelected: $('media-selected'),
    mediaPreviewThumb: $('media-preview-thumb'),
    mediaName: $('media-name'),
    mediaSize: $('media-size'),
    mediaRemove: $('media-remove'),
    postCounter: $('post-counter'),
    postSubmit: $('post-submit'),
    composerStatus: $('composer-status'),
    refreshButton: $('refresh-button'),
    feedSearchInput: $('feed-search-input'),
    feedSearchClear: $('feed-search-clear'),
    authorFilterChip: $('author-filter-chip'),
    authorFilterLabel: $('author-filter-label'),
    authorFilterClear: $('author-filter-clear'),
    feedStatus: $('feed-status'),
    feedList: $('feed-list'),
    loadMoreButton: $('load-more-button'),
    reportDialog: $('report-dialog'),
    reportReason: $('report-reason'),
    reportDetails: $('report-details'),
    reportDetailsCounter: $('report-details-counter'),
    reportCancel: $('report-cancel'),
    reportSubmit: $('report-submit'),
    reportStatus: $('report-status'),
    authorDialog: $('author-dialog'),
    authorDialogAvatar: $('author-dialog-avatar'),
    authorDialogTitle: $('author-dialog-title'),
    authorDialogCount: $('author-dialog-count'),
    authorDialogStatus: $('author-dialog-status'),
    authorDialogClose: $('author-dialog-close'),
    authorDialogFilter: $('author-dialog-filter'),
    mainLayout: $('main'),
    heroTitle: $('social-title'),
    heroLead: $('social-lead'),
    announcementCard: $('announcement-card'),
    announcementTitle: $('announcement-title'),
    announcementBody: $('announcement-body'),
    unavailablePanel: $('unavailable-panel'),
    unavailableBody: $('unavailable-body'),
    mediaHelp: $('media-help'),
    feedPanel: $('feed-panel'),
  };

  lang = env.window && env.window.location && env.window.location.hash === '#lang-en' ? 'en' : 'vi';
  account = null;
  posts = [];
  nextCursor = null;
  feedLoading = false;
  feedRequestSeq = 0;
  searchQuery = '';
  searchDebounceTimer = null;
  authorFilter = null;
  commentsState = new Map();
  commentDrafts = new Map();
  editingPosts = new Map();
  editingComments = new Map();
  openReactionPickers = new Set();
  imageCache = new Map();
  videoCache = new Map();
  avatarCache = new Map();
  selectedFile = null;
  selectedPreviewUrl = null;
  pendingUpload = null;
  reportTarget = null;
  reportReturnFocus = null;
  authorDialogReturnFocus = null;
  authorDialogProfile = null;
  authorDialogSeq = 0;
  uiEpoch = 0;
  surfaceState = 'loading';
  featureConfig = null;
  configRequest = 0;
  postDraft = { id: env.crypto.randomUUID(), submittedBody: null, file: null };

  els.viButton.addEventListener('click', () => setLanguage('vi'));
  els.enButton.addEventListener('click', () => setLanguage('en'));
  if (els.themeToggle) {
    els.themeToggle.addEventListener('click', toggleTheme);
  }
  if (env.window && typeof env.window.addEventListener === 'function') {
    env.window.addEventListener('hashchange', () => {
      const next = env.window.location.hash === '#lang-en' ? 'en' : 'vi';
      if (next !== lang) {
        lang = next;
        applyLanguage();
      }
    });
  }
  els.loginForm.addEventListener('submit', (event) => void signIn(event));
  els.logoutButton.addEventListener('click', () => void signOut());
  els.postBody.addEventListener('input', updatePostCounter);
  els.postMedia.addEventListener('change', updateMediaSelection);
  els.mediaRemove.addEventListener('click', () => {
    clearMediaSelection();
    setStatus(els.composerStatus, '');
  });
  els.postSubmit.addEventListener('click', () => void submitPost());
  els.refreshButton.addEventListener('click', () => void loadFeed(true));
  els.loadMoreButton.addEventListener('click', () => void loadFeed(false));
  if (els.feedSearchInput) {
    els.feedSearchInput.addEventListener('input', handleSearchInput);
    els.feedSearchInput.addEventListener('keydown', (event) => {
      if (event.key === 'Enter') {
        event.preventDefault();
        triggerImmediateSearch();
      }
    });
  }
  if (els.feedSearchClear) {
    els.feedSearchClear.addEventListener('click', clearSearch);
  }
  if (els.authorFilterClear) {
    els.authorFilterClear.addEventListener('click', () => {
      authorFilter = null;
      renderAuthorFilterChip();
      void loadFeed(true);
    });
  }
  if (els.reportDetails) {
    els.reportDetails.addEventListener('input', updateReportDetailsCounter);
  }
  els.reportCancel.addEventListener('click', closeReport);
  els.reportSubmit.addEventListener('click', () => void submitReport());
  els.reportDialog.addEventListener('cancel', () => {
    reportTarget = null;
  });
  els.reportDialog.addEventListener('close', () => {
    reportTarget = null;
    restoreReportFocus();
  });
  if (els.authorDialog) {
    els.authorDialogClose.addEventListener('click', closeAuthorDialog);
    els.authorDialogFilter.addEventListener('click', () => applyAuthorFilter(authorDialogProfile));
    els.authorDialog.addEventListener('cancel', () => {
      authorDialogProfile = null;
    });
    els.authorDialog.addEventListener('close', () => {
      authorDialogProfile = null;
      if (authorDialogReturnFocus && authorDialogReturnFocus.isConnected) {
        authorDialogReturnFocus.focus();
      }
      authorDialogReturnFocus = null;
    });
  }

  env.document.addEventListener('visibilitychange', () => {
    if (!env.document.hidden) {
      void bootstrapConfig();
    }
  });

  applyInitialTheme();
  applySurfaceState();
  applyLanguage();
  setStatus(els.feedStatus, '');
  return bootstrapConfig();
}

if (typeof globalThis.document !== 'undefined' && globalThis.document.getElementById('login-form')) {
  void initSocialApp();
}
