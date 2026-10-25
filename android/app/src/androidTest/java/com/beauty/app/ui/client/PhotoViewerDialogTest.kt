package com.beauty.app.ui.client

import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.beauty.app.data.BeautyRepository
import com.beauty.app.data.api.BeautyApi
import com.beauty.app.data.api.VisitAttachmentDto
import com.beauty.app.data.local.BeautyDatabase
import com.beauty.app.ui.theme.BeautyTheme
import java.io.ByteArrayOutputStream
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class PhotoViewerDialogTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var database: BeautyDatabase
    private val downloadCalls = AtomicInteger()
    private val requestedAttachments = CopyOnWriteArrayList<Pair<String, String>>()
    private var dismissCalls = 0
    private lateinit var viewerTarget: MutableState<ViewerTarget?>

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), BeautyDatabase::class.java
        ).allowMainThreadQueries().build()
        viewerTarget = mutableStateOf(null)
        composeRule.setContent {
            BeautyTheme(darkTheme = false) {
                viewerTarget.value?.let { target ->
                    PhotoViewerDialog(target.repository, target.organizationId, target.attachment) {
                        dismissCalls++
                        viewerTarget.value = null
                    }
                }
            }
        }
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun retryLoadsPhoto_thenCacheIsScopedByOrganizationAndAttachment() {
        val repository = BeautyRepository(retryThenSucceedApi(), database.clientDao(), database.visitDao())
        val attachmentId = "attachment-${UUID.randomUUID()}"

        showViewer(repository, organizationId = "org-a", attachmentId = attachmentId)
        composeRule.waitUntil(timeoutMillis = 5_000) { downloadCalls.get() == 1 }
        waitForText(text("photo_unavailable"))
        composeRule.onNodeWithText(text("photo_unavailable")).assertIsDisplayed()
        composeRule.onNodeWithText(text("ui2_retry")).performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { downloadCalls.get() == 2 }
        waitForImage(text("photo_tag_before"))

        // Reopening the same attachment in the same organization uses the memory cache.
        composeRule.runOnIdle { viewerTarget.value = null }
        composeRule.waitForIdle()
        showViewer(repository, organizationId = "org-a", attachmentId = attachmentId)
        waitForImage(text("photo_tag_before"))
        assertEquals(2, downloadCalls.get())

        // A matching attachment id in another organization, and a different id in this
        // organization, must each make their own authenticated request.
        showViewer(repository, organizationId = "org-b", attachmentId = attachmentId)
        composeRule.waitUntil(timeoutMillis = 5_000) { downloadCalls.get() == 3 }
        waitForImage(text("photo_tag_before"))
        showViewer(repository, organizationId = "org-a", attachmentId = "other-${UUID.randomUUID()}")
        composeRule.waitUntil(timeoutMillis = 5_000) { downloadCalls.get() == 4 }
        waitForImage(text("photo_tag_before"))
        assertEquals(
            listOf("org-a" to attachmentId, "org-a" to attachmentId, "org-b" to attachmentId),
            requestedAttachments.take(3)
        )
        assertEquals("org-a", requestedAttachments[3].first)
    }

    @Test
    fun documentPhoto_showsCaptionAndAccessibleClose() {
        showViewer(BeautyRepository(retryThenSucceedApi(), database.clientDao(), database.visitDao()), tag = "DOCUMENT", caption = "Consent form")

        composeRule.onNodeWithText(text("photo_tag_document")).assertIsDisplayed()
        composeRule.onNodeWithText("Consent form").assertIsDisplayed()
        composeRule.onNodeWithContentDescription(text("close")).performClick()

        assertEquals(1, dismissCalls)
    }

    @Test
    fun systemBack_dismissesViewer() {
        showViewer(BeautyRepository(retryThenSucceedApi(), database.clientDao(), database.visitDao()))

        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        composeRule.waitForIdle()

        assertEquals(1, dismissCalls)
    }

    private fun showViewer(
        repository: BeautyRepository,
        organizationId: String = "org-a",
        attachmentId: String = "attachment-${UUID.randomUUID()}",
        tag: String = "BEFORE",
        caption: String? = null
    ) {
        val attachment = VisitAttachmentDto(
            id = attachmentId, visitId = "visit-1", fileUrl = "", fileType = "image/jpeg",
            fileSize = 1, caption = caption, tag = tag, uploadedAt = "2026-10-26T10:00:00"
        )
        composeRule.runOnIdle { viewerTarget.value = ViewerTarget(repository, organizationId, attachment) }
    }

    private fun waitForText(value: String) = composeRule.waitUntil(timeoutMillis = 5_000) {
        composeRule.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty()
    }

    private fun waitForImage(description: String) = composeRule.waitUntil(timeoutMillis = 5_000) {
        composeRule.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty()
    }

    private fun retryThenSucceedApi(): BeautyApi = Proxy.newProxyInstance(
        BeautyApi::class.java.classLoader,
        arrayOf(BeautyApi::class.java)
    ) { _, method, arguments ->
        if (method.name == "downloadAttachment") {
            @Suppress("UNCHECKED_CAST")
            requestedAttachments += (arguments[0] as String) to (arguments[1] as String)
            if (downloadCalls.incrementAndGet() == 1) error("offline")
            return@newProxyInstance imageBytes()
        }
        error("Unexpected API call: ${method.name}")
    } as BeautyApi

    private fun imageBytes(): ByteArray = ByteArrayOutputStream().use { output ->
        Bitmap.createBitmap(2, 1, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff336699.toInt()) }
            .compress(Bitmap.CompressFormat.PNG, 100, output)
        output.toByteArray()
    }

    private fun text(resource: String): String = composeRule.activity.getString(
        composeRule.activity.resources.getIdentifier(resource, "string", composeRule.activity.packageName)
    )

    private data class ViewerTarget(
        val repository: BeautyRepository,
        val organizationId: String,
        val attachment: VisitAttachmentDto
    )
}
