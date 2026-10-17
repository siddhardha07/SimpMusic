package com.maxrave.simpmusic.viewModel

import androidx.lifecycle.viewModelScope
import com.maxrave.domain.data.model.spotify.SpotifyPlaylist
import com.maxrave.domain.repository.SpotifyLibraryRepository
import com.maxrave.domain.utils.LocalResource
import com.maxrave.simpmusic.viewModel.base.BaseViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Backs the "show all" screen reached from the Home screen's Spotify playlists row. */
class SpotifyPlaylistsOverviewViewModel(
    private val spotifyLibraryRepository: SpotifyLibraryRepository,
) : BaseViewModel() {
    val spotifyLoggedIn = spotifyLibraryRepository.isLoggedIn()

    private val _playlists: MutableStateFlow<LocalResource<List<SpotifyPlaylist>>> = MutableStateFlow(LocalResource.Loading())
    val playlists: StateFlow<LocalResource<List<SpotifyPlaylist>>> get() = _playlists

    fun load() {
        _playlists.value = LocalResource.Loading()
        viewModelScope.launch {
            if (spotifyLibraryRepository.isLoggedIn().first()) {
                spotifyLibraryRepository.getUserPlaylists(limit = 50).collect { _playlists.value = it }
            }
        }
    }
}
