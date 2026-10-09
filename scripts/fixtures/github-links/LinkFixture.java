package com.github.android;

public class LinkFixture extends android.app.Activity {
    @Override public void onCreate(android.os.Bundle state) {
        super.onCreate(state);
        getPackageManager().setComponentEnabledSetting(new android.content.ComponentName(this, "com.github.android.WebLink"),
            getIntent().getBooleanExtra("linksEnabled", true)
                ? android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                : android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            android.content.pm.PackageManager.DONT_KILL_APP);
        android.widget.TextView label = new android.widget.TextView(this);
        label.setText("GitHub link test fixture\n" + getIntent().getDataString());
        setContentView(label);
    }
}
