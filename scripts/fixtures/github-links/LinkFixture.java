package com.github.android;

public class LinkFixture extends android.app.Activity {
    @Override public void onCreate(android.os.Bundle state) {
        super.onCreate(state);
        android.widget.TextView label = new android.widget.TextView(this);
        label.setText("GitHub link test fixture\n" + getIntent().getDataString());
        setContentView(label);
    }
}
