package dev.paperreader.app.ui.theme

import androidx.compose.ui.graphics.Color

// A softer sun yellow keeps large actions readable without turning the light
// canvas into one saturated amber block.
internal val NeoAmber = Color(0xFFFFD84D)
internal val NeoBlue = Color(0xFF0099FF)
internal val NeoViolet = Color(0xFF7357FF)

// Status colors are chosen for clear icon, border, and badge recognition on the
// built-in surfaces. Foreground copy still uses the dedicated ink roles.
internal val BuiltinSuccess = Color(0xFF166534)
internal val BuiltinWarning = NeoAmber
internal val BuiltinDanger = Color(0xFFB91C1C)
