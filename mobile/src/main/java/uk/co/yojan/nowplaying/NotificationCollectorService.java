package uk.co.yojan.nowplaying;

import android.app.Notification;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Bitmap;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import kaaes.spotify.webapi.android.models.Album;

public class NotificationCollectorService extends NotificationListenerService {

    private static final String TAG = "Notification";
    private static SpotifySongListener listener;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "onCreate");
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        Log.d(TAG, "New notification posted from: " + sbn.getPackageName());

        if (listener != null && sbn.getPackageName().contains("spotify")) {
            Notification notification = sbn.getNotification();
            Object picture = notification.extras.getParcelable(Notification.EXTRA_PICTURE);
            if (picture instanceof Bitmap) {
                Bitmap notificationBigImage = (Bitmap) picture;
                listener.onNewSongPosted(notificationBigImage);
            }
            Intent intent = new Intent();
            intent.setComponent(new ComponentName(this, AlbumArtService.class));
            intent.setAction(BroadcastTypes.METADATA_CHANGED);
            intent.getExtras().putParcelable("nowplaying/albumart",
                    notification.extras.getParcelable(Notification.EXTRA_PICTURE));
            startService(intent);
        }

    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn, RankingMap rankingMap) {
        Log.d(TAG, "onNotificationRemoved: " + sbn.getPackageName());
    }

    public static void setSpotifySongListener(SpotifySongListener spotifySongListener) {
        listener = spotifySongListener;
    }

    public static void removeSpotifySongListener() {
        listener = null;
    }
}
