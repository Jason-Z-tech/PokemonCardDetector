package de.pokemoncarddetector.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import de.pokemoncarddetector.AppContainer
import de.pokemoncarddetector.PokeApp

/** Erstellt ViewModels mit Zugriff auf den [AppContainer]. */
@Composable
inline fun <reified VM : ViewModel> appViewModel(
    key: String? = null,
    crossinline create: (AppContainer, SavedStateHandle) -> VM,
): VM {
    val app = LocalContext.current.applicationContext as PokeApp
    return viewModel(
        key = key,
        factory = viewModelFactory {
            initializer { create(app.container, createSavedStateHandle()) }
        },
    )
}
