package team.superfun.rerun

import kotlin.random.Random

/**
 * Deals clips like a shuffled deck: every clip plays once before any repeats, and two clips from the same
 * movie never play back to back, including across rounds. [movies] holds each clip's movie (any key that's
 * equal for clips from the same movie).
 */
class ShuffleBag(private val movies: List<Any>, private val random: Random = Random.Default) {
    private val skipped = mutableSetOf<Int>()
    private var last: Int? = null

    /** Leaves a clip out of future rounds (e.g. a file the player can't decode). */
    fun skip(index: Int) {
        skipped.add(index)
    }

    val playableCount: Int get() = movies.size - skipped.size

    /** The next round won't start with [index]'s movie (used after the guide plays a movie's clips). */
    fun continueAfter(index: Int) {
        last = index
    }

    fun nextRound(): List<Int> {
        // Remaining clips per movie, each list in random order.
        val left = movies.indices.filterNot { it in skipped }.shuffled(random).groupBy { movies[it] }
            .mapValues { it.value.toMutableList() }.toMutableMap()
        var remaining = left.values.sumOf { it.size }
        val round = ArrayList<Int>(remaining)
        var previous = last?.let { movies[it] }
        while (remaining > 0) {
            val allowed = left.filterKeys { it != previous }
            val movie = when {
                // Only the last movie has clips left: a repeat can't be avoided.
                allowed.isEmpty() -> left.keys.first()
                else -> {
                    // A movie with more than half the clips left needs every other slot, so it goes now.
                    val biggest = allowed.maxBy { it.value.size }
                    if (biggest.value.size * 2 > remaining) biggest.key
                    else pickWeighted(allowed)
                }
            }
            val clips = left.getValue(movie)
            round.add(clips.removeAt(clips.lastIndex))
            if (clips.isEmpty()) left.remove(movie)
            remaining--
            previous = movie
        }
        last = round.lastOrNull() ?: last
        return round
    }

    /** A random clip among the allowed movies, so movies with more clips come up proportionally. */
    private fun pickWeighted(allowed: Map<Any, List<Int>>): Any {
        var n = random.nextInt(allowed.values.sumOf { it.size })
        for ((movie, clips) in allowed) {
            if (n < clips.size) return movie
            n -= clips.size
        }
        return allowed.keys.first()
    }
}
