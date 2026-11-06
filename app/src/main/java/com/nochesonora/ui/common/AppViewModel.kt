package com.nochesonora.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nochesonora.AppContainer
import com.nochesonora.MurmurApp

@Composable
fun appContainer(): AppContainer = (LocalContext.current.applicationContext as MurmurApp).container

/** ViewModel creado con el [AppContainer] (DI manual, sin Hilt). */
@Composable
inline fun <reified VM : ViewModel> appViewModel(crossinline create: (AppContainer) -> VM): VM {
    val container = appContainer()
    return viewModel(factory = viewModelFactory { initializer { create(container) } })
}
