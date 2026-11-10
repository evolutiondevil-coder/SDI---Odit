package com.odit.obd

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration

/**
 * Drives the real UI in demo mode (simulated adapter + car) and stores screenshots in
 * app/build/screenshots for a visual check.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w393dp-h851dp-xxhdpi")
class AppFlowTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val outDir = File("build/screenshots").apply { mkdirs() }

    private fun pump(ms: Long = 100) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
        compose.waitForIdle()
    }

    private fun waitForText(text: String, timeoutMs: Long = 120_000) {
        var waited = 0L
        while (waited < timeoutMs) {
            if (compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()) return
            pump(100)
            waited += 100
        }
        fail("Text not shown: $text")
    }

    private fun waitWhileBusy(text: String, timeoutMs: Long = 120_000) {
        var waited = 0L
        while (waited < timeoutMs) {
            if (compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isEmpty()) return
            pump(100)
            waited += 100
        }
        fail("Still busy: $text")
    }

    private fun shot(name: String) {
        pump(300)
        // captureToImage() waits for a frame callback that never comes on Robolectric; draw the window directly.
        val view = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(Canvas(bmp)) }
        File(outDir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun click(text: String) {
        compose.onNodeWithText(text).performScrollTo().performClick()
        pump()
    }

    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        pump()
    }

    @Test
    fun demoFlow() {
        waitForText("Търси адаптери")
        shot("01_connect")

        click("Демо режим (без автомобил)")
        waitForText("Volkswagen")
        waitWhileBusy("Четене на данни")
        shot("02_home")

        compose.onNodeWithText("Грешки на двигателя").performClick()
        waitForText("P0301")
        waitWhileBusy("Четене на грешките")
        shot("03_dtc")
        back()

        compose.onNodeWithText("Всички модули").performClick()
        waitForText("Бързо")
        click("Бързо")
        waitForText("Намерени модули")
        shot("04_modules")
        back()

        compose.onNodeWithText("Данни на живо").performClick()
        waitForText("об/мин")
        pump(2_000)
        shot("05_live")
        back()

        compose.onNodeWithText("Готовност").performClick()
        waitForText("Неготови монитори")
        shot("06_readiness")
        back()

        compose.onNodeWithText("Изтрий грешките").performClick()
        waitForText("Изтриване на грешките?")
        compose.onNodeWithText("Изтрий").performClick()
        pump()
        waitForText("Лампата на двигателя не свети")
        waitWhileBusy("Изтриване")
        shot("07_after_clear")
    }
}
