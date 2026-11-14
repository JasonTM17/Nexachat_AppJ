/**
 * Push notifications: FCM receipt ({@code NexaFirebaseMessagingService},
 * {@code FcmMessageProcessor}), device-token registration and sync work, and message and
 * reminder alerts posted through WorkManager. Alerts fail closed unless the active
 * account, session and membership match ({@code NotificationAdmission}).
 */
package com.nexachat.notification;
