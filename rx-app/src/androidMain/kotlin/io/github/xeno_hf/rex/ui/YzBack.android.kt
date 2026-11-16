package io.github.xeno_hf.rex.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable

@Composable
internal actual fun YzBack(enabled: Boolean, onBack: () -> Unit) = BackHandler(enabled, onBack)
