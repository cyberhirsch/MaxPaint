package com.maxpaint.spike

/**
 * One sheet of set paint. The simulation only ever bakes into the active
 * layer; the others are flattened above and below it for display.
 */
class Layer(var name: String, val tex: DoubleTex) {
    var visible = true
    var opacity = 1f
}
