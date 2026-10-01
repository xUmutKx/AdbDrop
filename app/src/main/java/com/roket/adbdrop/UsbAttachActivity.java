package com.roket.adbdrop;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** Launched by the system when another phone connects in accessory mode. */
public class UsbAttachActivity extends Activity {
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Hub.app = getApplicationContext();
        UsbHub.tick(this);
        startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        finish();
    }
}
