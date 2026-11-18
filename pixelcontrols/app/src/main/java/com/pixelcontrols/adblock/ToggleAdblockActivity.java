package com.pixelcontrols.adblock;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

/**
 * Invisible trampoline used by the launcher shortcut and by the tile on first use. Shows the
 * one-time Android VPN consent dialog when needed, then toggles.
 */
public class ToggleAdblockActivity extends Activity {
    static final String EXTRA_FORCE_ON = "force_on";
    private static final int REQ_VPN = 1;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        boolean forceOn = getIntent().getBooleanExtra(EXTRA_FORCE_ON, false);
        if (!forceOn && Adblock.isActive(this)) {
            Adblock.stop(this);
            Toast.makeText(this, "Adblock off", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        Intent consent = Adblock.consentIntent(this);
        if (consent != null) {
            startActivityForResult(consent, REQ_VPN);
        } else {
            turnOn();
        }
    }

    private void turnOn() {
        Adblock.start(this);
        Toast.makeText(this, "Adblock on", Toast.LENGTH_SHORT).show();
        finish();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_VPN && resultCode == RESULT_OK) {
            turnOn();
        } else {
            Toast.makeText(this, "Adblock needs the VPN permission to filter ads", Toast.LENGTH_LONG).show();
            finish();
        }
    }
}
