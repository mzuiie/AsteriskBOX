// Copyright 2026, AsteriskBOX contributors
// SPDX-License-Identifier: GPL-3.0

package features.home

import app.AppState
import app.OutboundGroupState
import app.modes.SingBoxModeDirect
import app.modes.SingBoxModeGlobal
import app.modes.SingBoxModeRule
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** T1c: 免流存续时直连模式不可切（模型层兜底，UI 芯片置灰是第一道）。 */
class HomeModeLockTest {

    private fun freeFlowState() = AppState(
        outboundGroups = listOf(OutboundGroupState(id = 1, name = "免流网关", ownerKey = "freeflow")),
        singBoxMode = SingBoxModeRule,
        proxyRunning = true,
    )

    @Test
    fun directModeBlockedUnderFreeFlow() {
        assertNull(buildHomeModeChange(freeFlowState(), SingBoxModeRule, SingBoxModeDirect))
    }

    @Test
    fun globalModeStillAllowedUnderFreeFlow() {
        assertNotNull(buildHomeModeChange(freeFlowState(), SingBoxModeRule, SingBoxModeGlobal))
    }

    @Test
    fun directModeAllowedWithoutFreeFlow() {
        assertNotNull(buildHomeModeChange(AppState(singBoxMode = SingBoxModeRule), SingBoxModeRule, SingBoxModeDirect))
    }
}
