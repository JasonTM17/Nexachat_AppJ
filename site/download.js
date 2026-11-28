(async function () {
  'use strict';

  const releasesUrl = 'https://github.com/JasonTM17/Nexachat_AppJ/releases';
  const latestApi = 'https://api.github.com/repos/JasonTM17/Nexachat_AppJ/releases/latest';
  const apkName = 'nexachat-latest.apk';
  const checksumName = apkName + '.sha256';
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 6000);

  let release;
  try {
    const response = await fetch(latestApi, {
      credentials: 'omit',
      redirect: 'error',
      referrerPolicy: 'no-referrer',
      signal: controller.signal,
    });
    if (!response.ok) return;
    release = await response.json();
  } catch {
    // The HTML links still work if release metadata cannot be loaded.
    return;
  } finally {
    clearTimeout(timeout);
  }

  const tag = release?.tag_name;
  if (release?.draft !== false || release.prerelease !== false
      || typeof tag !== 'string' || !/^v\d+\.\d+\.\d+$/.test(tag)
      || !Array.isArray(release.assets)) return;

  const assetBase = releasesUrl + '/download/' + tag + '/';
  const hasAsset = (name) => release.assets.some((asset) =>
    asset?.name === name && asset.state === 'uploaded'
    && Number.isInteger(asset.size) && asset.size > 0
    && asset.browser_download_url === assetBase + name
  );
  if (!hasAsset(apkName) || !hasAsset(checksumName)) return;

  const links = {
    apk: assetBase + apkName,
    checksum: assetBase + checksumName,
    notes: releasesUrl + '/tag/' + tag,
  };
  // Pin both downloads to the displayed tag even if GitHub publishes a new release.
  for (const link of document.querySelectorAll('[data-release-link]')) {
    link.href = links[link.dataset.releaseLink];
  }
  for (const version of document.querySelectorAll('[data-release-version]')) {
    version.textContent = tag;
  }
  for (const label of document.querySelectorAll('[data-release-version-label]')) {
    label.hidden = false;
  }
})();
