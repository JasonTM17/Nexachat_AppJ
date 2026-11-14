# feature.media — `package com.nexachat.feature.media`

Contents:

- `MediaPickerSheetFragment` — Photo Picker for visual media and SAF for documents/audio
  (no storage permission needed); the host screen owns the upload
- `MediaViewerFragment` / `MediaViewerViewModel` — full-screen preview from the
  authenticated cache: bounded image zoom/pan (`ZoomableMediaImageView`, `MediaZoomRules`),
  local video/audio playback via Media3
- `PendingAttachment` — picked item staged above the input bar while its upload runs
- `AttachmentViewBinder` — read-only `FileProvider` intents for cached attachments
- `FormatBytes` — file-size labels
