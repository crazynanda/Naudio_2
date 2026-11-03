package com.maxrave.domain.mediaservice.handler

/**
 * Queue index "Play next" inserts at: immediately after the current track, or null when the
 * player has no usable current index. A current index of -1 (nothing loaded yet) inserts at 0,
 * and the last track inserts at [queueSize], i.e. appends.
 */
fun playNextInsertionIndex(
    currentIndex: Int,
    queueSize: Int,
): Int? {
    if (queueSize == 0) return 0
    return (currentIndex + 1).takeIf { it in 0..queueSize }
}
