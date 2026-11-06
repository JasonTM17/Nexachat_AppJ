package com.nexachat.core.network;

import dagger.hilt.EntryPoint;
import dagger.hilt.InstallIn;
import dagger.hilt.components.SingletonComponent;

/** Lets Compose navigation reach the singleton without a view model. */
@EntryPoint
@InstallIn(SingletonComponent.class)
public interface SessionEventsEntryPoint {
    SessionEvents sessionEvents();
}
