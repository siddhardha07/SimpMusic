# SimpMusic 2.3.1

## New
- **Spotify discovery on Home**: your own Spotify playlists, Spotify's featured playlists, and Spotify's podcast shows now have their own rows right at the top of Home, each with a "More" button to see the full list - no more digging through the playlists screen to find them.
- **YouTube Music's weekly top podcast chart on Home**: previously only reachable by scrolling all the way to the bottom of Home, past every other shelf. Now it's a row near the top, with its own "More" screen.
- **Search now covers Spotify too**: searching "All" also searches Spotify's own catalog for matching songs and playlists, not just your saved library. A new source filter next to the search type chips lets you scope a search to YouTube, Spotify, or both.

## Improved
- Update checks and the in-app update dialog now point at this build's own releases instead of upstream's, so you get notified about (and can download) the right version.
- Spotify's token refresh no longer races itself when two parts of the app request it at the same time, which used to make featured content silently fail to load.
- Home's background gradient now flows continuously from the very top instead of breaking where the Spotify rows end.
