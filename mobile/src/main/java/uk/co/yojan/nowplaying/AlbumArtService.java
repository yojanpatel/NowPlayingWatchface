package uk.co.yojan.nowplaying;

import android.app.IntentService;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.Log;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;
import com.squareup.picasso.Picasso;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

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
        super.onCreate();
        initializeSpotifyApi();
        initializeGoogleApiClient();
        if (mGoogleApiClient != null) {
            mGoogleApiClient.connect();
        }
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

    private void initializeGoogleApiClient() {
        if (mGoogleApiClient == null) {
            mGoogleApiClient = new GoogleApiClient.Builder(this)
                    .addConnectionCallbacks(new GoogleApiClient.ConnectionCallbacks() {
                        @Override
                        public void onConnected(Bundle connectionHint) {
                            Log.d(TAG, "onConnected: " + connectionHint);
                            if (lastIntent != null) {
                                Log.d(TAG,"There was an unprocessed intent due to GoogleApiClient" +
                                        "connecting. Processing now");
                                onHandleIntent(lastIntent);
                            }
                        }

                        @Override
                        public void onConnectionSuspended(int cause) {
                            Log.d(TAG, "onConnectionSuspended: " + cause);
                        }
                    })
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
    }

    private void processTrack(String trackId) {
        mSpotifyService.getTrack(trackId, new Callback<Track>() {
            @Override
            public void success(Track track, Response response) {
                Log.d("Track success", track.name);

                // functional, for fun :)
                syncAssetToWatch(createAssetFromBitmap(downloadImage(getAlbumArtUrl(track))));
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

    private Bitmap downloadImage(String imageUrl) {
        try {
            Bitmap albumArt = Picasso.with(this).load(imageUrl).get();
            Log.d(TAG, "Downloaded album art " + albumArt.getHeight() + " height.");
            return albumArt;
        } catch (IOException e) {
            Log.e(TAG, e.toString());
        }
        return null;
    }

    private static Asset createAssetFromBitmap(Bitmap bitmap) {
        final ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, byteStream);
        return Asset.createFromBytes(byteStream.toByteArray());
    }


    private void syncAssetToWatch(Asset asset) {
        PutDataRequest request = PutDataRequest.create("/albumart");
        request.putAsset("albumArt", asset);
        Wearable.DataApi.putDataItem(mGoogleApiClient, request);
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        if (!mGoogleApiClient.isConnected()) {
            lastIntent = intent;
            return;
        }
        // lastIntent = null means there is no backlog of intents to process.
        lastIntent = null;
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
                int trackLengthInSec = intent.getIntExtra("length", 0);
                processTrack(trackId);
            }
        }
    }
}
