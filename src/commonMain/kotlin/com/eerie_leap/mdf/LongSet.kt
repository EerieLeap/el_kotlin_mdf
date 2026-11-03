package com.eerie_leap.mdf

private const val INITIAL_SLOTS = 16
private const val EMPTY = Long.MIN_VALUE
private const val HASH_MULTIPLIER = -7_046_029_254_386_353_131L
private const val HASH_SHIFT = 32

/**
 * A set of numbers (the offsets of blocks) that holds each in a slot of its own instead of boxing it, for the walk of a list of
 * millions of blocks, which keeps one to refuse a block named twice. Only [Long.MIN_VALUE] can't be held.
 */
internal class LongSet {
    private var slots = LongArray(INITIAL_SLOTS) { EMPTY }

    /** How many numbers are in the set. */
    var size = 0
        private set

    /** Puts [value] in the set; false where it was there already. */
    fun add(value: Long): Boolean {
        if ((size + 1) * 2 > slots.size) grow()
        val slot = slotOf(value, slots)
        if (slots[slot] == value) return false
        slots[slot] = value
        size++
        return true
    }

    // The slot [value] is in, or the empty one it belongs in.
    private fun slotOf(value: Long, among: LongArray): Int {
        val mask = among.size - 1
        var slot = ((value * HASH_MULTIPLIER) ushr HASH_SHIFT).toInt() and mask
        while (among[slot] != EMPTY && among[slot] != value) slot = (slot + 1) and mask
        return slot
    }

    private fun grow() {
        val bigger = LongArray(slots.size * 2) { EMPTY }
        for (value in slots) {
            if (value != EMPTY) bigger[slotOf(value, bigger)] = value
        }
        slots = bigger
    }
}
