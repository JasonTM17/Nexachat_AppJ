package com.nexachat

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Application entry point. Hilt's generated [Hilt_NexaChatApp] component lives
 * here; modules under `core/` and `data/` contribute bindings.
 */
@HiltAndroidApp
class NexaChatApp : Application()
