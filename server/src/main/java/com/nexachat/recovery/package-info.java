/**
 * Account recovery by email OTP: password reset ({@code PasswordResetController}) and
 * account deletion ({@code AccountDeletionController}) codes, OTP issue/verify with
 * hashed codes and attempt limits ({@code EmailOtpService}), and deferred mail dispatch
 * so responses do not reveal account or address ownership.
 */
package com.nexachat.recovery;
