package com.breakyuna.noveltranslator.nativecore.workspace

import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceRuntimeScriptFailureTest {
    @Test fun typedEngineTimeoutBecomesARunTimeout() {
        val fixture = RuntimeFixture()
        fixture.engine.failure = WorkspaceScriptTimeoutException()
        assertTrue(fixture.run() is WorkspaceRunOutcome.TimedOut)
    }

    @Test fun typedEngineStartupFailureBecomesEngineFailed() {
        val fixture = RuntimeFixture()
        fixture.engine.failure = WorkspaceScriptEngineUnavailable(IllegalStateException("no native runtime"))
        assertTrue(fixture.run() is WorkspaceRunOutcome.EngineFailed)
    }
}
