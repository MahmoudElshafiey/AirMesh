package com.airmesh.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * AirMesh spacing and elevation tokens (TASK U1 part 1). Components use these
 * instead of ad-hoc dp values so the layout rhythm stays consistent across screens.
 */
object AirMeshSpacing {
    val xxs: Dp = 2.dp
    val xs: Dp = 4.dp
    val sm: Dp = 8.dp
    val md: Dp = 16.dp
    val lg: Dp = 24.dp
    val xl: Dp = 32.dp
}

object AirMeshElevation {
    /** Flat surfaces (list rows, chips). */
    val level0: Dp = 0.dp
    /** Cards and the floating action button. */
    val level1: Dp = 1.dp
    /** Bottom sheets and dialogs. */
    val level2: Dp = 3.dp
}
