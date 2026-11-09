(function () {
  'use strict';

  var TOKEN_PATTERN = /^[A-Za-z0-9_-]{43}$/;

  function validateToken(token) {
    if (typeof token !== 'string') {
      return false;
    }
    return TOKEN_PATTERN.test(token);
  }

  function extractToken(pathname, search, hash) {
    if (search && search.length > 0) {
      return null;
    }
    if (hash && hash.length > 0) {
      return null;
    }
    if (!pathname || typeof pathname !== 'string') {
      return null;
    }
    var prefix = '/join/';
    if (!pathname.startsWith(prefix)) {
      return null;
    }
    var token = pathname.substring(prefix.length);
    if (!validateToken(token)) {
      return null;
    }
    return token;
  }

  function init() {
    var validSection = document.getElementById('invite-valid');
    var invalidSection = document.getElementById('invite-invalid');
    var openBtn = document.getElementById('btn-open-app');

    var token = extractToken(
      window.location.pathname,
      window.location.search,
      window.location.hash
    );

    if (token) {
      openBtn.setAttribute('href', 'nexachat://join/' + token);
      openBtn.setAttribute('rel', 'noreferrer');
      validSection.removeAttribute('hidden');
    } else {
      invalidSection.removeAttribute('hidden');
    }
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
