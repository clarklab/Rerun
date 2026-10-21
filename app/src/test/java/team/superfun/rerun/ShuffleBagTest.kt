package team.superfun.rerun

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import kotlin.random.Random

class ShuffleBagTest {
    // Shaped like the real library: one movie with 5 clips, a few with 2-4, the rest single clips.
    private val movies: List<Any> = List(5) { "tcm" } + List(4) { "shining" } + List(4) { "terrorvision" } +
        List(3) { "ernest" } + List(2) { "jaws" } + List(2) { "carrie" } + (1..20).map { "movie$it" }

    @Test
    fun everyRoundIsEachClipOnce() {
        val bag = ShuffleBag(movies, Random(1))
        repeat(200) { assertEquals(movies.indices.toList(), bag.nextRound().sorted()) }
    }

    @Test
    fun noMovieTwiceInARowAcrossRounds() {
        for (seed in 0 until 50) {
            val bag = ShuffleBag(movies, Random(seed))
            val played = (0 until 40).flatMap { bag.nextRound() }
            played.zipWithNext().forEach { (a, b) -> assertNotEquals(movies[a], movies[b]) }
        }
    }

    @Test
    fun crowdedMovieStillGetsSpacedOut() {
        // 4 of 7 clips from one movie: only possible as A x A x A x A.
        val crowded: List<Any> = listOf("a", "a", "a", "a", "b", "c", "d")
        for (seed in 0 until 50) {
            val round = ShuffleBag(crowded, Random(seed)).nextRound()
            round.zipWithNext().forEach { (a, b) -> assertNotEquals(crowded[a], crowded[b]) }
        }
    }

    @Test
    fun skippedClipsLeaveTheRound() {
        val bag = ShuffleBag(movies, Random(3))
        bag.skip(0)
        bag.skip(7)
        repeat(20) { assertEquals(movies.indices.filterNot { it == 0 || it == 7 }, bag.nextRound().sorted()) }
    }
}
