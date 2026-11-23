package com.nexachat.core.network;

import com.nexachat.data.SettingsRepository;
import dagger.hilt.EntryPoint;
import dagger.hilt.InstallIn;
import dagger.hilt.components.SingletonComponent;

/** Lets the host activity read appearance prefs synchronously before inflation. */
@EntryPoint
@InstallIn(SingletonComponent.class)
public interface SettingsRepositoryEntryPoint {
    SettingsRepository settingsRepository();
}
