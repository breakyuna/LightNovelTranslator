package com.breakyuna.noveltranslator.nativeapp

import com.breakyuna.noveltranslator.nativecore.workspace.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class NativeAgentSkillPackageTest {
    private fun payload(resources: JsonObject = buildJsonObject {}) = buildJsonObject {
        put("id", "user-check"); put("name", "User check"); put("content", "# Check\nReview terminology")
        put("resources", resources)
    }
    @Test fun generatedAndImportedPackagesKeepFilesAndDefaultPermissions() {
        val pkg = NativeAgentSkillPackage.decode(payload(buildJsonObject { put("scripts/check.js", "lg.log('ok')") }))
        assertEquals("lg.log('ok')", pkg.resources["scripts/check.js"])
        assertFalse(pkg.draft.policy.allowFetch)
        assertFalse(pkg.draft.policy.allowSearch)
        assertFalse(pkg.draft.policy.allowProposeChanges)
    }
    @Test fun permissionEscalationAndWrongScalarTypesAreRejected() {
        for (extra in listOf("policy" to buildJsonObject { put("allowFetch", true) },
            "enabled" to JsonPrimitive("true"))) {
            assertThrows(IllegalArgumentException::class.java) {
                NativeAgentSkillPackage.decode(JsonObject(payload() + extra))
            }
        }
    }
    @Test fun metadataAndTraversalCannotBecomeResources() {
        for (path in listOf("../escape.js", "/escape.js", "scripts/../../escape.js", "SKILL.md", "skill.json", "a\\b.js")) {
            assertThrows(Exception::class.java) {
                NativeAgentSkillPackage.decode(payload(buildJsonObject { put(path, "bad") }))
            }
        }
    }
}
