package uk.co.yojan.nowplaying;

import android.app.IntentService;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Parcelable;
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
    private int WATCH_SCREEN_SIZE = 500;  // pixels

    public AlbumArtService() {
        super("AlbumArtService");
    }

    @Override
    public void onDestroy() {
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

    private void initializeGoogleApiClientSync() {
        if (mGoogleApiClient == null) {
            mGoogleApiClient = new GoogleApiClient.Builder(this)
                    .addOnConnectionFailedListener(new GoogleApiClient.OnConnectionFailedListener() {
                        @Override
                        public void onConnectionFailed(ConnectionResult result) {
                            Log.d(TAG, "onConnectionFailed: " + result);
                        }
                    })
                    // Request access only to the Wearable API
                    .addApi(Wearable.API)
                    .build();
        }

        if (!mGoogleApiClient.isConnected() && !mGoogleApiClient.isConnecting()) {
            mGoogleApiClient.blockingConnect(5, TimeUnit.SECONDS);
        }
    }

    /**
     * Performs the work for a track that is currently playing.
     *
     * - Download the track metadata from Spotify's web api (async via retrofit)
     * - Extract the album url
     * - Download the album cover bitmap (async via Picasso)
     * - Convert the bitmap to a png to an asset
     * - Sync the asset to the other nodes in the wearable network (async via DataApi)
     */
    private void processTrack(String trackId) {
        // Lazily instantiate spotify web api
        initializeSpotifyApi();
        mSpotifyService.getTrack(trackId, new Callback<Track>() {
            @Override
            public void success(Track track, Response response) {
                String albumUrl = getAlbumArtUrl(track);
                downloadAndSyncImage(albumUrl);
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
            Log.d(TAG, "Extracted album art url: " + albumArt.url);
            return albumArt.url;
        }
        return null;
    }

    private void downloadAndSyncImage(String imageUrl) {
        Picasso.with(this).load(imageUrl).into(new Target() {
            @Override
            public void onBitmapLoaded(final Bitmap bitmap, Picasso.LoadedFrom from) {
                new AsyncTask<Bitmap, Void, Void>() {
                    @Override
                    protected Void doInBackground(Bitmap... bitmap) {
                        Log.d(TAG, "Loading album art");
                        Asset albumArtAsset = createAssetFromBitmap(bitmap[0]);
                        syncAssetToWatch(albumArtAsset);
                        return null;
                    }
                }.execute(bitmap);
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
        bitmap.compress(Bitmap.CompressFormat.PNG, 80, byteStream);
        return Asset.createFromBytes(byteStream.toByteArray());
    }

    private void syncAssetToWatch(Asset asset) {
        // lazily instantiate GoogleApiClient
        initializeGoogleApiClientSync();
        if (!mGoogleApiClient.isConnected()) {
            Log.e(TAG, "mGoogleApiClient not connected");
            return;
        }
        PutDataMapRequest dataMap = PutDataMapRequest.createWithAutoAppendedId("/albumart");
        dataMap.getDataMap().putAsset("albumArt", asset);
        PutDataRequest request = dataMap.asPutDataRequest().setUrgent();
        Log.d(TAG, "Syncing asset.");
        Wearable.DataApi.putDataItem(mGoogleApiClient, request);
    }

    @Override
    protected void onHandleIntent(final Intent intent) {
        if (intent != null) {
            final String action = intent.getAction();
            if (BroadcastTypes.PLAYBACK_STATE_CHANGED.equals(action)) {
                Log.d(TAG, "Playback state changed.");
            } else if (BroadcastTypes.METADATA_CHANGED.equals(action)) {
                Log.d(TAG, "Metadata changed");
                String trackId = intent.getStringExtra("id");
                String artistName = intent.getStringExtra("artist");
                String albumName = intent.getStringExtra("album");
                String trackName = intent.getStringExtra("track");
                Object picture = intent.getExtras().getParcelable("nowplaying/albumart");
                if(picture instanceof Bitmap) {
                    Log.d(TAG, "received image in notification");
                    syncAssetToWatch(createAssetFromBitmap((Bitmap) picture));
                } else {

                    Log.d(TAG, String.format("%s - %s - %s", artistName, trackName, albumName));
                    try {
                        processTrack(extractTrackId(trackId));
                    } catch (IllegalArgumentException e) {
                        Log.e(TAG, e.toString());
                    }
                }
            }
        }
    }

    /**
     * Inconsistent spotify track id formats.
     * Extract the actual id from the intent trackId for the web API.
     */
    private static String extractTrackId(String spotifyTrackId) throws IllegalArgumentException {
        if(spotifyTrackId != null && !spotifyTrackId.isEmpty()) {
            String[] trackIdParts = spotifyTrackId.split(":");
            if (trackIdParts.length == 3) {
                return spotifyTrackId.split(":")[2];
            }
        }

        throw new IllegalArgumentException("Illegal spotify track id: " + spotifyTrackId);
    }
}
