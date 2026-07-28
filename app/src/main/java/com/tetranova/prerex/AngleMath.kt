package com.tetranova.prerex

import kotlin.math.abs

/** Normalizza un angolo in gradi nell'intervallo (-180, 180]. */
fun wrapAngleDeg180(angle: Float): Float {
    var a = angle % 360f
    if (a > 180f) a -= 360f
    if (a <= -180f) a += 360f
    return a
}

/** Differenza angolare assoluta minima tra due angoli in gradi, nell'intervallo [0, 180]. */
fun angleDiffAbsDeg(a: Float, b: Float): Float = abs(wrapAngleDeg180(a - b))
