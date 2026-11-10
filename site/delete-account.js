(function () {
  'use strict';

  var CODE_PATTERN = /^\d{6}$/;

  function readApiBase() {
    var meta = document.querySelector('meta[name="nexachat-api-base"]');
    if (!meta) {
      return '';
    }
    var value = meta.getAttribute('content');
    if (typeof value !== 'string') {
      return '';
    }
    value = value.trim();
    if (!value) {
      return '';
    }
    if (value.endsWith('/')) {
      value = value.slice(0, -1);
    }
    try {
      var parsed = new URL(value);
      if (parsed.protocol !== 'https:' && parsed.protocol !== 'http:') {
        return '';
      }
      return parsed.origin + parsed.pathname.replace(/\/$/, '');
    } catch (e) {
      return '';
    }
  }

  function normalizePhone(raw) {
    if (typeof raw !== 'string') {
      return '';
    }
    return raw.trim();
  }

  function validateCode(raw) {
    if (typeof raw !== 'string') {
      return false;
    }
    return CODE_PATTERN.test(raw.trim());
  }

  function setHidden(el, hidden) {
    if (!el) {
      return;
    }
    if (hidden) {
      el.setAttribute('hidden', '');
    } else {
      el.removeAttribute('hidden');
    }
  }

  function setBusy(form, busy) {
    var fields = form.querySelectorAll('input, button');
    for (var i = 0; i < fields.length; i++) {
      fields[i].disabled = busy;
    }
  }

  function focusElement(el) {
    if (el && typeof el.focus === 'function') {
      el.focus();
    }
  }

  function clearFieldError(input) {
    if (!input) {
      return;
    }
    input.removeAttribute('aria-invalid');
    var errId = input.getAttribute('aria-describedby');
    if (errId) {
      var err = document.getElementById(errId);
      if (err) {
        setHidden(err, true);
      }
    }
  }

  function showFieldError(input, show) {
    if (!input) {
      return;
    }
    if (show) {
      input.setAttribute('aria-invalid', 'true');
    } else {
      input.removeAttribute('aria-invalid');
    }
    var errId = input.getAttribute('aria-describedby');
    if (errId) {
      var err = document.getElementById(errId);
      if (err) {
        setHidden(err, !show);
      }
    }
  }

  function postJson(apiBase, path, body) {
    return fetch(apiBase + path, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
      credentials: 'omit',
      referrerPolicy: 'no-referrer',
      mode: 'cors',
    });
  }

  function init() {
    var apiBase = readApiBase();
    var unavailable = document.getElementById('web-deletion-unavailable');
    var formWrap = document.getElementById('web-deletion-form-wrap');
    var form = document.getElementById('web-deletion-form');
    if (!form || !formWrap || !unavailable) {
      return;
    }

    if (!apiBase) {
      setHidden(unavailable, false);
      setHidden(formWrap, true);
      return;
    }

    setHidden(unavailable, true);
    setHidden(formWrap, false);

    var phoneInput = document.getElementById('deletion-phone');
    var codeInput = document.getElementById('deletion-code');
    var confirmCheck = document.getElementById('deletion-confirm');
    var btnSend = document.getElementById('btn-send-code');
    var btnDelete = document.getElementById('btn-delete-account');
    var requestFeedback = document.getElementById('request-feedback');
    var postSendFields = document.getElementById('post-send-fields');
    var sendError = document.getElementById('web-deletion-send-error');
    var rateError = document.getElementById('web-deletion-rate-error');
    var formError = document.getElementById('web-deletion-form-error');
    var successBlock = document.getElementById('web-deletion-success');

    var codeRequested = false;
    var focusAfterBusy = null;

    function hideErrors() {
      setHidden(formError, true);
      setHidden(sendError, true);
      setHidden(rateError, true);
    }

    function showSendError() {
      setHidden(sendError, false);
    }

    function showRateError() {
      setHidden(rateError, false);
    }

    function showFormError() {
      setHidden(formError, false);
    }

    function updateDeleteEnabled() {
      if (!btnDelete || !confirmCheck || !codeInput) {
        return;
      }
      var ok =
        codeRequested &&
        validateCode(codeInput.value) &&
        confirmCheck.checked;
      btnDelete.disabled = !ok;
    }

    function finish() {
      setBusy(form, false);
      updateDeleteEnabled();
      var target = focusAfterBusy;
      focusAfterBusy = null;
      focusElement(target);
    }

    function resetRequestState() {
      hideErrors();
      if (!codeRequested) {
        return;
      }
      codeRequested = false;
      setHidden(postSendFields, true);
      setHidden(requestFeedback, true);
      if (codeInput) {
        codeInput.value = '';
        clearFieldError(codeInput);
      }
      updateDeleteEnabled();
    }

    function requestCode() {
      hideErrors();
      var phone = normalizePhone(phoneInput.value);
      if (!phone) {
        showFieldError(phoneInput, true);
        focusElement(phoneInput);
        return;
      }
      clearFieldError(phoneInput);
      setBusy(form, true);
      postJson(apiBase, '/api/v1/account-deletion/request', { phone: phone })
        .then(function (res) {
          if (res.status === 202) {
            codeRequested = true;
            setHidden(requestFeedback, false);
            setHidden(postSendFields, false);
            updateDeleteEnabled();
            focusAfterBusy = codeInput;
            return;
          }
          if (res.status === 429) {
            showRateError();
            focusAfterBusy = rateError;
            return;
          }
          throw new Error('unexpected status');
        })
        .catch(function () {
          showSendError();
          focusAfterBusy = sendError;
        })
        .finally(finish);
    }

    function confirmDeletion() {
      hideErrors();
      var phone = normalizePhone(phoneInput.value);
      var code = codeInput ? codeInput.value.trim() : '';
      if (!phone) {
        showFieldError(phoneInput, true);
        focusElement(phoneInput);
        return;
      }
      if (!validateCode(code)) {
        showFieldError(codeInput, true);
        focusElement(codeInput);
        return;
      }
      if (!confirmCheck || !confirmCheck.checked) {
        return;
      }
      clearFieldError(phoneInput);
      clearFieldError(codeInput);
      setBusy(form, true);
      postJson(apiBase, '/api/v1/account-deletion/confirm', { phone: phone, code: code })
        .then(function (res) {
          if (res.status === 204) {
            setHidden(form, true);
            setHidden(successBlock, false);
            focusAfterBusy = successBlock;
            return;
          }
          if (res.status === 400) {
            showFieldError(codeInput, true);
            focusAfterBusy = codeInput;
            return;
          }
          if (res.status === 429) {
            showRateError();
            focusAfterBusy = rateError;
            return;
          }
          throw new Error('unexpected status');
        })
        .catch(function () {
          showFormError();
          focusAfterBusy = formError;
        })
        .finally(finish);
    }

    btnSend.addEventListener('click', requestCode);

    if (phoneInput) {
      phoneInput.addEventListener('input', function () {
        clearFieldError(phoneInput);
        resetRequestState();
      });
    }

    if (codeInput) {
      codeInput.addEventListener('input', function () {
        clearFieldError(codeInput);
        var val = codeInput.value.replace(/\D/g, '');
        if (val.length > 6) {
          val = val.slice(0, 6);
        }
        codeInput.value = val;
        updateDeleteEnabled();
      });
    }

    if (confirmCheck) {
      confirmCheck.addEventListener('change', updateDeleteEnabled);
    }

    function wireEnter(input, action) {
      if (!input) {
        return;
      }
      input.addEventListener('keydown', function (ev) {
        if (ev.key !== 'Enter') {
          return;
        }
        ev.preventDefault();
        action();
      });
    }

    wireEnter(phoneInput, function () {
      btnSend.click();
    });
    wireEnter(codeInput, function () {
      if (btnDelete && !btnDelete.disabled) {
        btnDelete.click();
      }
    });

    form.addEventListener('submit', function (ev) {
      ev.preventDefault();
    });

    btnDelete.addEventListener('click', confirmDeletion);

    updateDeleteEnabled();
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
