package com.nexachat.core.ui;

import android.content.Context;
import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import com.nexachat.R;
import com.nexachat.core.common.AppLocaleManager;

/**
 * Single-activity XML host (B1 foundation). Compose {@link com.nexachat.MainActivity} remains the launcher until B4.
 * DayNight theming comes from {@link R.style#Theme_NexaChat_Views}; locale matches {@link com.nexachat.MainActivity}.
 */
public class NexaHostActivity extends AppCompatActivity {

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(AppLocaleManager.wrapContext(newBase));
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_nexa_host);
    }
}
