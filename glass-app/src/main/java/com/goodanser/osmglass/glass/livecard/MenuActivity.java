package com.goodanser.osmglass.glass.livecard;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;

import com.goodanser.osmglass.glass.R;

/**
 * Translucent menu activity that opens when the user taps the LiveCard. Currently exposes a single
 * "Stop navigation" item that stops the service and finishes.
 */
public class MenuActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        openOptionsMenu();
    }

    @Override
    public boolean onCreatePanelMenu(int featureId, Menu menu) {
        if (featureId == Window_FEATURE_OPTIONS_PANEL) {
            new MenuInflater(this).inflate(R.menu.livecard_menu, menu);
            return true;
        }
        return super.onCreatePanelMenu(featureId, menu);
    }

    @Override
    public boolean onMenuItemSelected(int featureId, MenuItem item) {
        if (item.getItemId() == R.id.menu_stop_nav) {
            stopService(new Intent(this, NavLiveCardService.class));
            finish();
            return true;
        }
        return super.onMenuItemSelected(featureId, item);
    }

    @Override
    public void onOptionsMenuClosed(Menu menu) {
        finish();
    }

    // Re-export for older API levels (Window.FEATURE_OPTIONS_PANEL = 0).
    private static final int Window_FEATURE_OPTIONS_PANEL = 0;
}
