package uk.co.yojan.nowplaying;

public class BroadcastTypes {

        // noninstantiable
        private BroadcastTypes() { }

        private static final String SPOTIFY_PACKAGE = "com.spotify.music";
        public static final String PLAYBACK_STATE_CHANGED = SPOTIFY_PACKAGE + ".playbackstatechanged";
        public static final String QUEUE_CHANGED = SPOTIFY_PACKAGE + ".queuechanged";
        public static final String METADATA_CHANGED = SPOTIFY_PACKAGE + ".metadatachanged";
        public static final String BITMAP = "uk.co.yojan.bitmap";

}
