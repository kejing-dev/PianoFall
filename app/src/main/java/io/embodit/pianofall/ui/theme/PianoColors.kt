package io.embodit.pianofall.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The design's exact colours (design-package `palette`): right hand teal, left hand amber, the other hand grey,
 * piano lacquer black. This is the only file with colour literals; the four semantic roles are injected into
 * `PicoTheme` by [PianoFallTheme], the brand tokens are used directly by the scene and the hand legend.
 */
object PianoColors {
    // ---- ColorScheme roles with a fixed design value (the other twelve inherit SpatialUI Vibrant)
    val Interaction = Color(0xFF2EC4B6) // design-style: fixed-figma-color interaction = right-hand teal (panel accent)
    val Passable = Color(0xFF7CFFB2) // design-style: fixed-figma-color passable: snapped / hit
    val Alert = Color(0xFFFFB020) // design-style: fixed-figma-color alert: left-hand amber
    val Error = Color(0xFFFF6B5E) // design-style: fixed-figma-color error

    // ---- brand tokens outside ColorScheme
    val RightHand = Color(0xFF2EC4B6) // design-style: fixed-figma-color rightHand
    val RightHandDeep = Color(0xFF26A195) // design-style: fixed-figma-color rightHand -18 % (black keys)
    val LeftHand = Color(0xFFFFB020) // design-style: fixed-figma-color leftHand
    val LeftHandDeep = Color(0xFFD1901A) // design-style: fixed-figma-color leftHand -18 % (black keys)
    val OtherHand = Color(0xFF9AA3AD) // design-style: fixed-figma-color otherHand
    val Outline = Color(0xFFE8F6FF) // design-style: fixed-figma-color outline
    val Snap = Color(0xFF7CFFB2) // design-style: fixed-figma-color snap
    val White = Color(0xFFFFFFFF) // design-style: fixed-figma-color white: curtain, landing line
    val Ivory = Color(0xFFF3EEDF) // design-style: fixed-figma-color ivory: virtual white keys
    val IvoryDown = Color(0xFFCFCABD) // design-style: fixed-figma-color ivory -15 %: pressed
    val Ebony = Color(0xFF1B1C20) // design-style: fixed-figma-color ebony: virtual black keys
    val EbonyDown = Color(0xFF0E0F12) // design-style: fixed-figma-color ebony pressed
    val Lacquer = Color(0xFF1C1F26) // design-style: fixed-figma-color lacquer: body, icon background
    val LacquerEdge = Color(0xFF3A3F4A) // design-style: fixed-figma-color lacquer edge highlight
    val DeskWood = Color(0xFFC89B6A) // design-style: fixed-figma-color deskWood: virtual desk
    val DebugWhite = Color(0xFFDADDE2) // design-style: fixed-figma-color debug white model piano
    val DebugBlack = Color(0xFF3B3F46) // design-style: fixed-figma-color debug white model black keys
}
