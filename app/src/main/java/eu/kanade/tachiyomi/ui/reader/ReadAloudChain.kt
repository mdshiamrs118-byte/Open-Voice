package eu.kanade.tachiyomi.ui.reader

import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import eu.kanade.tachiyomi.ui.reader.viewer.Viewer
import eu.kanade.tachiyomi.ui.reader.viewer.pager.PagerViewer
import eu.kanade.tachiyomi.ui.reader.viewer.pager.R2LPagerViewer
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonViewer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import mihon.translation.ReadAloud
import mihon.translation.ReadAloudBoxes
import mihon.translation.ReadAloudDisplay
import mihon.translation.TextBox
import mihon.translation.TranslationPreferences
import mihon.translation.TranslationStore
import mihon.translation.speechTagFor
import java.util.Locale

/**
 * Reads on by itself after a tapped text box: the next boxes on screen (continuous read), scrolling or turning
 * the page to reach more (follow read).
 */
class ReadAloudChain(
    private val root: () -> View,
    private val scope: CoroutineScope,
    private val readAloud: ReadAloud,
    private val preferences: TranslationPreferences,
    private val viewer: () -> Viewer?,
) {

    private class Entry(val view: ReaderPageImageView, val page: ReaderPage, val box: TextBox, val rect: RectF)

    private var job: Job? = null
    private var chaining = false
    private var lastPage: ReaderPage? = null
    private var lastBox: TextBox? = null

    init {
        readAloud.onFinished = ::onFinished
    }

    /** Speaks a box the user tapped and, if continuous read is on, keeps going after it. */
    fun speakTapped(view: ReaderPageImageView, page: ReaderPage, box: TextBox) {
        job?.cancel()
        chaining = preferences.ttsContinuous.get()
        speak(view, page, box)
    }

    fun stop() {
        chaining = false
        job?.cancel()
        job = null
    }

    private fun speak(view: ReaderPageImageView, page: ReaderPage, box: TextBox) {
        lastPage = page
        lastBox = box
        val tag = if (box.translated) "en" else speechTagFor(box.text, ReadAloudDisplay.language)
        readAloud.speak(view, box, Locale.forLanguageTag(tag))
    }

    private fun onFinished() {
        if (!chaining) return
        job?.cancel()
        job = scope.launch { advance() }
    }

    private suspend fun advance() {
        val follow = preferences.ttsFollow.get()
        var empty = 0
        var guard = 0
        while (chaining && guard++ < MAX_STEPS) {
            val viewport = viewport()
            val entries = entries()
            val last = entries.indexOfFirst { it.page === lastPage && same(it.box, lastBox) }
            val after = if (last >= 0) entries.drop(last + 1) else entries
            val next = if (follow) after.firstOrNull() else after.firstOrNull { visible(it.rect, viewport) }
            if (next != null) {
                if (follow && !fullyVisible(next.rect, viewport)) scrollTo(next.rect, viewport)
                speak(next.view, next.page, next.box)
                return
            }
            if (!follow) break
            if (entries.isEmpty()) {
                if (++empty > EMPTY_TRIES) break
                delay(WAIT_MS)
                continue
            }
            if (entries.any { !TranslationStore.hasOcr(it.page, ReadAloudDisplay.language) }) {
                if (++empty > SCAN_TRIES) break
                delay(WAIT_MS)
                continue
            }
            if (++empty > EMPTY_TRIES) break
            if (!reveal(viewport)) break
            delay(PAGE_MS)
        }
        chaining = false
    }

    /** Brings the next part of the chapter into view; false when the viewer cannot. */
    private fun reveal(viewport: Rect): Boolean {
        when (val v = viewer()) {
            is WebtoonViewer -> v.recycler.smoothScrollBy(0, viewport.height() * 4 / 5, null, SCROLL_MS)
            is PagerViewer -> v.moveForward()
            else -> return false
        }
        return true
    }

    private fun scrollTo(rect: RectF, viewport: Rect) {
        val v = viewer() as? WebtoonViewer ?: return
        val dy = (rect.top - viewport.top - viewport.height() * 0.2f).toInt()
        if (dy != 0) v.recycler.smoothScrollBy(0, dy, null, SCROLL_MS)
    }

    private fun viewport(): Rect = Rect().also { root().getGlobalVisibleRect(it) }

    private fun visible(rect: RectF, viewport: Rect): Boolean =
        viewport.contains(rect.centerX().toInt(), rect.centerY().toInt())

    private fun fullyVisible(rect: RectF, viewport: Rect): Boolean =
        rect.top >= viewport.top && rect.bottom <= viewport.bottom &&
            rect.left >= viewport.left && rect.right <= viewport.right

    private fun same(a: TextBox, b: TextBox?): Boolean =
        b != null && a.left == b.left && a.top == b.top && a.right == b.right && a.bottom == b.bottom &&
            a.text == b.text

    /** The boxes of every page on screen in reading order. */
    private fun entries(): List<Entry> {
        val views = ArrayList<ReaderPageImageView>()
        collect(root(), views)
        val rtl = viewer() is R2LPagerViewer
        return views
            .sortedBy { view -> IntArray(2).also(view::getLocationOnScreen)[1] }
            .flatMap { view ->
                val page = view.readAloudPage ?: return@flatMap emptyList()
                val items = ReadAloudBoxes.of(page).mapNotNull { box ->
                    view.boxOnScreen(box)?.let { Entry(view, page, box, it) }
                }
                inReadingOrder(items, rtl)
            }
    }

    private fun collect(view: View, out: MutableList<ReaderPageImageView>) {
        if (!view.isShown) return
        if (view is ReaderPageImageView && view.readAloudPage != null) {
            if (view.getGlobalVisibleRect(Rect())) out.add(view)
            return
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) collect(view.getChildAt(i), out)
        }
    }

    /** Rows from the top down; within a row left to right, or right to left for manga. */
    private fun inReadingOrder(items: List<Entry>, rtl: Boolean): List<Entry> {
        val rows = ArrayList<MutableList<Entry>>()
        var rowTop = 0f
        var rowBottom = 0f
        for (item in items.sortedBy { it.rect.top }) {
            val centre = item.rect.centerY()
            if (rows.isNotEmpty() && centre > rowTop && centre < rowBottom) {
                rows.last().add(item)
            } else {
                rows.add(mutableListOf(item))
                rowTop = item.rect.top
                rowBottom = item.rect.bottom
            }
        }
        return rows.flatMap { row ->
            if (rtl) row.sortedByDescending { it.rect.centerX() } else row.sortedBy { it.rect.centerX() }
        }
    }

    private companion object {
        const val MAX_STEPS = 40
        const val EMPTY_TRIES = 8
        const val SCAN_TRIES = 20
        const val WAIT_MS = 700L
        const val PAGE_MS = 800L
        const val SCROLL_MS = 450
    }
}
