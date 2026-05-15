package dev.glass.glass;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import dev.glass.glass.livecard.NavLiveCardService;

/**
 * Cold-launch entry point. Starts the LiveCard service and exits.
 *
 * On real Glass the user normally launches via voice trigger ("OK Glass, bike to…"); voice triggers
 * are deferred to v2. The launcher activity gives us an `am start` handle for the API 19 emulator.
 */
public class LauncherActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        startService(new Intent(this, NavLiveCardService.class));
        finish();
    }
}
