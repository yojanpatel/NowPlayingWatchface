package uk.co.yojan.nowplaying;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class SpotifyBroadcastReceiver extends BroadcastReceiver {
    public SpotifyBroadcastReceiver() {
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d("SpotifyReceiver", intent.getAction());
        if(BroadcastTypes.METADATA_CHANGED.equals(intent.getAction())) {
            // make the intent explicit
            intent.setComponent(new ComponentName(context, AlbumArtService.class));
            context.startService(intent);
        }
    }
}
