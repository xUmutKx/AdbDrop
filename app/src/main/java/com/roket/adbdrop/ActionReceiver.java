package com.roket.adbdrop;
import android.content.*;
public class ActionReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) { Hub.answer("accept".equals(i.getAction())); }
}
