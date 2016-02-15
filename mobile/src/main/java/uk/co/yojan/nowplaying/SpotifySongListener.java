package uk.co.yojan.nowplaying;

import android.graphics.Bitmap;

/**
 * Created by yojan on 2/12/16.
 */
public interface SpotifySongListener {

    void onNewSongPosted(Bitmap albumArt);
}
