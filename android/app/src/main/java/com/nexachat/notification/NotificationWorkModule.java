package com.nexachat.notification;

import dagger.Binds;
import dagger.Module;
import dagger.hilt.InstallIn;
import dagger.hilt.components.SingletonComponent;
import javax.inject.Singleton;

@Module
@InstallIn(SingletonComponent.class)
abstract class NotificationWorkModule {
    @Binds
    @Singleton
    abstract NotificationOccurrenceLedger bindNotificationOccurrenceLedger(
            NotificationOccurrenceStore store);
}
