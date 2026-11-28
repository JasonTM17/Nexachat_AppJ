import { createSocialClient, fetchSocialConfig, SocialApiError, SocialSessionError } from './social-client.js';
import { ALLOWED_IMAGE_MIME_TYPES, ALLOWED_VIDEO_MIME_TYPES, canonicalImageRequestPath, canonicalVideoRequestPath, imageUuid, isMediaId, videoUuid } from './social-media.js';

const HEART = '❤️';

const TEXT = {
  vi: {
    skip: 'Bỏ qua đến nội dung',
    android: 'Tải Android',
    eyebrow: 'Bảng tin NexaChat',
    title: 'Chia sẻ công khai với tài khoản NexaChat của bạn.',
    titleCompact: 'NexaChat Social',
    leadCompact: 'Chia sẻ với các tài khoản NexaChat.',
    lead: 'Đăng bài, ảnh đã có, bình luận và cảm xúc. Bài viết hiển thị với mọi tài khoản NexaChat và không được mã hóa đầu cuối.',
    signinTitle: 'Đăng nhập',
    signinHelp: 'Dùng số điện thoại hoặc email đã xác minh cùng mật khẩu NexaChat của bạn.',
    identifier: 'Số điện thoại hoặc email đã xác minh',
    password: 'Mật khẩu',
    signinButton: 'Đăng nhập',
    signingIn: 'Đang đăng nhập…',
    signinFailed: 'Không đăng nhập được. Kiểm tra thông tin đăng nhập rồi thử lại.',
    signinValidation: 'Nhập số điện thoại hoặc email và mật khẩu.',
    sessionTitle: 'Phiên đăng nhập',
    signedInAs: 'Đang đăng nhập là {name}',
    signout: 'Đăng xuất',
    signingOut: 'Đang đăng xuất…',
    signedOut: 'Đã đăng xuất trên trình duyệt này.',
    signoutUnconfirmed: 'Đã đăng xuất cục bộ, nhưng máy chủ chưa xác nhận thu hồi phiên.',
    composerTitle: 'Bài viết mới',
    composerLabel: 'Nội dung bài viết',
    mediaLabel: 'Ảnh hoặc video',
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
    feedError: 'Không tải được bảng tin. Thử lại sau.',
    sessionExpired: 'Phiên đã hết hạn. Đăng nhập lại để tiếp tục.',
    comments: '{count} bình luận',
    commentsOne: '1 bình luận',
    commentsZero: 'Bình luận',
    commentEmpty: 'Chưa có bình luận nào.',
    commentLabel: 'Viết bình luận',
    commentButton: 'Gửi bình luận',
    commenting: 'Đang gửi…',
    commentFailed: 'Không gửi được bình luận.',
    commentValidation: 'Viết bình luận trước khi gửi.',
    react: 'Thả tim',
    removeReact: 'Bỏ tim',
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
    cancel: 'Hủy',
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
    leadCompact: 'Share with NexaChat accounts.',
    lead: 'Post text and existing images, comment, react, and report. Posts are visible to every NexaChat account and are not end-to-end encrypted.',
    signinTitle: 'Sign in',
    signinHelp: 'Use your NexaChat phone number or verified email with your password.',
    identifier: 'Phone number or verified email',
    password: 'Password',
    signinButton: 'Sign in',
    signingIn: 'Signing in…',
    signinFailed: 'Could not sign in. Check the account details and try again.',
    signinValidation: 'Enter a phone number or email and the password.',
    sessionTitle: 'Signed-in session',
    signedInAs: 'Signed in as {name}',
    signout: 'Sign out',
    signingOut: 'Signing out…',
    signedOut: 'Signed out in this browser.',
    signoutUnconfirmed: 'Signed out locally, but the server did not confirm session revocation.',
    composerTitle: 'New post',
    composerLabel: 'Post body',
    mediaLabel: 'Photo or video',
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
    feedError: 'Could not load the feed. Try again later.',
    sessionExpired: 'The session expired. Sign in again to continue.',
    comments: '{count} comments',
    commentsOne: '1 comment',
    commentsZero: 'Comments',
    commentEmpty: 'No comments yet.',
    commentLabel: 'Write a comment',
    commentButton: 'Send comment',
    commenting: 'Sending…',
    commentFailed: 'Could not send the comment.',
    commentValidation: 'Write a comment before sending.',
    react: 'Add heart',
    removeReact: 'Remove heart',
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
    cancel: 'Cancel',
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
    contextTitle: 'Who can see the feed?',
    contextBody: 'Every NexaChat account can read public posts. This is not private messaging and it is not end-to-end encrypted.',
    chatTitle: 'Private chat',
    chatBody: 'Private messaging stays in the NexaChat Android app. This page does not provide browser chat.',
    openChat: 'Get NexaChat for Android',
    downloadHash: '/download.html#lang-en',
    locale: 'en-US',
  },
};

const client = createSocialClient();
const $ = (id) => document.getElementById(id);

const els = {
  viButton: $('lang-vi-button'),
  enButton: $('lang-en-button'),
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
  sessionUser: $('session-user'),
  logoutButton: $('logout-button'),
  postBody: $('post-body'),
  postMedia: $('post-media'),
  mediaSelected: $('media-selected'),
  mediaName: $('media-name'),
  mediaRemove: $('media-remove'),
  postCounter: $('post-counter'),
  postSubmit: $('post-submit'),
  composerStatus: $('composer-status'),
  refreshButton: $('refresh-button'),
  feedStatus: $('feed-status'),
  feedList: $('feed-list'),
  loadMoreButton: $('load-more-button'),
  reportDialog: $('report-dialog'),
  reportReason: $('report-reason'),
  reportCancel: $('report-cancel'),
  reportSubmit: $('report-submit'),
  reportStatus: $('report-status'),
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

let lang = window.location.hash === '#lang-en' ? 'en' : 'vi';
let account = null;
let posts = [];
let nextCursor = null;
let feedLoading = false;
let commentsState = new Map();
let commentDrafts = new Map();
let imageCache = new Map();
let videoCache = new Map();
let selectedFile = null;
let pendingUpload = null;
let reportTarget = null;
let reportReturnFocus = null;
let uiEpoch = 0;
// Runtime feature state. 'loading' is the boot value: every interactive
// panel stays hidden until the public config proves the surface enabled —
// an unreachable or malformed config fails closed to 'unavailable'.
let surfaceState = 'loading';
let featureConfig = null;
let postDraft = { id: crypto.randomUUID(), submittedBody: null, file: null };

function t(key, values = {}) {
  let value = (TEXT[lang] && TEXT[lang][key]) || TEXT.vi[key] || key;
  for (const [name, replacement] of Object.entries(values)) {
    value = value.replaceAll(`{${name}}`, String(replacement));
  }
  return value;
}

function setStatus(element, key, info = false) {
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
  els.unavailablePanel.hidden = enabled;
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

async function bootstrapConfig() {
  try {
    const config = await fetchSocialConfig();
    featureConfig = config;
    surfaceState = config.webEnabled ? 'enabled' : 'disabled';
  } catch {
    featureConfig = null;
    surfaceState = 'unavailable';
  }
  applySurfaceState();
  applyLanguage();
}

function applyLanguage() {
  document.documentElement.lang = lang;
  const signedIn = Boolean(account);
  els.mainLayout.classList.toggle('signed-in', signedIn);
  applyVideoGate();
  els.heroTitle.dataset.i18n = signedIn ? 'titleCompact' : 'title';
  els.heroLead.dataset.i18n = signedIn ? 'leadCompact' : 'lead';
  for (const node of document.querySelectorAll('[data-i18n]')) {
    node.textContent = t(node.dataset.i18n);
  }
  for (const node of document.querySelectorAll('[data-status-key]')) {
    const key = node.dataset.statusKey;
    node.textContent = key ? t(key) : '';
  }
  els.downloadLink.href = t('downloadHash');
  els.openChatLink.href = t('downloadHash');
  els.viButton.setAttribute('aria-pressed', String(lang === 'vi'));
  els.enButton.setAttribute('aria-pressed', String(lang === 'en'));
  updatePostCounter();
  if (account) {
    els.sessionUser.textContent = t('signedInAs', { name: displayName(account) });
  }
  renderAnnouncement();
  renderFeed();
}

function setLanguage(next) {
  lang = next === 'en' ? 'en' : 'vi';
  if (lang === 'en') {
    history.replaceState(null, '', '#lang-en');
  } else {
    history.replaceState(null, '', window.location.pathname);
  }
  applyLanguage();
}

function el(tag, className, text) {
  const node = document.createElement(tag);
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

function avatar(name) {
  const tone = Math.abs(Array.from(String(name || '?')).reduce((sum, char) => sum + char.codePointAt(0), 0)) % 4;
  return el('span', `avatar-initial avatar-tone-${tone}`, initials(name));
}

function clearMedia() {
  for (const cache of [imageCache, videoCache]) {
    for (const entry of cache.values()) {
      if (entry.objectUrl) {
        URL.revokeObjectURL(entry.objectUrl);
      }
    }
  }
  imageCache = new Map();
  videoCache = new Map();
}

function pruneMedia() {
  const wantedImages = new Set(posts.map((post) => imageUuid(post.image && post.image.url)).filter(Boolean));
  for (const [id, entry] of imageCache) {
    if (!wantedImages.has(id)) {
      if (entry.objectUrl) {
        URL.revokeObjectURL(entry.objectUrl);
      }
      imageCache.delete(id);
    }
  }
  const wantedVideos = new Set(posts.map((post) => videoUuid(post.video && post.video.url)).filter(Boolean));
  for (const [id, entry] of videoCache) {
    if (!wantedVideos.has(id)) {
      if (entry.objectUrl) {
        URL.revokeObjectURL(entry.objectUrl);
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
            entry.objectUrl = URL.createObjectURL(blob);
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
  const video = document.createElement('video');
  video.className = 'post-video';
  video.controls = true;
  video.playsInline = true;
  video.preload = 'auto';
  video.src = objectUrl;
  video.setAttribute('aria-label', t('videoAlt'));
  // A decode failure is not a fetch failure: the bytes are cached, so retrying
  // only re-attaches the same object URL — it never refetches on its own.
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
  const hadFocus = container.contains(document.activeElement);
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
            entry.objectUrl = URL.createObjectURL(blob);
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
    const hadFocus = container.contains(document.activeElement);
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

function setSignedIn(user) {
  uiEpoch += 1;
  account = user;
  applySurfaceState();
  els.refreshButton.hidden = false;
  els.sessionUser.textContent = t('signedInAs', { name: displayName(user) });
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
  commentsState = new Map();
  commentDrafts = new Map();
  postDraft = { id: crypto.randomUUID(), submittedBody: null, file: null };
  clearMedia();
  clearMediaSelection();
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
    surfaceState = 'disabled';
    applySurfaceState();
    applyLanguage();
    return;
  }
  setStatus(statusElement, fallbackKey);
}

function updatePostCounter() {
  els.postCounter.textContent = `${els.postBody.value.length}/1000`;
}

async function loadFeed(reset) {
  if (!account || feedLoading) {
    return;
  }
  const epoch = uiEpoch;
  feedLoading = true;
  els.refreshButton.disabled = true;
  els.loadMoreButton.disabled = true;
  setStatus(els.feedStatus, 'loading', true);
  if (reset) {
    clearMedia();
  }
  try {
    const cursor = reset ? null : nextCursor;
    const suffix = cursor ? `&cursor=${encodeURIComponent(cursor)}` : '';
    const page = await client.request(`/posts?limit=20${suffix}`);
    if (!account || epoch !== uiEpoch) {
      return;
    }
    const items = Array.isArray(page.items) ? page.items : [];
    posts = reset ? items : posts.concat(items.filter((item) => !posts.some((post) => post.id === item.id)));
    nextCursor = page.nextCursor || null;
    pruneMedia();
    renderFeed();
    setStatus(els.feedStatus, posts.length ? '' : 'empty', !posts.length);
  } catch (error) {
    if (epoch === uiEpoch) {
      handleError(error, 'feedError', els.feedStatus);
    }
  } finally {
    feedLoading = false;
    if (epoch === uiEpoch) {
      els.refreshButton.disabled = false;
      els.loadMoreButton.disabled = false;
      els.loadMoreButton.hidden = !nextCursor;
    }
  }
}

function heartSummary(post) {
  return (post.reactions || []).find((reaction) => reaction.emoji === HEART) || null;
}

function renderFeed() {
  els.feedList.replaceChildren();
  if (!account) {
    els.loadMoreButton.hidden = true;
    return;
  }
  for (const post of posts) {
    els.feedList.append(renderPost(post));
  }
  els.loadMoreButton.hidden = !nextCursor;
}

function renderPost(post) {
  const article = el('article', 'card post-card');
  article.dataset.postId = post.id;

  const header = el('header', 'post-header');
  const name = displayName(post.author);
  header.append(avatar(name));
  const meta = el('div', 'author-meta');
  meta.append(el('span', 'author-name', name));
  const time = el('time', 'post-time', formatTime(post.createdAt));
  time.dateTime = post.createdAt || '';
  meta.append(time);
  header.append(meta);
  article.append(header);

  if (post.body) {
    article.append(el('p', 'post-body', post.body));
  }
  if (post.image && post.image.url) {
    const imageWrap = el('div', 'post-image-wrap', t('loading'));
    article.append(imageWrap);
    void loadImage(post, imageWrap);
  }
  if (post.video && post.video.url) {
    const videoWrap = el('div', 'post-video-wrap');
    article.append(videoWrap);
    renderVideoArea(post, videoWrap);
  }

  const actions = el('div', 'post-actions');
  const heart = heartSummary(post);
  const react = actionButton(`${HEART} ${heart ? heart.count : 0}`, heart && heart.mine ? 'active' : '');
  react.setAttribute('aria-label', heart && heart.mine ? t('removeReact') : t('react'));
  react.setAttribute('aria-pressed', String(Boolean(heart && heart.mine)));
  react.addEventListener('click', () => void toggleHeart(post, react));
  actions.append(react);

  for (const reaction of post.reactions || []) {
    if (reaction.emoji !== HEART) {
      actions.append(el('span', 'reaction-summary', `${reaction.emoji} ${reaction.count}`));
    }
  }

  const commentButton = actionButton(commentLabel(post));
  commentButton.setAttribute('aria-expanded', String(Boolean(commentState(post.id).open)));
  commentButton.addEventListener('click', () => void toggleComments(post));
  actions.append(commentButton);

  if (post.mine) {
    const deleteButton = actionButton(t('delete'), 'danger');
    deleteButton.addEventListener('click', () => void deletePost(post, deleteButton));
    actions.append(deleteButton);
  } else {
    const reportButton = actionButton(t('report'));
    reportButton.addEventListener('click', () => openReport('POST', post.id));
    actions.append(reportButton);
  }
  article.append(actions);

  const comments = el('section', 'comments-region');
  comments.hidden = !commentState(post.id).open;
  renderCommentsRegion(post, comments);
  article.append(comments);
  return article;
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
    commentDrafts.set(postId, { body: '', submittedBody: null, clientCommentId: crypto.randomUUID(), sending: false });
  }
  return commentDrafts.get(postId);
}

async function toggleHeart(post, button) {
  if (!account) {
    return;
  }
  const epoch = uiEpoch;
  button.disabled = true;
  const active = Boolean(heartSummary(post) && heartSummary(post).mine);
  try {
    const result = await client.request(`/posts/${encodeURIComponent(post.id)}/reactions/${encodeURIComponent(HEART)}`, {
      method: active ? 'DELETE' : 'PUT',
    });
    if (epoch === uiEpoch && account) {
      post.reactions = result.reactions || [];
      renderFeed();
    }
  } catch (error) {
    if (epoch === uiEpoch) {
      handleError(error, 'reactionFailed', els.feedStatus);
      button.disabled = false;
    }
  }
}

async function deletePost(post, button) {
  if (!window.confirm(t('confirmDeletePost'))) {
    return;
  }
  const epoch = uiEpoch;
  button.disabled = true;
  try {
    await client.request(`/posts/${encodeURIComponent(post.id)}`, { method: 'DELETE' });
    if (epoch === uiEpoch && account) {
      posts = posts.filter((item) => item.id !== post.id);
      pruneMedia();
      renderFeed();
      setStatus(els.feedStatus, posts.length ? 'deleted' : 'empty', true);
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
  renderFeed();
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
  renderFeed();
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
    state.error = 'feedError';
  } finally {
    state.loading = false;
    if (epoch === uiEpoch && account) {
      renderFeed();
    }
  }
}

function renderCommentsRegion(post, region) {
  const state = commentState(post.id);
  region.replaceChildren();
  if (!state.open) {
    return;
  }
  const list = el('div', 'comment-list');
  if (state.loading && !state.items.length) {
    list.append(el('p', 'muted', t('loading')));
  } else if (!state.items.length) {
    list.append(el('p', 'muted', state.error ? t(state.error) : t('commentEmpty')));
  } else {
    for (const comment of state.items) {
      list.append(renderComment(post, comment));
    }
  }
  region.append(list);
  if (state.nextCursor) {
    const more = actionButton(t('loadMore'));
    more.disabled = state.loading;
    more.addEventListener('click', () => void loadComments(post, false));
    region.append(more);
  }

  const draft = commentDraft(post.id);
  const label = el('label', 'field-label', t('commentLabel'));
  const inputId = `comment-input-${post.id}`;
  label.htmlFor = inputId;
  const input = el('textarea', 'text-input comment-input');
  input.id = inputId;
  input.maxLength = 500;
  input.rows = 2;
  input.value = draft.body;
  input.disabled = draft.sending;
  input.addEventListener('input', () => {
    draft.body = input.value;
  });
  const row = el('div', 'comment-form-row');
  const counter = el('span', 'muted', `${draft.body.length}/500`);
  input.addEventListener('input', () => {
    counter.textContent = `${input.value.length}/500`;
  });
  const send = el('button', 'button button-primary', draft.sending ? t('commenting') : t('commentButton'));
  send.type = 'button';
  send.disabled = draft.sending;
  send.addEventListener('click', () => void submitComment(post, input, send));
  row.append(counter, send);
  region.append(label, input, row);
}

function renderComment(post, comment) {
  const card = el('article', 'comment-card');
  const header = el('header', 'post-header');
  const name = displayName(comment.author);
  header.append(avatar(name));
  const meta = el('div', 'author-meta');
  meta.append(el('span', 'author-name', name));
  const time = el('time', 'post-time', formatTime(comment.createdAt));
  time.dateTime = comment.createdAt || '';
  meta.append(time);
  header.append(meta);
  card.append(header);
  card.append(el('p', 'comment-body', comment.body || ''));
  const actions = el('div', 'comment-actions');
  if (comment.mine) {
    const deleteButton = actionButton(t('delete'), 'danger');
    deleteButton.addEventListener('click', () => void deleteComment(post, comment, deleteButton));
    actions.append(deleteButton);
  } else {
    const reportButton = actionButton(t('report'));
    reportButton.addEventListener('click', () => openReport('COMMENT', comment.id));
    actions.append(reportButton);
  }
  card.append(actions);
  return card;
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
    draft.clientCommentId = crypto.randomUUID();
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
      clientCommentId: crypto.randomUUID(),
      sending: false,
    });
    const state = commentState(post.id);
    state.loaded = false;
    state.items = [];
    state.nextCursor = null;
    await loadComments(post, true);
    if (epoch === uiEpoch && account) {
      renderFeed();
    }
  } catch (error) {
    draft.sending = false;
    if (epoch === uiEpoch) {
      handleError(error, 'commentFailed', els.feedStatus);
      if (account) {
        renderFeed();
      }
    }
  }
}

async function deleteComment(post, comment, button) {
  if (!window.confirm(t('confirmDeleteComment'))) {
    return;
  }
  const epoch = uiEpoch;
  button.disabled = true;
  try {
    await client.request(`/comments/${encodeURIComponent(comment.id)}`, { method: 'DELETE' });
    if (epoch === uiEpoch && account) {
      const state = commentState(post.id);
      state.items = state.items.filter((item) => item.id !== comment.id);
      post.commentCount = Math.max(0, post.commentCount - 1);
      renderFeed();
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
  reportReturnFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null;
  els.reportStatus.textContent = '';
  els.reportSubmit.disabled = false;
  els.reportReason.value = 'SPAM';
  if (!els.reportDialog.open) {
    els.reportDialog.showModal();
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
  if (els.reportDialog.open) {
    els.reportDialog.close();
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
  try {
    await client.request('/reports', {
      method: 'POST',
      body: {
        targetType: reportTarget.targetType,
        targetId: reportTarget.targetId,
        reason: els.reportReason.value,
      },
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

const MAX_MEDIA_BYTES = 4 * 1024 * 1024;
const ACCEPTED_MEDIA_TYPES = new Set(['image/jpeg', 'image/png', 'image/webp', 'video/mp4']);

function updateMediaSelection() {
  const file = els.postMedia.files && els.postMedia.files[0] ? els.postMedia.files[0] : null;
  selectedFile = null;
  pendingUpload = null;
  els.mediaSelected.hidden = true;
  els.mediaName.textContent = '';
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
  els.mediaSelected.hidden = false;
  setStatus(els.composerStatus, '');
}

function clearMediaSelection() {
  selectedFile = null;
  pendingUpload = null;
  els.postMedia.value = '';
  els.mediaSelected.hidden = true;
  els.mediaName.textContent = '';
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
    postDraft = { id: crypto.randomUUID(), submittedBody: null, file: null };
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
        const form = new FormData();
        form.append('file', file, file.name || (fileIsVideo ? 'video.mp4' : 'image'));
        const uploaded = await client.request(fileIsVideo ? '/videos' : '/images', {
          method: 'POST',
          body: form,
        });
        if (epoch !== uiEpoch || !account) {
          return;
        }
        // A 2xx without a usable media id is an upload failure: publishing a
        // text-only post here would silently drop the selected media.
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
    postDraft = { id: crypto.randomUUID(), submittedBody: null, file: null };
    els.postBody.value = '';
    clearMediaSelection();
    updatePostCounter();
    setStatus(els.composerStatus, '');
    setStatus(els.feedStatus, '');
    renderFeed();
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
  } catch {
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

els.viButton.addEventListener('click', () => setLanguage('vi'));
els.enButton.addEventListener('click', () => setLanguage('en'));
window.addEventListener('hashchange', () => {
  const next = window.location.hash === '#lang-en' ? 'en' : 'vi';
  if (next !== lang) {
    lang = next;
    applyLanguage();
  }
});
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
els.reportCancel.addEventListener('click', closeReport);
els.reportSubmit.addEventListener('click', () => void submitReport());
els.reportDialog.addEventListener('cancel', () => {
  reportTarget = null;
});
els.reportDialog.addEventListener('close', () => {
  reportTarget = null;
  restoreReportFocus();
});

document.addEventListener('visibilitychange', () => {
  if (!document.hidden) {
    void bootstrapConfig();
  }
});

applySurfaceState();
applyLanguage();
setStatus(els.feedStatus, '');
void bootstrapConfig();
