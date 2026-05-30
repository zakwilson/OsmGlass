package com.goodanser.osmglass.glass.livecard;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;

import com.goodanser.osmglass.glass.R;

/**
 * Translucent menu shown when the user taps the LiveCard. Renders a single haloed
 * "Stop navigation" label over the live map (see {@code activity_menu.xml}).
 *
 * <p>Replaces the framework options menu, whose text was unreadable over dark map tiles and
 * ignored theme-level text-shadow styling. Tap the touchpad (DPAD_CENTER) to stop navigation;
 * swipe down (BACK) to dismiss, which finishes the activity by default.
 */
public class MenuActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_menu);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            stopService(new Intent(this, NavLiveCardService.class));
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
