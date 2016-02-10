# NowPlayingWatchface
Android Wear watchface that updates the background to the album cover of the most recently listened to track on Spotify.

It listens to a broadcast intent from Spotify if a new song is played, downloads the album cover and transmits it to the wearable..
The wearable updates the watchface background with this album art. Uses Picasso so album art caching comes for free.

To add:
- Ambient mode friendly, low-bit album cover.
- Text for the artist, song and album name.
