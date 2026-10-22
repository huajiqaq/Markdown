package com.hrm.markdown.parser

import com.hrm.markdown.parser.flavour.CommonMarkFlavour
import com.hrm.markdown.parser.flavour.ExtendedFlavour
import com.hrm.markdown.parser.flavour.FlavourCache
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FlavourCacheConcurrencyTest {
    @Test
    fun should_publishSingleOwnedPlan_when_accessedConcurrently() {
        for (flavour in listOf(ExtendedFlavour, CommonMarkFlavour.copy(enableGfmAutolinks = true))) {
            val workerCount = 16
            val callsPerWorker = 200
            val start = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(workerCount)
            val results = ConcurrentLinkedQueue<FlavourCache>()
            try {
                val futures = List(workerCount) {
                    executor.submit {
                        start.await()
                        repeat(callsPerWorker) { results += FlavourCache.of(flavour) }
                    }
                }
                start.countDown()
                futures.forEach { it.get(10, TimeUnit.SECONDS) }
            } finally {
                executor.shutdownNow()
                assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
            }
            val published = results.first()
            assertEquals(workerCount * callsPerWorker, results.size)
            results.forEach { assertSame(published, it) }
        }
    }

    @Test
    fun should_keepConfigurationsIndependent_when_plansAreCreatedConcurrently() {
        val flavours = List(32) { CommonMarkFlavour.copy(enableGfmAutolinks = true) }
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(8)
        val plans = try {
            val futures = flavours.map { flavour ->
                executor.submit<FlavourCache> { start.await(); FlavourCache.of(flavour) }
            }
            start.countDown()
            futures.map { it.get(10, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
        for (index in flavours.indices) {
            assertSame(plans[index], FlavourCache.of(flavours[index]))
            for (previous in 0 until index) assertNotSame(plans[previous], plans[index])
        }
    }
}
