package io.github.willzy12h.nocturne.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import io.github.willzy12h.nocturne.R
import io.github.willzy12h.nocturne.core.protocol.Mode

internal fun captureTonightSurfaces(ui: U06Fixture, shot: (String) -> Unit) {
    val compose = ui.compose
    fun dialogButton(text: String) = compose.onNode(hasText(text) and hasClickAction() and hasAnyAncestor(isDialog()))
    compose.onNodeWithTag("tonight-change").performScrollTo().performClick()
    compose.onNodeWithText(ui.s(R.string.parity_choose_profile)).assertIsDisplayed()
    shot("tonight-chooser")
    dialogButton(ui.s(R.string.uat_quick_cancel)).performClick()
    compose.onNode(hasText(ui.s(R.string.action_edit)) and hasClickAction() and
        hasAnyAncestor(hasTestTag("quick-controls"))).performScrollTo().performClick()
    shot("quick-defaults")
    val heat = ui.s(R.string.uat_quick_edit, ui.s(Format.modeNameRes(Mode.HEAT)))
    dialogButton(heat).performScrollTo().performClick()
    shot("quick-default-heat")
    captureScrollEnd(ui)
    shot("quick-default-heat-bottom")
    dialogButton(ui.s(R.string.uat_quick_cancel)).performClick()
    dialogButton(ui.s(R.string.uat_setup_done)).performClick()
}

/** Additional opt-in native captures; callers own the palette and deterministic GraphLifecycle fixture. */
internal fun captureEditorSurfaces(ui: U06Fixture, shot: (String) -> Unit) {
    val compose = ui.compose
    fun tag(value: String) = compose.onNodeWithTag(value).performScrollTo()
    fun doneMiddle() = compose.onNode(hasText(ui.s(R.string.uat_setup_done)) and hasClickAction() and
        hasAnyAncestor(hasTestTag("editor-middle-sheet"))).performScrollTo().performClick()
    shot("editor")
    tag("editor-middle-summary").performClick()
    compose.onNodeWithTag("editor-middle-sheet").assertIsDisplayed()
    compose.onNode(hasText(ui.s(R.string.uat_setup_done)) and hasClickAction() and
        hasAnyAncestor(hasTestTag("editor-middle-sheet"))).performScrollTo().assertIsDisplayed()
    shot("middle-top")
    tag("middle-shape-6").performClick()
    shot("middle-shapes")
    tag("middle-fan-shape-QUIET_MIDDLE").performClick()
    shot("middle-quiet-fan")
    compose.onNodeWithContentDescription(ui.s(R.string.slider_rise_minutes)).performScrollTo().assertIsDisplayed()
    shot("middle-quiet-fan-bottom")
    doneMiddle()
    tag("editor-phase-START-0").performClick()
    tag("step-mode-HEAT").performClick()
    tag("step-done")
    shot("step-heat")
    tag("step-type-time").performClick()
    compose.onNodeWithTag("type-time-hours").assertIsDisplayed().performClick()
    shot("type-time")
    compose.onNode(hasText(ui.s(R.string.feature_cancel)) and hasClickAction() and
        hasAnyAncestor(isDialog() and hasAnyDescendant(hasTestTag("type-time-hours")))).performClick()
    tag("step-mode-EXTENDED_HEAT").performClick()
    tag("step-done")
    shot("step-long")
    tag("step-delete")
    shot("step-long-bottom")
    tag("step-mode-TURBO").performClick()
    tag("step-done")
    shot("step-turbo-fixed")
    tag("step-delete")
    shot("step-turbo-bottom")
    tag("step-done").performClick()
    tag("editor-add-START").performClick()
    compose.onNodeWithTag("editor-add-sheet").assertIsDisplayed()
    shot("add-step")
    tag("add-choice-${Mode.WAIT}")
    tag("add-next")
    shot("add-step-bottom")
    compose.onNode(hasText(ui.s(R.string.feature_cancel)) and hasClickAction() and
        hasAnyAncestor(hasTestTag("editor-add-sheet"))).performScrollTo().performClick()
    val row = tag("editor-phase-START-0")
    val density = compose.density.density
    row.performTouchInput {
        down(Offset(23f * density, centerY))
        advanceEventTime(android.view.ViewConfiguration.getLongPressTimeout().toLong() + 1)
        moveBy(Offset(0f, 2f))
    }
    compose.waitForIdle()
    try { shot("editor-drag") } finally { row.performTouchInput { cancel() } }
}

internal fun captureProfileSurfaces(ui: U06Fixture, shot: (String) -> Unit) {
    val compose = ui.compose
    val profile = ui.graph.repository.profiles.value.first()
    compose.onNodeWithTag("profile-more-${profile.id}", useUnmergedTree = true).performScrollTo().performClick()
    compose.onNodeWithText(ui.s(R.string.pin_menu)).assertIsDisplayed()
    shot("profiles-overflow")
    compose.onNodeWithText(ui.s(R.string.pin_menu)).performClick()
    shot("profiles-pin")
    compose.onNode(hasText(ui.s(R.string.feature_cancel)) and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
    compose.onNodeWithTag("profile-more-${profile.id}", useUnmergedTree = true).performScrollTo().performClick()
    compose.onNodeWithText(ui.s(R.string.profiles_delete)).performClick()
    shot("profiles-delete")
    compose.onNode(hasText(ui.s(R.string.feature_cancel)) and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
    compose.onNodeWithTag("profiles-new").performClick()
    compose.onNodeWithText(ui.s(R.string.profiles_start_from)).assertIsDisplayed()
    shot("profiles-new")
    val last = io.github.willzy12h.nocturne.core.plan.Presets.all.last().name
    compose.onNode(hasText(last) and hasClickAction() and hasAnyAncestor(isDialog())).performScrollTo().assertIsDisplayed()
    shot("profiles-new-bottom")
    compose.onNode(hasText(ui.s(R.string.feature_cancel)) and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
    val incoming = profile.copy(id = "capture-shared-profile", name = "Shared night")
    val json = io.github.willzy12h.nocturne.core.store.BackupCodec.encode(
        io.github.willzy12h.nocturne.core.store.BackupBundle(createdAt = ui.graph.now().toString(),
            profiles = listOf(incoming), settings = io.github.willzy12h.nocturne.core.store.Settings()))
    val uri = android.net.Uri.parse("content://capture/shared-profile.json")
    org.robolectric.Shadows.shadowOf(compose.activity.contentResolver).registerInputStream(uri, json.byteInputStream())
    // The fixture's fake transport is enabled by a legacy flag, not a disposable demo session.
    // Imports are shown through their real, enabled UI route and never applied by these captures.
    check(!ui.graph.repository.isDemoSession)
    compose.runOnUiThread { kotlinx.coroutines.runBlocking { ui.graph.repository.updateSettings { it.copy(demoMode = false) } } }
    ui.tab("Profiles") // A real/demo flag change deliberately replaces SessionApp and its navigation state.
    compose.onNodeWithTag("profiles-import").assertIsEnabled().performClick()
    val request = requireNotNull(org.robolectric.Shadows.shadowOf(compose.activity).nextStartedActivityForResult)
    check(request.intent.action == android.content.Intent.ACTION_OPEN_DOCUMENT)
    compose.runOnUiThread {
        check(compose.activity.activityResultRegistry.dispatchResult(request.requestCode, android.app.Activity.RESULT_OK,
            android.content.Intent().setData(uri)))
    }
    ui.waitUntil { ui.model.importPreview.value != null }
    compose.onNodeWithText(ui.s(R.string.parity_profile_import_title)).assertIsDisplayed()
    shot("profiles-import")
    compose.onNodeWithText(ui.s(R.string.import_show_details, ui.s(R.string.import_profiles))).performScrollTo().performClick()
    captureScrollEnd(ui)
    shot("profiles-import-details")
    compose.onNode(hasText(ui.s(R.string.feature_cancel)) and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
    compose.runOnUiThread { kotlinx.coroutines.runBlocking { ui.graph.repository.updateSettings { it.copy(demoMode = true) } } }
    ui.waitUntil { ui.graph.link.state.value == io.github.willzy12h.nocturne.core.link.LinkState.READY && ui.graph.link.status.value != null }
}

/** Move each visible scroll area to its genuine end, using its published semantics. */
internal fun captureScrollEnd(ui: U06Fixture) {
    val compose = ui.compose
    val scrollable = SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange) and
        SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollBy)
    val dialogScrollers = compose.onAllNodes(scrollable and hasAnyAncestor(isDialog()), useUnmergedTree = true).fetchSemanticsNodes()
    val scrollers = dialogScrollers.ifEmpty { compose.onAllNodes(scrollable, useUnmergedTree = true).fetchSemanticsNodes() }
    scrollers.forEach { node ->
        val range = node.config[SemanticsProperties.VerticalScrollAxisRange]
        val delta = (range.maxValue() - range.value()) * if (range.reverseScrolling) -1f else 1f
        if (delta != 0f) compose.onNode(SemanticsMatcher("capture scroll area ${node.id}") { it.id == node.id }, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, delta) }
    }
    compose.waitForIdle()
}
