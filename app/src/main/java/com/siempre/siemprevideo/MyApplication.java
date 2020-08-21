package com.siempre.siemprevideo;

import android.app.Application;
import android.content.Context;

import androidx.multidex.MultiDex;

import com.bugfender.sdk.Bugfender;

public class MyApplication extends Application {
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        MultiDex.install(this);
    }

    @Override
    public void onCreate() {
        super.onCreate();

        Bugfender.init(this, "qMR3CYpzNgL1hJuSrKefTEtA37bdJU8d", BuildConfig.DEBUG);
        Bugfender.enableCrashReporting();
        Bugfender.enableUIEventLogging(this);
        Bugfender.enableLogcatLogging();
    }
}
