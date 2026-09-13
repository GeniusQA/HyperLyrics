package com.juren233.hle.providers.netease

import org.junit.Assert.*
import org.junit.Test

class NeteaseDiagnosticCapabilityTest {
    @Test fun enabledOnlyByCore() {
        assertTrue(NeteaseDiagnosticCapability.resolve { true })
        assertFalse(NeteaseDiagnosticCapability.resolve { false })
    }
    @Test fun olderCoreAndCapabilityFailuresAreDisabled() {
        assertFalse(NeteaseDiagnosticCapability.resolve { throw NoSuchMethodError() })
        assertFalse(NeteaseDiagnosticCapability.resolve { throw AbstractMethodError() })
        assertFalse(NeteaseDiagnosticCapability.resolve { throw IllegalStateException() })
    }
}
