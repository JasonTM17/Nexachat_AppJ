package com.nexachat.core.storage;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import javax.inject.Qualifier;

/** Qualifier for the DataStore that holds encrypted session tokens. */
@Qualifier
@Retention(RetentionPolicy.CLASS)
public @interface TokenPreferences {}
