package com.rutger.fastify.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rutger.fastify.container
import com.rutger.fastify.ui.LocalNavigator

/** "What's new": releases from followed artists, last 60 days, newest first. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewReleasesScreen() {
    val c = LocalContext.current.container
    val nav = LocalNavigator.current
    val vm = viewModel { NewReleasesViewModel(c.library) }
    val state by vm.state.collectAsStateWithLifecycle()
    val list = state.data.orEmpty()

    PullToRefreshBox(isRefreshing = state.refreshing && state.data != null, onRefresh = vm::refresh, modifier = Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { nav.back() }, modifier = Modifier.padding(4.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                    Text("New releases", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                }
            }
            item {
                Text("From artists you follow, last 60 days", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
            state.error?.let { err -> item { ErrorCard("Couldn't load new releases", err, onRetry = vm::refresh) } }
            if (state.data == null && state.refreshing) {
                item { Box(Modifier.fillMaxWidth().padding(32.dp), Alignment.Center) { CircularProgressIndicator() } }
            }
            if (state.data != null && list.isEmpty()) {
                item { Text("Nothing new", modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(list, key = { it.id }) { a -> CollectionRow(a) { nav.openCollection(a) } }
        }
    }
}
