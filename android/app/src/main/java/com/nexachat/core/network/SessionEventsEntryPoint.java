package com.nexachat.core.network;

import dagger.hilt.EntryPoint;
import dagger.hilt.InstallIn;
import dagger.hilt.components.SingletonComponent;

/** Lets Android navigation reach the singleton without a ViewModel dependency. */
@EntryPoint
@InstallIn(SingletonComponent.class)
public interface SessionEventsEntryPoint {
    SessionEvents sessionEvents();
}
