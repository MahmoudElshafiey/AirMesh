package com.airmesh.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * AirMesh corner radii (TASK U1 part 1). Cards and list rows use the medium/large
 * radii (12/16 dp); small controls use [Shapes.small]. Kept in one place so the
 * whole UI shares the same rounding rhythm.
 */
val AirMeshShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)
