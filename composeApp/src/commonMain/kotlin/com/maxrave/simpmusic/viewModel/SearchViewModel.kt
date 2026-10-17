package com.maxrave.simpmusic.viewModel

import androidx.lifecycle.viewModelScope
import com.maxrave.common.Config
import com.maxrave.common.SELECTED_LANGUAGE
import com.maxrave.domain.data.entities.SearchHistory
import com.maxrave.domain.data.model.searchResult.albums.AlbumsResult
import com.maxrave.domain.data.model.searchResult.artists.ArtistsResult
import com.maxrave.domain.data.model.searchResult.playlists.PlaylistsResult
import com.maxrave.domain.data.model.searchResult.songs.SongsResult
import com.maxrave.domain.data.model.searchResult.spotify.SpotifyPlaylistSearchResult
import com.maxrave.domain.data.model.searchResult.spotify.SpotifyTrackSearchResult
import com.maxrave.domain.data.model.mood.Mood
import com.maxrave.domain.data.model.searchResult.videos.VideosResult
import com.maxrave.domain.data.model.spotify.SpotifyTrack
import com.maxrave.domain.data.type.SearchResultType
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.mediaservice.handler.PlaylistType
import com.maxrave.domain.mediaservice.handler.QueueData
import com.maxrave.domain.repository.HomeRepository
import com.maxrave.domain.repository.SearchRepository
import com.maxrave.domain.repository.SpotifyLibraryRepository
import com.maxrave.domain.utils.LocalResource
import com.maxrave.domain.utils.Resource
import com.maxrave.domain.utils.toQueryList
import com.maxrave.logger.LogLevel
import com.maxrave.logger.Logger
import com.maxrave.simpmusic.viewModel.base.BaseViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.StringResource
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.albums
import simpmusic.composeapp.generated.resources.all
import simpmusic.composeapp.generated.resources.artists
import simpmusic.composeapp.generated.resources.featured_playlists
import simpmusic.composeapp.generated.resources.playlists
import simpmusic.composeapp.generated.resources.spotify_track_unavailable
import simpmusic.composeapp.generated.resources.podcasts
import simpmusic.composeapp.generated.resources.search_source_all
import simpmusic.composeapp.generated.resources.search_source_other
import simpmusic.composeapp.generated.resources.search_source_spotify
import simpmusic.composeapp.generated.resources.search_source_youtube
import simpmusic.composeapp.generated.resources.songs
import simpmusic.composeapp.generated.resources.videos

// State cho tìm kiếm
data class SearchScreenState(
    val searchType: SearchType = SearchType.ALL,
    val searchSource: SearchSource = SearchSource.ALL,
    val searchAllResult: List<SearchResultType> = emptyList(),
    val searchSongsResult: List<SongsResult> = emptyList(),
    val searchVideosResult: List<VideosResult> = emptyList(),
    val searchAlbumsResult: List<AlbumsResult> = emptyList(),
    val searchArtistsResult: List<ArtistsResult> = emptyList(),
    val searchPlaylistsResult: List<PlaylistsResult> = emptyList(),
    val searchFeaturedPlaylistsResult: List<PlaylistsResult> = emptyList(),
    val searchPodcastsResult: List<PlaylistsResult> = emptyList(),
    val suggestQueries: List<String> = emptyList(),
    val suggestYTItems: List<SearchResultType> = emptyList(),
)

// Loại tìm kiếm
enum class SearchType {
    ALL,
    SONGS,
    VIDEOS,
    ALBUMS,
    ARTISTS,
    PLAYLISTS,
    FEATURED_PLAYLISTS,
    PODCASTS,
}

fun SearchType.toStringRes(): StringResource =
    when (this) {
        SearchType.ALL -> Res.string.all
        SearchType.SONGS -> Res.string.songs
        SearchType.VIDEOS -> Res.string.videos
        SearchType.ALBUMS -> Res.string.albums
        SearchType.ARTISTS -> Res.string.artists
        SearchType.PLAYLISTS -> Res.string.playlists
        SearchType.FEATURED_PLAYLISTS -> Res.string.featured_playlists
        SearchType.PODCASTS -> Res.string.podcasts
    }

/**
 * Which backend(s) [SearchViewModel.searchAll] fans out to. Orthogonal to [SearchType] - the
 * type chips (Songs/Videos/Albums/...) are YouTube-only categories and unaffected by this;
 * this only changes what the "All" chip's merged result is built from.
 */
enum class SearchSource {
    ALL,
    YOUTUBE,
    SPOTIFY,

    /** No other platform is integrated yet - selecting it just yields an empty result. */
    OTHER,
}

fun SearchSource.toStringRes(): StringResource =
    when (this) {
        SearchSource.ALL -> Res.string.search_source_all
        SearchSource.YOUTUBE -> Res.string.search_source_youtube
        SearchSource.SPOTIFY -> Res.string.search_source_spotify
        SearchSource.OTHER -> Res.string.search_source_other
    }

// UI state cho tìm kiếm
sealed class SearchScreenUIState {
    object Empty : SearchScreenUIState()

    object Loading : SearchScreenUIState()

    object Success : SearchScreenUIState()

    object Error : SearchScreenUIState()
}

class SearchViewModel(
    private val dataStoreManager: DataStoreManager,
    private val searchRepository: SearchRepository,
    private val homeRepository: HomeRepository,
    private val spotifyLibraryRepository: SpotifyLibraryRepository,
) : BaseViewModel() {
    // Real Spotify catalog search (not the user's own library) - "searchPlaylists"/"searchTracks"
    // take the query server-side, unlike the fetch-everything-then-page "libraryV3" call.
    private suspend fun spotifyPlaylistSearchResults(query: String): List<SpotifyPlaylistSearchResult> {
        if (!spotifyLibraryRepository.isLoggedIn().first()) return emptyList()
        val resource = spotifyLibraryRepository.searchPlaylists(query, limit = 10).first { it !is LocalResource.Loading }
        return (resource as? LocalResource.Success)?.data.orEmpty().map { SpotifyPlaylistSearchResult(it) }
    }

    private suspend fun spotifyTrackSearchResults(query: String): List<SpotifyTrackSearchResult> {
        if (!spotifyLibraryRepository.isLoggedIn().first()) return emptyList()
        val resource = spotifyLibraryRepository.searchTracks(query, limit = 10).first { it !is LocalResource.Loading }
        return (resource as? LocalResource.Success)?.data.orEmpty().map { SpotifyTrackSearchResult(it) }
    }

    /**
     * A Spotify search result isn't itself playable - resolves it to a YouTube Music match
     * first (same as every other Spotify track in this app), then plays it exactly like a
     * YouTube Music [SongsResult] tap does.
     */
    fun playSpotifyTrack(spotifyTrack: SpotifyTrack) {
        viewModelScope.launch {
            val resolved = spotifyLibraryRepository.resolveTrack(spotifyTrack)
            if (resolved == null) {
                makeToast(getString(Res.string.spotify_track_unavailable))
                return@launch
            }
            setQueueData(
                QueueData.Data(
                    listTracks = arrayListOf(resolved),
                    firstPlayedTrack = resolved,
                    playlistId = "RDAMVM${resolved.videoId}",
                    playlistName = spotifyTrack.title,
                    playlistType = PlaylistType.RADIO,
                    continuation = null,
                ),
            )
            loadMediaItem(resolved, Config.SONG_CLICK)
        }
    }
    private val _searchScreenUIState = MutableStateFlow<SearchScreenUIState>(SearchScreenUIState.Empty)
    val searchScreenUIState: StateFlow<SearchScreenUIState> get() = _searchScreenUIState.asStateFlow()

    private val _searchScreenState = MutableStateFlow(SearchScreenState())
    val searchScreenState: StateFlow<SearchScreenState> get() = _searchScreenState.asStateFlow()

    private val _searchHistory: MutableStateFlow<List<String>> = MutableStateFlow(emptyList())
    val searchHistory: StateFlow<List<String>> get() = _searchHistory.asStateFlow()

    /** Browse categories shown on the empty search screen, the way YouTube Music itself does. */
    private val _moodAndGenres: MutableStateFlow<Mood?> = MutableStateFlow(null)
    val moodAndGenres: StateFlow<Mood?> get() = _moodAndGenres.asStateFlow()

    /** Cover art per category params, filled in as tiles scroll into view. */
    private val _moodArtwork: MutableStateFlow<Map<String, String>> = MutableStateFlow(emptyMap())
    val moodArtwork: StateFlow<Map<String, String>> get() = _moodArtwork.asStateFlow()

    private val requestedArtwork = mutableSetOf<String>()

    var regionCode: String? = null
    var language: String? = null

    init {
        regionCode = runBlocking { dataStoreManager.location.first() }
        language = runBlocking { dataStoreManager.getString(SELECTED_LANGUAGE).first() }
        getSearchHistory()
        getMoodAndGenres()
    }

    /**
     * Fetched once for the app's lifetime — this ViewModel is a Koin `single`, and the category
     * list changes about as often as YouTube ships a new mood, so re-fetching on every visit to
     * the search tab would be pure waste.
     */
    private fun getMoodAndGenres() {
        if (_moodAndGenres.value != null) return
        viewModelScope.launch {
            homeRepository.getMoodAndMomentsData().collect { resource ->
                if (resource is Resource.Success) {
                    _moodAndGenres.value = resource.data
                }
            }
        }
    }

    /**
     * Resolve the cover for one category. Called from the tile itself, so only categories the user
     * actually scrolls to cost a request — [requestedArtwork] keeps a tile scrolling in and out of
     * view from firing it again.
     */
    fun loadMoodArtwork(params: String) {
        if (!requestedArtwork.add(params)) return
        viewModelScope.launch {
            homeRepository.getMoodCategoryArtwork(params).collect { url ->
                if (url != null) {
                    _moodArtwork.update { it + (params to url) }
                }
            }
        }
    }

    private fun getSearchHistory() {
        viewModelScope.launch {
            searchRepository.getSearchHistory().collect { values ->
                if (values.isNotEmpty()) {
                    values.toQueryList().reversed().let { list ->
                        _searchHistory.value = list
                        log("Search history updated: $list")
                    }
                } else {
                    _searchHistory.value = emptyList()
                    log("Search history is empty")
                }
            }
        }
    }

    fun insertSearchHistory(query: String) {
        viewModelScope.launch {
            searchRepository.insertSearchHistory(SearchHistory(query = query)).collectLatest {
                Logger.d(tag, "Inserted search history: $query, $it")
                getSearchHistory()
            }
        }
    }

    fun deleteSearchHistory() {
        viewModelScope.launch {
            searchRepository.deleteSearchHistory()
            delay(1000)
            getSearchHistory()
        }
    }

    fun searchSongs(query: String) {
        _searchScreenUIState.value = SearchScreenUIState.Loading
        viewModelScope.launch {
            searchRepository.getSearchDataSong(query).collect { values ->
                when (values) {
                    is Resource.Success -> {
                        values.data?.let { songsList ->
                            _searchScreenState.update { state ->
                                state.copy(searchSongsResult = songsList)
                            }
                        }
                        _searchScreenUIState.value = SearchScreenUIState.Success
                    }

                    is Resource.Error -> {
                        _searchScreenUIState.value = SearchScreenUIState.Error
                    }
                }
            }
        }
    }

    fun searchAll(query: String) {
        _searchScreenUIState.value = SearchScreenUIState.Loading
        val source = _searchScreenState.value.searchSource
        viewModelScope.launch {
            var song = ArrayList<SongsResult>()
            val video = ArrayList<VideosResult>()
            var album = ArrayList<AlbumsResult>()
            var artist = ArrayList<ArtistsResult>()
            var playlist = ArrayList<PlaylistsResult>()
            var featuredPlaylist = ArrayList<PlaylistsResult>()
            var podcast = ArrayList<PlaylistsResult>()
            var spotifyPlaylists: List<SpotifyPlaylistSearchResult> = emptyList()
            var spotifyTracks: List<SpotifyTrackSearchResult> = emptyList()
            val temp: ArrayList<SearchResultType> = ArrayList()

            val jobs =
                buildList {
                    if (source == SearchSource.ALL || source == SearchSource.YOUTUBE) {
                        add(
                            launch {
                                searchRepository.getSearchDataSong(query).collect { values ->
                                    when (values) {
                                        is Resource.Success -> values.data?.let { song = it }
                                        is Resource.Error -> {}
                                    }
                                }
                            },
                        )
                        add(
                            launch {
                                searchRepository.getSearchDataArtist(query).collect { values ->
                                    when (values) {
                                        is Resource.Success -> values.data?.let { artist = it }
                                        is Resource.Error -> {}
                                    }
                                }
                            },
                        )
                        add(
                            launch {
                                searchRepository
                                    .getSearchDataAlbum(query)
                                    .collect { values ->
                                        when (values) {
                                            is Resource.Success -> values.data?.let { album = it }
                                            is Resource.Error -> {}
                                        }
                                    }
                            },
                        )
                        add(
                            launch {
                                searchRepository.getSearchDataPlaylist(query).collect { values ->
                                    when (values) {
                                        is Resource.Success -> values.data?.let { playlist = it }
                                        is Resource.Error -> {}
                                    }
                                }
                            },
                        )
                        add(
                            launch {
                                searchRepository.getSearchDataVideo(query).collect { values ->
                                    when (values) {
                                        is Resource.Success -> values.data?.let { video.addAll(it) }
                                        is Resource.Error -> {}
                                    }
                                }
                            },
                        )
                        add(
                            launch {
                                searchRepository.getSearchDataFeaturedPlaylist(query).collect { values ->
                                    when (values) {
                                        is Resource.Success -> values.data?.let { featuredPlaylist = it }
                                        is Resource.Error -> {}
                                    }
                                }
                            },
                        )
                        add(
                            launch {
                                searchRepository.getSearchDataPodcast(query).collect { values ->
                                    when (values) {
                                        is Resource.Success -> values.data?.let { podcast = it }
                                        is Resource.Error -> {}
                                    }
                                }
                            },
                        )
                    }
                    if (source == SearchSource.ALL || source == SearchSource.SPOTIFY) {
                        add(
                            launch {
                                runCatching { spotifyPlaylistSearchResults(query) }
                                    .onSuccess { spotifyPlaylists = it }
                                    .onFailure { Logger.e(tag, "Spotify playlist search failed: ${it.message}") }
                            },
                        )
                        add(
                            launch {
                                runCatching { spotifyTrackSearchResults(query) }
                                    .onSuccess { spotifyTracks = it }
                                    .onFailure { Logger.e(tag, "Spotify track search failed: ${it.message}") }
                            },
                        )
                    }
                }
            jobs.forEach { it.join() }

            try {
                if (artist.size >= 3) {
                    for (i in 0..2) {
                        temp += artist[i]
                    }
                    temp.addAll(song)
                    temp.addAll(video)
                    temp.addAll(album)
                    temp.addAll(playlist)
                    temp.addAll(featuredPlaylist)
                    temp.addAll(podcast)
                } else {
                    temp.addAll(artist)
                    temp.addAll(song)
                    temp.addAll(video)
                    temp.addAll(album)
                    temp.addAll(playlist)
                    temp.addAll(featuredPlaylist)
                    temp.addAll(podcast)
                }
                temp.addAll(spotifyPlaylists)
                temp.addAll(spotifyTracks)

                _searchScreenState.update { state ->
                    state.copy(
                        searchType = SearchType.ALL,
                        searchAllResult = temp,
                        searchSongsResult = song,
                        searchArtistsResult = artist,
                        searchAlbumsResult = album,
                        searchPlaylistsResult = playlist,
                        searchVideosResult = video,
                        searchFeaturedPlaylistsResult = featuredPlaylist,
                        searchPodcastsResult = podcast,
                    )
                }
                _searchScreenUIState.value = SearchScreenUIState.Success
            } catch (e: Exception) {
                e.printStackTrace()
                _searchScreenUIState.value = SearchScreenUIState.Error
            }
        }
    }

    fun suggestQuery(query: String) {
        viewModelScope.launch {
            searchRepository.getSuggestQuery(query).collect { values ->
                when (values) {
                    is Resource.Success -> {
                        values.data?.let { suggestData ->
                            _searchScreenState.update { state ->
                                state.copy(
                                    suggestQueries = suggestData.queries,
                                    suggestYTItems = suggestData.recommendedItems,
                                )
                            }
                        }
                    }

                    is Resource.Error -> {
                        // Không cần xử lý lỗi đặc biệt cho gợi ý
                        log("Error fetching suggest queries: ${values.message}", LogLevel.ERROR)
                    }
                }
            }
        }
    }

    fun searchAlbums(query: String) {
        _searchScreenUIState.value = SearchScreenUIState.Loading
        viewModelScope.launch {
            searchRepository.getSearchDataAlbum(query).collect { values ->
                when (values) {
                    is Resource.Success -> {
                        values.data?.let { albumsList ->
                            _searchScreenState.update { state ->
                                state.copy(
                                    searchType = SearchType.ALBUMS,
                                    searchAlbumsResult = albumsList,
                                )
                            }
                        }
                        _searchScreenUIState.value = SearchScreenUIState.Success
                    }

                    is Resource.Error -> {
                        _searchScreenUIState.value = SearchScreenUIState.Error
                    }
                }
            }
        }
    }

    fun searchFeaturedPlaylist(query: String) {
        _searchScreenUIState.value = SearchScreenUIState.Loading
        viewModelScope.launch {
            searchRepository.getSearchDataFeaturedPlaylist(query).collect { values ->
                when (values) {
                    is Resource.Success -> {
                        values.data?.let { featuredPlaylistList ->
                            _searchScreenState.update { state ->
                                state.copy(
                                    searchType = SearchType.FEATURED_PLAYLISTS,
                                    searchFeaturedPlaylistsResult = featuredPlaylistList,
                                )
                            }
                        }
                        _searchScreenUIState.value = SearchScreenUIState.Success
                    }

                    is Resource.Error -> {
                        _searchScreenUIState.value = SearchScreenUIState.Error
                    }
                }
            }
        }
    }

    fun searchPodcast(query: String) {
        _searchScreenUIState.value = SearchScreenUIState.Loading
        viewModelScope.launch {
            searchRepository.getSearchDataPodcast(query).collect { values ->
                when (values) {
                    is Resource.Success -> {
                        values.data?.let { podcastList ->
                            _searchScreenState.update { state ->
                                state.copy(
                                    searchType = SearchType.PODCASTS,
                                    searchPodcastsResult = podcastList,
                                )
                            }
                        }
                        _searchScreenUIState.value = SearchScreenUIState.Success
                    }

                    is Resource.Error -> {
                        _searchScreenUIState.value = SearchScreenUIState.Error
                    }
                }
            }
        }
    }

    fun searchArtists(query: String) {
        _searchScreenUIState.value = SearchScreenUIState.Loading
        viewModelScope.launch {
            searchRepository.getSearchDataArtist(query).collect { values ->
                when (values) {
                    is Resource.Success -> {
                        values.data?.let { artistsList ->
                            _searchScreenState.update { state ->
                                state.copy(
                                    searchType = SearchType.ARTISTS,
                                    searchArtistsResult = artistsList,
                                )
                            }
                        }
                        _searchScreenUIState.value = SearchScreenUIState.Success
                    }

                    is Resource.Error -> {
                        _searchScreenUIState.value = SearchScreenUIState.Error
                    }
                }
            }
        }
    }

    fun searchPlaylists(query: String) {
        _searchScreenUIState.value = SearchScreenUIState.Loading
        viewModelScope.launch {
            searchRepository.getSearchDataPlaylist(query).collect { values ->
                when (values) {
                    is Resource.Success -> {
                        values.data?.let { playlistsList ->
                            _searchScreenState.update { state ->
                                state.copy(
                                    searchType = SearchType.PLAYLISTS,
                                    searchPlaylistsResult = playlistsList,
                                )
                            }
                        }
                        _searchScreenUIState.value = SearchScreenUIState.Success
                    }

                    is Resource.Error -> {
                        _searchScreenUIState.value = SearchScreenUIState.Error
                    }
                }
            }
        }
    }

    fun searchVideos(query: String) {
        _searchScreenUIState.value = SearchScreenUIState.Loading
        viewModelScope.launch {
            searchRepository.getSearchDataVideo(query).collect { values ->
                when (values) {
                    is Resource.Success -> {
                        values.data?.let { videosList ->
                            _searchScreenState.update { state ->
                                state.copy(
                                    searchType = SearchType.VIDEOS,
                                    searchVideosResult = videosList,
                                )
                            }
                        }
                        _searchScreenUIState.value = SearchScreenUIState.Success
                    }

                    is Resource.Error -> {
                        _searchScreenUIState.value = SearchScreenUIState.Error
                    }
                }
            }
        }
    }

    fun setSearchType(searchType: SearchType) {
        _searchScreenState.update { state ->
            state.copy(searchType = searchType)
        }
    }

    /** Also switches the type chip to "All", since [SearchSource] only shapes that merged result. */
    fun setSearchSource(
        source: SearchSource,
        query: String,
    ) {
        _searchScreenState.update { state ->
            state.copy(searchSource = source, searchType = SearchType.ALL)
        }
        if (query.isNotBlank()) {
            searchAll(query)
        }
    }
}