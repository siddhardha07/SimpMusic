package com.maxrave.simpmusic.ui.screen.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.maxrave.simpmusic.ui.component.RippleIconButton
import com.maxrave.simpmusic.ui.component.SpotifyPlaylistGrid
import com.maxrave.simpmusic.ui.icon.ArrowBackIosNew
import com.maxrave.simpmusic.ui.icon.SimpIcons
import com.maxrave.simpmusic.ui.theme.typo
import com.maxrave.simpmusic.viewModel.SpotifyPlaylistsOverviewViewModel
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import simpmusic.composeapp.generated.resources.Res
import simpmusic.composeapp.generated.resources.your_spotify_playlists

/** "Show all" screen for the Home screen's Spotify playlists row. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpotifyPlaylistsOverviewScreen(
    navController: NavController,
    viewModel: SpotifyPlaylistsOverviewViewModel = koinViewModel(),
) {
    val loggedIn by viewModel.spotifyLoggedIn.collectAsStateWithLifecycle(initialValue = false)
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.load() }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopAppBar(
            title = { Text(text = stringResource(Res.string.your_spotify_playlists), style = typo().titleMedium) },
            navigationIcon = {
                Box(Modifier.padding(horizontal = 5.dp)) {
                    RippleIconButton(SimpIcons.ArrowBackIosNew, Modifier.size(32.dp), true) {
                        navController.navigateUp()
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        )
        SpotifyPlaylistGrid(
            navController = navController,
            contentPadding = PaddingValues(0.dp),
            isLoggedIn = loggedIn,
            data = playlists,
            onReload = { viewModel.load() },
        )
    }
}
