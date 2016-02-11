package uk.co.yojan.nowplaying;

import android.app.IntentService;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.PendingResult;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;
import com.squareup.picasso.Picasso;
import com.squareup.picasso.Target;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import kaaes.spotify.webapi.android.SpotifyApi;
import kaaes.spotify.webapi.android.SpotifyService;
import kaaes.spotify.webapi.android.models.Image;
import kaaes.spotify.webapi.android.models.Track;
import retrofit.Callback;
import retrofit.RetrofitError;
import retrofit.client.Response;

public class AlbumArtService extends IntentService {

    private static final String TAG = "AlbumArtService";

    private SpotifyService mSpotifyService;
    private GoogleApiClient mGoogleApiClient;

    // Want the album art that is the smallest size above the screen size.
    private int WATCH_SCREEN_SIZE = 300;  // pixels

    // record a backlog
    Intent lastIntent;

    public AlbumArtService() {
        super("AlbumArtService");
    }

    @Override
    public void onCreate() {
        Log.d(TAG, "onCreate");
        super.onCreate();
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy");
        super.onDestroy();
        if (mGoogleApiClient != null) {
            mGoogleApiClient.disconnect();
        }
    }

    private void initializeSpotifyApi() {
        if (mSpotifyService == null) {
            SpotifyApi api = new SpotifyApi();
            mSpotifyService = api.getService();
        }
    }

    private void initializeGoogleApiClient() {
        if (mGoogleApiClient == null) {
            mGoogleApiClient = new GoogleApiClient.Builder(this)
//                    .addConnectionCallbacks(new GoogleApiClient.ConnectionCallbacks() {
//                        @Override
//                        public void onConnected(Bundle connectionHint) {
//                            Log.d(TAG, "onConnected: " + connectionHint);
//                            if (lastIntent != null) {
//                                Log.d(TAG,"There was an unprocessed intent due to GoogleApiClient" +
//                                        "connecting. Processing now");
//                                onHandleIntent(lastIntent);
//                            }
//                        }
//
//                        @Override
//                        public void onConnectionSuspended(int cause) {
//                            Log.d(TAG, "onConnectionSuspended: " + cause);
//                        }
//                    })
                    .addOnConnectionFailedListener(new GoogleApiClient.OnConnectionFailedListener() {
                        @Override
                        public void onConnectionFailed(ConnectionResult result) {
                            Log.d(TAG, "onConnectionFailed: " + result);
                        }
                    })
                    // Request access only to the Wearable API
                    .addApi(Wearable.API)
                    .build();
            mGoogleApiClient.blockingConnect(5, TimeUnit.SECONDS);
            Log.d(TAG, "GoogleApiClient connected: " + mGoogleApiClient.isConnected());
            Log.d(TAG, "GoogleApiClient.WearableAPI connected: " + mGoogleApiClient.hasConnectedApi(Wearable.API));
        }
    }

    private void processTrack(String trackId) {
        initializeSpotifyApi();
        mSpotifyService.getTrack(trackId, new Callback<Track>() {
            @Override
            public void success(Track track, Response response) {
                Log.d("Track success", track.name);
                downloadAndSyncImage(getAlbumArtUrl(track));
            }

            @Override
            public void failure(RetrofitError error) {
                Log.d("Track failure", error.toString());
            }
        });

    }

    private String getAlbumArtUrl(Track track) {
        List<Image> albumArtImageList = track.album.images;
        Image albumArt = null;
        for (Image image : albumArtImageList) {
            if (albumArt == null) {
                albumArt = image;
            } else if (image.height < albumArt.height && image.height >= WATCH_SCREEN_SIZE) {
                // Found a smaller image that will fit on the watch screen.
                albumArt = image;
            }
        }

        if (albumArt != null) {
            Log.d(TAG, "Extracted album art url...");
            return albumArt.url;
        }
        return null;
    }

    private void downloadAndSyncImage(String imageUrl) {
        Picasso.with(this).load(imageUrl).into(new Target() {
            @Override
            public void onBitmapLoaded(final Bitmap bitmap, Picasso.LoadedFrom from) {
                Log.d(TAG, "Downloaded album art " + bitmap.getHeight() + " height.");
                new AsyncTask<Void, Void, Void>() {

                    @Override
                    protected Void doInBackground(Void... params) {
                        initializeGoogleApiClient();
                        syncAssetToWatch(createAssetFromBitmap(bitmap));
                        return null;
                    }
                }.execute();
            }

            @Override
            public void onBitmapFailed(Drawable errorDrawable) {
                Log.e(TAG, "Failed to get album art.");
            }

            @Override
            public void onPrepareLoad(Drawable placeHolderDrawable) {

            }
        });
    }

    private static Asset createAssetFromBitmap(Bitmap bitmap) {
        final ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, byteStream);
        return Asset.createFromBytes(byteStream.toByteArray());
    }

    private void syncAssetToWatch(Asset asset) {
        if (!mGoogleApiClient.isConnected()) {
            Log.e(TAG, "mGoogleApiClient not connected");
            return;
        }
        Log.d(TAG, "syncing asset to watch " + asset.getDigest());
        PutDataMapRequest dataMap = PutDataMapRequest.create("/albumart");
        dataMap.getDataMap().putAsset("albumArt", asset);
        PutDataRequest request = dataMap.asPutDataRequest();
        request.setUrgent();
        Log.d(TAG, request.getUri() + "");
        Wearable.DataApi.deleteDataItems(mGoogleApiClient, request.getUri());
        Wearable.DataApi.putDataItem(mGoogleApiClient, request).setResultCallback(
                new ResultCallback<DataApi.DataItemResult>() {
                    @Override
                    public void onResult(DataApi.DataItemResult dataItemResult) {
                        Log.d(TAG, "onResult, asset transferred."+dataItemResult.getStatus().isSuccess());
                    }
                }
        );
    }

    @Override
    protected void onHandleIntent(final Intent intent) {
        Log.d(TAG, "onHandleIntent");
        if (intent != null) {
            final String action = intent.getAction();
            if (BroadcastTypes.PLAYBACK_STATE_CHANGED.equals(action)) {
                Log.d(TAG, "Playback state changed.");
//                boolean playing = intent.getBooleanExtra("playing", false);
//                int positionInMs = intent.getIntExtra("playbackPosition", 0);
            } else if (BroadcastTypes.METADATA_CHANGED.equals(action)) {
                Log.d(TAG, "Metadata changed");
                String trackId = intent.getStringExtra("id");
                String artistName = intent.getStringExtra("artist");
                String albumName = intent.getStringExtra("album");
                String trackName = intent.getStringExtra("track");
                Log.d(TAG, String.format("%s - %s - %s", artistName, trackName, albumName));
                int trackLengthInSec = intent.getIntExtra("length", 0);
                processTrack(extractTrackId(trackId));
            }
        }
    }

    private static String extractTrackId(String spotifyTrackId) {
        return spotifyTrackId.split(":")[2];
    }
}
