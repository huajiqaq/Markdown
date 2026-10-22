package com.hrm.markdown.parser

import com.hrm.markdown.parser.ast.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

class AstCollectionReadThreadTest {
    @Test
    fun should_publishEquivalentReadOnlyViewsAndRejectForeignWrites_when_viewsAreFirstReadConcurrently() {
        val document = Document()
        document.linkDefinitions["key"] = LinkReferenceDefinition("key", "/target")
        document.sealPublication(emptyList())
        val ready = CountDownLatch(2)
        val startSignal = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        withDocumentMutation(document) {
            val workers = List(2) {
                Thread {
                    try {
                        ready.countDown()
                        check(startSignal.await(10, TimeUnit.SECONDS))
                        repeat(100) {
                            assertEquals(setOf("key"), document.linkDefinitions.keys)
                            assertEquals("/target", document.linkDefinitions.values.single().destination)
                            val entry = document.linkDefinitions.entries.single()
                            assertEquals("key", entry.key)
                            assertFailsWith<IllegalStateException> { entry.setValue(LinkReferenceDefinition()) }
                            assertFailsWith<IllegalStateException> { document.linkDefinitions.keys.remove("key") }
                        }
                    } catch (error: Throwable) {
                        failure.compareAndSet(null, error)
                    }
                }.apply { start() }
            }
            try {
                assertTrue(ready.await(10, TimeUnit.SECONDS))
            } finally {
                startSignal.countDown()
                workers.forEach { worker ->
                    worker.join(10_000)
                    assertFalse(worker.isAlive)
                }
            }
        }
        failure.get()?.let { throw AssertionError("Concurrent collection access failed", it) }
        assertEquals("/target", document.linkDefinitions.getValue("key").destination)
    }
}
