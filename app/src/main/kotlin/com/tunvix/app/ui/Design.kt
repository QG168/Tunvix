package com.tunvix.app.ui

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color

@Immutable
data class DesignPalette(val dark: Boolean) {
    val ink = Color(if(dark)0xFFE7ECF7 else 0xFF101B35)
    val muted = Color(if(dark)0xFFA7B2C8 else 0xFF67738A)
    val sub = Color(if(dark)0xFFBAC4D8 else 0xFF606C85)
    val primary = Color(if(dark)0xFFA0A3FF else 0xFF5353FA)
    val onPrimary = Color(if(dark)0xFF171B36 else 0xFFFFFFFF)
    val primaryLight = Color(if(dark)0xFF2B3050 else 0xFFEEF0FF)
    val background = Color(if(dark)0xFF101522 else 0xFFF7F8FD)
    val surface = Color(if(dark)0xFF1B2233 else 0xFFFFFFFF)
    val secondarySurface = Color(if(dark)0xFF232C40 else 0xFFF2F4FB)
    val border = Color(if(dark)0xFF354058 else 0xFFEEF0F6)
    val dangerSurface = Color(if(dark)0xFF402735 else 0xFFFFF0F2)
    val successSurface = Color(if(dark)0xFF1D3934 else 0xFFE9F9F5)
    val warningSurface = Color(if(dark)0xFF3B3125 else 0xFFFFF3E7)
    val warning = Color(if(dark)0xFFE3BE79 else 0xFF946B22)
}
val LocalDesign = staticCompositionLocalOf { DesignPalette(false) }
object Design {
    val Dark: Boolean @Composable @ReadOnlyComposable get() = LocalDesign.current.dark
    val Ink: Color @Composable @ReadOnlyComposable get() = LocalDesign.current.ink
    val Muted: Color @Composable @ReadOnlyComposable get() = LocalDesign.current.muted
    val Sub: Color @Composable @ReadOnlyComposable get() = LocalDesign.current.sub
    val Primary: Color @Composable @ReadOnlyComposable get() = LocalDesign.current.primary
    val OnPrimary: Color @Composable @ReadOnlyComposable get() = LocalDesign.current.onPrimary
    val PrimaryLight: Color @Composable @ReadOnlyComposable get() = LocalDesign.current.primaryLight
    val Background: Color @Composable @ReadOnlyComposable get() = LocalDesign.current.background
    val Surface: Color @Composable @ReadOnlyComposable get() = LocalDesign.current.surface
    val SecondarySurface: Color @Composable @ReadOnlyComposable get() = LocalDesign.current.secondarySurface
    val Border: Color @Composable @ReadOnlyComposable get() = LocalDesign.current.border
    val DangerSurface: Color @Composable @ReadOnlyComposable get() = LocalDesign.current.dangerSurface
    val SuccessSurface: Color @Composable @ReadOnlyComposable get() = LocalDesign.current.successSurface
    val WarningSurface: Color @Composable @ReadOnlyComposable get() = LocalDesign.current.warningSurface
    val Warning: Color @Composable @ReadOnlyComposable get() = LocalDesign.current.warning
    val Danger = Color(0xFFF04E68)
    val Player = Color(0xFF0E1424)
}
