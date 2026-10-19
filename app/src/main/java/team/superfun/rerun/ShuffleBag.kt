package team.superfun.rerun

import kotlin.random.Random

/**
 * Deals clips like a shuffled deck: every clip plays once before any repeats,
 * and a new round never starts with the clip that just ended the last one.
 */
class ShuffleBag(private val size: Int, private val random: Random = Random.Default) {
    private val skipped = mutableSetOf<Int>()
    private var last: Int? = null

    /** Leaves a clip out of future rounds (e.g. a file the player can't decode). */
    fun skip(index: Int) {
        skipped.add(index)
    }

    val playableCount: Int get() = size - skipped.size

    fun nextRound(): List<Int> {
        val round = (0 until size).filterNot { it in skipped }.shuffled(random).toMutableList()
        if (round.size > 1 && round.first() == last) {
            val swapWith = 1 + random.nextInt(round.size - 1)
            round[0] = round[swapWith].also { round[swapWith] = round[0] }
        }
        last = round.lastOrNull() ?: last
        return round
    }
}
