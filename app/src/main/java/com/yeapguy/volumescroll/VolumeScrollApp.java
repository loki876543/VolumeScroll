package com.yeapguy.volumescroll;

import android.app.Application;

import com.google.android.material.color.DynamicColors;

public class VolumeScrollApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        DynamicColors.applyToActivitiesIfAvailable(this);
        // 1.7.0 defaults, once per install: volume scrolling only in Firefox
        // and Loki. After this everything stays user-controlled.
        new SettingsRepository(this).applyScrollAppDefaultsIfNeeded();
    }
}
