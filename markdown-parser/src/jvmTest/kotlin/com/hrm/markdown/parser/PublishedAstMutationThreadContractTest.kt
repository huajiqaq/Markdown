package com.hrm.markdown.parser

import com.hrm.markdown.parser.ast.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PublishedAstMutationThreadContractTest {
    @Test
    fun should_rejectForeignThreadWrites_when_documentMutationAuthorityIsActive() {
        val document = MarkdownParser().parse("text")
        val paragraph = document.children.single() as Paragraph
        val text = paragraph.children.single() as Text
        whileWorkerHasAuthority(
            authorize = { action -> withDocumentMutation(document, action) },
            beforeSignal = { text.literal = "authorized" },
        ) {
            assertEquals("authorized", text.literal)
            assertEquals(1, paragraph.children.size)
            assertFailsWith<IllegalStateException> { text.literal = "foreign" }
            assertFailsWith<IllegalStateException> { paragraph.appendChild(Text("foreign")) }
            assertFailsWith<IllegalStateException> { document.linkDefinitions["foreign"] = LinkReferenceDefinition() }
            assertFailsWith<IllegalStateException> { withDocumentMutation(document) { text.literal = "foreign scope" } }
        }
        assertEquals("authorized", text.literal)
        assertFailsWith<IllegalStateException> { text.literal = "scope ended" }
    }

    @Test
    fun should_rejectForeignThreadWrites_when_subtreeMutationAuthorityIsActive() {
        val document = MarkdownParser().parse("text")
        val paragraph = document.children.single() as Paragraph
        val text = paragraph.children.single() as Text
        whileWorkerHasAuthority(
            authorize = { action -> withSubtreeMutation(paragraph, action) },
            beforeSignal = { text.literal = "local authority" },
        ) {
            assertEquals("local authority", text.literal)
            assertEquals(1, paragraph.children.size)
            assertFailsWith<IllegalStateException> { text.literal = "foreign" }
            assertFailsWith<IllegalStateException> { paragraph.rawContent = "foreign" }
            assertFailsWith<IllegalStateException> { paragraph.clearChildren() }
        }
        assertEquals("local authority", text.literal)
        assertFailsWith<IllegalStateException> { text.literal = "scope ended" }
    }

    private fun whileWorkerHasAuthority(
        authorize: (() -> Unit) -> Unit,
        beforeSignal: () -> Unit,
        foreignThreadChecks: () -> Unit,
    ) {
        val ready = CountDownLatch(1)
        val release = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val worker = Thread({
            try {
                authorize {
                    beforeSignal()
                    ready.countDown()
                    check(release.await(10, TimeUnit.SECONDS)) { "Foreign-thread checks did not complete" }
                }
            } catch (error: Throwable) {
                failure.set(error)
                ready.countDown()
            }
        }, "published-ast-authority")
        worker.start()
        try {
            assertTrue(ready.await(10, TimeUnit.SECONDS), "Mutation authority did not become active")
            failure.get()?.let { throw AssertionError("Authority thread failed", it) }
            foreignThreadChecks()
        } finally {
            release.countDown()
            worker.join(10_000)
            assertTrue(!worker.isAlive, "Mutation authority thread did not finish")
        }
        failure.get()?.let { throw AssertionError("Authority thread failed", it) }
    }
}
