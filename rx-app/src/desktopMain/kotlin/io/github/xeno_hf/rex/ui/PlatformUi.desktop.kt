package io.github.xeno_hf.rex.ui

import androidx.compose.runtime.Composable

// 桌面預覽用的空實作：只為了讓共用畫面能在 JVM 上編譯。

@Composable
actual fun rememberFilePicker(onPicked: (name: String?, bytes: ByteArray) -> Unit): () -> Unit = {}

@Composable
actual fun rememberLocationPermission(onResult: () -> Unit): () -> Unit = {}

@Composable
actual fun PlatformSystemBars(p: RxPalette) {}
