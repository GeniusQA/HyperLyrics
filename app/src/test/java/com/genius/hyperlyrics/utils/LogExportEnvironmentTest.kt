/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.genius.hyperlyrics.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class LogExportEnvironmentTest {
    @Test
    fun `installing package takes precedence as the install source`() {
        assertEquals(
            "installer",
            selectInstallSourcePackage(
                installingPackageName = "installer",
                initiatingPackageName = "initiator",
                originatingPackageName = "originator",
            ),
        )
    }

    @Test
    fun `install source falls back through initiating and originating packages`() {
        assertEquals(
            "initiator",
            selectInstallSourcePackage(null, "initiator", "originator"),
        )
        assertEquals(
            "originator",
            selectInstallSourcePackage(null, " ", "originator"),
        )
        assertEquals(null, selectInstallSourcePackage(null, null, null))
    }
}
