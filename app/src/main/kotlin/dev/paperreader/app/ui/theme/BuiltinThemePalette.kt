package dev.paperreader.app.ui.theme

import androidx.compose.ui.graphics.Color

// A softer sun yellow keeps large actions readable without turning the light
// canvas into one saturated amber block.
internal val NeoAmber = Color(0xFFFFD84D)
internal val NeoBlue = Color(0xFF0099FF)

// The design skills name brighter 600 tones, but those fail normal-text AA on a
// light canvas. These darker, same-hue variants keep status text, icons, and
// borders readable wherever the host reuses the semantic token.
internal val BuiltinSuccess = Color(0xFF166534)
internal val BuiltinWarning = Color(0xFF92400E)
internal val BuiltinDanger = Color(0xFFB91C1C)
