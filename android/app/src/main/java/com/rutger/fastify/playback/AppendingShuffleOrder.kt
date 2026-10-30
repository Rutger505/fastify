package com.rutger.fastify.playback

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.ShuffleOrder
import kotlin.random.Random

/**
 * Random shuffle order, except that items appended to the end of a non-empty queue (autoplay
 * recommendations) play last, in their own order. The default order inserts them at random
 * positions, so in shuffle mode they could land before tracks that already played and be skipped.
 */
@UnstableApi
class AppendingShuffleOrder(private val inner: ShuffleOrder) : ShuffleOrder {
    constructor(length: Int) : this(ShuffleOrder.DefaultShuffleOrder(length, Random.nextLong()))

    override fun getLength() = inner.length
    override fun getNextIndex(index: Int) = inner.getNextIndex(index)
    override fun getPreviousIndex(index: Int) = inner.getPreviousIndex(index)
    override fun getLastIndex() = inner.lastIndex
    override fun getFirstIndex() = inner.firstIndex

    override fun cloneAndInsert(insertionIndex: Int, insertionCount: Int): ShuffleOrder {
        if (inner.length == 0 || insertionIndex != inner.length) {
            return AppendingShuffleOrder(inner.cloneAndInsert(insertionIndex, insertionCount))
        }
        val order = ArrayList<Int>(inner.length + insertionCount)
        var i = inner.firstIndex
        while (i != C.INDEX_UNSET) {
            order += i
            i = inner.getNextIndex(i)
        }
        for (n in 0 until insertionCount) order += insertionIndex + n
        return AppendingShuffleOrder(ShuffleOrder.DefaultShuffleOrder(order.toIntArray(), Random.nextLong()))
    }

    override fun cloneAndMove(indexFrom: Int, indexToExclusive: Int, newIndexFrom: Int) =
        AppendingShuffleOrder(inner.cloneAndMove(indexFrom, indexToExclusive, newIndexFrom))

    override fun cloneAndSet(insertionIndex: Int, insertionCount: Int) =
        AppendingShuffleOrder(inner.cloneAndSet(insertionIndex, insertionCount))

    override fun cloneAndRemove(indexFrom: Int, indexToExclusive: Int) =
        AppendingShuffleOrder(inner.cloneAndRemove(indexFrom, indexToExclusive))

    override fun cloneAndClear() = AppendingShuffleOrder(inner.cloneAndClear())
}
