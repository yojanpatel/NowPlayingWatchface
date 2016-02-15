/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.co.yojan.nowplaying;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.Rect;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.Time;
import android.util.Log;
import android.view.Display;
import android.view.SurfaceHolder;
import android.view.WindowManager;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.Wearable;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Analog watch face with a ticking second hand. In ambient mode, the second hand isn't shown. On
 * devices with low-bit ambient mode, the hands are drawn without anti-aliasing in ambient mode.
 */
    public class NowPlayingWatchface extends CanvasWatchFaceService implements DataApi.DataListener {

    private static final String TAG = "NowPlayingWatchface";

    private static final boolean SHOW_SECONDS = false;

    /**
     * Update rate in milliseconds for interactive mode. We update once a second to advance the
     * second hand.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    @Override
    public void onDataChanged(DataEventBuffer dataEventBuffer) {
        Log.d(TAG, "onDataChanged");
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<NowPlayingWatchface.Engine> mWeakReference;

        public EngineHandler(NowPlayingWatchface.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            NowPlayingWatchface.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine {

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        private int mWidth;
        private int mHeight;

        private int top;
        private int left;

        private Bitmap currentAlbumArt;
        private Bitmap bwAlbumArt;

        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        Paint mBackgroundPaint;
        Paint mHandPaint;
        boolean mAmbient;
        Time mTime;
        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mTime.clear(intent.getStringExtra("time-zone"));
                mTime.setToNow();
            }
        };

        public static final String ACTION_BITMAP = "uk.co.yojan.bitmap";
        public static final String ACTION_MEDIA = "uk.co.yojan.media";

        public static final String EXTRA_TOKEN = "token";
        public static final String EXTA_BITMAP = "bitmap";

        private final String[] PREFERRED_MEDIA_ART_ORDER = {
                MediaMetadata.METADATA_KEY_ART,
                MediaMetadata.METADATA_KEY_ALBUM_ART,
                MediaMetadata.METADATA_KEY_DISPLAY_ICON
        };

        final BroadcastReceiver mediaReceiver = new BroadcastReceiver() {

          @Override
            public void onReceive(Context context, Intent intent) {
                if(ACTION_MEDIA.equals(intent.getAction())) {
                    MediaSession.Token token = intent.getParcelableExtra(EXTRA_TOKEN);
                    MediaController mc = new MediaController(context, token);
                    Log.d("MediaBroadcastReceiver",
                            "" + mc.getMetadata().getText(MediaMetadata.METADATA_KEY_ARTIST));
                    for (String key : PREFERRED_MEDIA_ART_ORDER) {
                        Bitmap result = mc.getMetadata().getBitmap(key);
                        if (result != null) {
                            updateAlbumArt(result);
                            return;
                        }
                    }
                } else if (ACTION_BITMAP.equals(intent.getAction())){
                    Bitmap bitmap = intent.getParcelableExtra(EXTA_BITMAP);
                    Log.d("MediaBroadcastReceiver", "Received bitmap from broadcast" + bitmap.getHeight());
                }
            }
        };

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(NowPlayingWatchface.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_SHORT)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .build());

            Resources resources = NowPlayingWatchface.this.getResources();

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.background));

            mHandPaint = new Paint();
            mHandPaint.setColor(resources.getColor(R.color.analog_hands));
            mHandPaint.setStrokeWidth(resources.getDimension(R.dimen.analog_hand_stroke));
            mHandPaint.setAntiAlias(true);
            mHandPaint.setStrokeCap(Paint.Cap.ROUND);

            mTime = new Time();

            WindowManager wm = (WindowManager) getApplicationContext()
                    .getSystemService(Context.WINDOW_SERVICE);
            Display display = wm.getDefaultDisplay();
            Point size = new Point();
            display.getSize(size);
            mWidth = size.x;
            mHeight = size.y;
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mHandPaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            mTime.setToNow();

            // Draw the background.
            if (isInAmbientMode()) {
                if (bwAlbumArt != null) {
                    canvas.drawBitmap(bwAlbumArt, left, top, null);
                } else {
                    canvas.drawColor(Color.BLACK);
                }
            } else {
                if (currentAlbumArt != null) {
                    canvas.drawBitmap(currentAlbumArt, left, top, null);
                } else {
                    canvas.drawRect(0, 0, canvas.getWidth(), canvas.getHeight(), mBackgroundPaint);
                }
            }

            // Find the center. Ignore the window insets so that, on round watches with a
            // "chin", the watch face is centered on the entire screen, not just the usable
            // portion.
            float centerX = bounds.width() / 2f;
            float centerY = bounds.height() / 2f;

            if (SHOW_SECONDS) {
                float secRot = mTime.second / 30f * (float) Math.PI;
                float secLength = centerX - 20;
                if (!mAmbient) {
                    float secX = (float) Math.sin(secRot) * secLength;
                    float secY = (float) -Math.cos(secRot) * secLength;
                    canvas.drawLine(centerX, centerY, centerX + secX, centerY + secY, mHandPaint);
                }
            }
            int minutes = mTime.minute;
            float minRot = minutes / 30f * (float) Math.PI;
            float hrRot = ((mTime.hour + (minutes / 60f)) / 6f) * (float) Math.PI;

            float minLength = centerX - 40;
            float hrLength = centerX - 80;

            float minX = (float) Math.sin(minRot) * minLength;
            float minY = (float) -Math.cos(minRot) * minLength;
            canvas.drawLine(centerX, centerY, centerX + minX, centerY + minY, mHandPaint);

            float hrX = (float) Math.sin(hrRot) * hrLength;
            float hrY = (float) -Math.cos(hrRot) * hrLength;
            canvas.drawLine(centerX, centerY, centerX + hrX, centerY + hrY, mHandPaint);
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();
                // Update time zone in case it changed while we weren't visible.
                mTime.clear(TimeZone.getDefault().getID());
                mTime.setToNow();
            } else {
                unregisterReceiver();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            NowPlayingWatchface.this.registerReceiver(mTimeZoneReceiver, filter);

            IntentFilter mediaFilter = new IntentFilter(ACTION_BITMAP);
            mediaFilter.addAction(ACTION_MEDIA);
            NowPlayingWatchface.this.registerReceiver(mediaReceiver, mediaFilter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            NowPlayingWatchface.this.unregisterReceiver(mTimeZoneReceiver);
            NowPlayingWatchface.this.unregisterReceiver(mediaReceiver);
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }

        private void updateBwAlbumArt(final Bitmap original) {
            new AsyncTask<Void, Void, Void>() {
                @Override
                protected Void doInBackground(Void... params) {
                    long now = System.currentTimeMillis();
                    Bitmap bmpMonochrome = Bitmap.createBitmap(
                            original.getWidth(), original.getHeight(), Bitmap.Config.ARGB_8888);
                    Canvas canvas = new Canvas(bmpMonochrome);
                    ColorMatrix ma = new ColorMatrix();
                    ma.setSaturation(0);
                    Paint paint = new Paint();
                    paint.setColorFilter(new ColorMatrixColorFilter(ma));
                    canvas.drawBitmap(original, 0, 0, paint);

                    int w = bmpMonochrome.getWidth();
                    int h = bmpMonochrome.getHeight();
                    int[] pixels = new int[w * h];

                    bmpMonochrome.getPixels(pixels, 0, w, 0, 0, w, h);
                    Bitmap mono = Bitmap.createBitmap(
                            bmpMonochrome.getWidth(),
                            bmpMonochrome.getHeight(),
                            Bitmap.Config.ARGB_8888);

                    for (int y = 0; y < h; y++) {
                        int offset = y * h;
                        for (int x = 0; x < w; x++) {
                            // Get the 7th bit (2^7 = 128) to determine 0 or 1 for pixel
                            pixels[offset + x] =
                                    ((pixels[offset + x] & 0x40) >> 6) == 1 ? Color.DKGRAY : Color.BLACK;
                        }
                    }
                    mono.setPixels(pixels, 0, w, 0, 0, w, h);
                    bwAlbumArt = mono;
                    Log.d(TAG, "Converting to black-and-white took: " +
                            (System.currentTimeMillis() - now) + "ms");
                    return null;
                }
            }.execute();
        }

        private void updateAlbumArt(Bitmap albumArt) {
            if(albumArt.getWidth() < mWidth) {
                Log.d(TAG, "updateAlbumArt scaling");
                currentAlbumArt = Bitmap.createScaledBitmap(albumArt, mWidth, mHeight, true);
            } else {
                Log.d(TAG, "updateAlbumArt original");
                currentAlbumArt = albumArt;
            }

            if (currentAlbumArt != null) {
                left = (mWidth - currentAlbumArt.getWidth()) / 2;
                top = (mHeight - currentAlbumArt.getHeight()) / 2;
                Log.d(TAG, "displaying art: " + left + ", " + top);
                updateBwAlbumArt(currentAlbumArt);
            }
        }
    }
}
