package com.odit.obd

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertTrue
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
        // Background work (simulated network) runs on real threads with real time.
        Thread.sleep(5)
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
        answerIntro(true)
        waitForText("Търси адаптери")
        shot("01_connect")

        click("Демо режим (без автомобил)")
        waitForText("Обикновен автомобил")
        compose.onNodeWithText("Обикновен автомобил").performClick()
        pump()
        waitForText("Volkswagen")
        waitWhileBusy("Четене на данни")
        waitWhileBusy("Търсене на допълнителни данни онлайн")
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

        clearFromHome()
        // The list is emptied and the result stays on the screen (plural for several codes).
        waitForText("Грешките са изтрити (4).")
        waitForText("Постоянните грешки (1) не се изтриват с уред")
        assertTrue(compose.onAllNodesWithText("P0171").fetchSemanticsNodes().isEmpty())
        shot("07_after_clear")
        back()
        waitForText("Лампата на двигателя не свети")
    }

    /** The first-run "Онлайн помощ" question (shown only while it has not been answered). */
    private fun answerIntro(enable: Boolean) {
        waitForText("Търси адаптери")
        pump(300)
        val button = if (enable) "Включи" else "Не, без интернет"
        if (compose.onAllNodesWithText(button).fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithText(button).performClick()
            pump()
        }
    }

    /** Starts a demo car from the scenario dialog. */
    private fun startDemo(title: String, online: Boolean = true) {
        answerIntro(online)
        click("Демо режим (без автомобил)")
        waitForText(title)
        if (!online) {
            compose.onNodeWithText("Симулирай интернет (онлайн помощ)").performClick()
            pump()
        }
        compose.onNodeWithText(title).performClick()
        pump()
        waitForText("Грешки на двигателя")
        waitWhileBusy("Свързване")
        waitWhileBusy("Четене на данни")
        waitWhileBusy("Търсене на допълнителни данни онлайн")
    }

    @Test
    fun vinFoundOnlyAfterModuleScan() {
        startDemo("VIN само в друг модул")
        waitForText("не е намерен")
        shot("10_vin_missing")
        compose.onNodeWithText("Всички модули").performClick()
        click("Бързо")
        waitForText("Намерени модули")
        back()
        waitForText("WVWZZZ1KZ8W123456")
        waitForText("UDS 22 F190")
        shot("11_vin_from_module")
    }

    @Test
    fun conflictingVinsAreShown() {
        startDemo("Различни VIN в модулите")
        compose.onNodeWithText("Всички модули").performClick()
        click("Бързо")
        waitForText("Намерени модули")
        back()
        waitForText("Различни VIN в модулите")
        shot("12_vin_conflict")
    }

    @Test
    fun unknownMakeOfflineShowsCountry() {
        startDemo("Непозната марка", online = false)
        waitForText("Беларус")
        repeat(400) { pump(10) } // give a (wrong) online answer time to appear
        assertTrue(compose.onAllNodesWithText("онлайн", substring = true).fetchSemanticsNodes().isEmpty())
        shot("13_unknown_wmi_offline")
    }

    @Test
    fun unknownMakeOnlineFillsMake() {
        startDemo("Непозната марка", online = true)
        waitForText("MAZ (онлайн, вероятно)")
        shot("14_unknown_wmi_online")
    }

    @Test
    fun kLineVinViaKwp() {
        startDemo("Стара кола (K-line)", online = false)
        waitForText("VF1BB0A0526123456")
        waitForText("KWP 1A 90")
        waitForText("Renault")
        shot("15_kline")
    }

    private fun clearFromHome() {
        compose.onNodeWithText("Изтрий грешките").performClick()
        waitForText("Изтриване на грешките?")
        compose.onNodeWithText("Изтрий").performClick()
        pump()
        waitWhileBusy("Изтриване")
        waitWhileBusy("Проверка на модулите")
        waitWhileBusy("Четене")
    }

    /** Scrolls the lazy list of the current screen to a button and taps it. */
    private fun clickInList(text: String) {
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text))
        pump()
        compose.onNodeWithText(text).performClick()
        pump()
    }

    @Test
    fun clearedModuleRowsDisappear() {
        startDemo("Обикновен автомобил", online = false)
        compose.onNodeWithText("Всички модули").performClick()
        click("Бързо")
        waitForText("Намерени модули: 7 · с грешки: 6")
        clickInList("Изтрий грешките във всички модули")
        waitForText("Изтриване в 6 модула?")
        compose.onNodeWithText("Изтрий").performClick()
        pump()
        waitWhileBusy("Изтриване")
        waitWhileBusy("Четене")
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Грешките са изтрити (7)."))
        waitForText("Намерени модули: 7 · с грешки: 0")
        assertTrue(compose.onAllNodesWithText("Изтрий грешките в този модул").fetchSemanticsNodes().isEmpty())
        shot("16_modules_cleared")
    }

    @Test
    fun activeFaultIsShownAsReturned() {
        startDemo("Активна повреда", online = false)
        clearFromHome()
        waitForText("Изтрити: 3. Върнаха се веднага: 1")
        waitForText("Върна се веднага след изтриването")
        waitForText("P0301")
        assertTrue(compose.onAllNodesWithText("P0171").fetchSemanticsNodes().isEmpty())
        shot("17_fault_returned")
    }

    @Test
    fun oldCarVinFieldAndDirectKLine() {
        answerIntro(false)
        click("Демо режим (без автомобил)")
        waitForText("Стара кола без OBD2")
        compose.onNodeWithText("Симулирай интернет (онлайн помощ)").performClick()
        pump()
        compose.onNodeWithText("Стара кола без OBD2").performClick()
        pump()
        waitForText("Няма връзка с автомобила", timeoutMs = 300_000)
        // Its engine wakes up on the K-line but ignores OBD requests (like the real Scénic I).
        waitForText("отговори на събуждането по K-line")
        shot("18_old_car_not_found")

        // The VIN field is right there; the VIN identifies the car.
        compose.onNode(hasSetTextAction()).performTextInput("VF1JA0B0521234567")
        pump()
        compose.onNodeWithText("Търси").performClick()
        pump()
        waitForText("Scénic I (вероятно)")
        waitForText("между 1996–2003 (по модела)")
        shot("19_old_car_identified")

        click("Директна връзка (стари коли, K-line)")
        waitForText("Намерени модули: 3 · с грешки: 2", timeoutMs = 300_000)
        waitForText("DTC 0120")
        waitForText("Двигател (впръскване)")
        shot("20_old_car_modules")

        clickInList("Изтрий грешките във всички модули")
        waitForText("Изтриване в 2 модула?")
        compose.onNodeWithText("Изтрий").performClick()
        pump()
        waitWhileBusy("Изтриване")
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Грешките са изтрити (3)."))
        waitForText("Намерени модули: 3 · с грешки: 0")
        shot("21_old_car_cleared")
    }

    @Test
    fun oldVwShowsWhichModulesCannotBeRead() {
        answerIntro(false)
        click("Демо режим (без автомобил)")
        waitForText("Стар VW (K-line)")
        compose.onNodeWithText("Симулирай интернет (онлайн помощ)").performClick()
        pump()
        compose.onNodeWithText("Стар VW (K-line)").performClick()
        pump()
        waitForText("Няма връзка с автомобила", timeoutMs = 300_000)

        // The adapter check is offered right in the failure card.
        click("Провери адаптера (около 20 с)")
        waitWhileBusy("Проверка на адаптера", timeoutMs = 300_000)
        waitForText("K-line изглежда реално поддържан")

        compose.onNode(hasSetTextAction()).performTextInput("WVWZZZ1JZ2W123456")
        pump()
        compose.onNodeWithText("Търси").performClick()
        pump()
        waitForText("Golf IV / Bora (вероятно)")
        shot("22_old_vw_identified")

        click("Директна връзка (стари коли, K-line)")
        // Engine and cluster (KWP2000), immobiliser and radio (KW1281).
        waitForText("Намерени модули: 4", timeoutMs = 600_000)
        waitForText("VAG 16684 / P0300")
        shot("23_old_vw_modules")
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("стария протокол на VW (KW1281)", substring = true))
        pump()
        waitForText("стария протокол на VW (KW1281)")
        shot("24_old_vw_kw1281")
    }
}
