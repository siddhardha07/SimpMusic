package com.maxrave.simpmusic.viewModel

import androidx.lifecycle.viewModelScope
import com.maxrave.domain.data.model.home.chart.ItemArtist
import com.maxrave.domain.repository.HomeRepository
import com.maxrave.domain.utils.Resource
import com.maxrave.simpmusic.viewModel.base.BaseViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Backs the "show all" screen reached from the Home screen's YouTube best podcasts row. Hardcodes
 * the "US" chart region, same reasoning as [com.maxrave.simpmusic.viewModel.HomeViewModel.getYoutubeBestPodcasts]
 * - the podcast shelf only exists there so far.
 */
class YoutubeBestPodcastsOverviewViewModel(
    private val homeRepository: HomeRepository,
) : BaseViewModel() {
    private val _loading: MutableStateFlow<Boolean> = MutableStateFlow(true)
    val loading: StateFlow<Boolean> get() = _loading

    private val _shows: MutableStateFlow<List<ItemArtist>> = MutableStateFlow(emptyList())
    val shows: StateFlow<List<ItemArtist>> get() = _shows

    fun load() {
        _loading.value = true
        viewModelScope.launch {
            homeRepository.getChartData("US").collect { values ->
                _shows.value = (values as? Resource.Success)?.data?.podcasts?.shows.orEmpty()
                _loading.value = false
            }
        }
    }
}
