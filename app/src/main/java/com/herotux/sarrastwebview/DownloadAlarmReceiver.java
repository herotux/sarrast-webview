package com.herotux.sarrastwebview;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class DownloadAlarmReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if(!"SARRAST_START_DOWNLOAD".equals(intent.getAction())) return;
        Intent i=new Intent(context,MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        context.startActivity(i);
    }
}