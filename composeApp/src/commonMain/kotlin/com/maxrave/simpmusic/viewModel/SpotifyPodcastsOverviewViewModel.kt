package com.maxrave.simpmusic.viewModel

import androidx.lifecycle.viewModelScope
import com.maxrave.domain.data.model.spotify.SpotifyPodcast
import com.maxrave.domain.repository.SpotifyLibraryRepository
import com.maxrave.domain.utils.LocalResource
import com.maxrave.simpmusic.viewModel.base.BaseViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Backs the "show all" screen reached from the Home screen's Spotify podcasts row. */
class SpotifyPodcastsOverviewViewModel(
    private val spotifyLibraryRepository: SpotifyLibraryRepository,
) : BaseViewModel() {
    val spotifyLoggedIn = spotifyLibraryRepository.isLoggedIn()

    private val _podcasts: MutableStateFlow<LocalResource<List<SpotifyPodcast>>> = MutableStateFlow(LocalResource.Loading())
    val podcasts: StateFlow<LocalResource<List<SpotifyPodcast>>> get() = _podcasts

    fun load() {
        _podcasts.value = LocalResource.Loading()
        viewModelScope.launch {
            if (spotifyLibraryRepository.isLoggedIn().first()) {
                spotifyLibraryRepository.getFeaturedPodcasts(limit = 50).collect { _podcasts.value = it }
            }
        }
    }
}
