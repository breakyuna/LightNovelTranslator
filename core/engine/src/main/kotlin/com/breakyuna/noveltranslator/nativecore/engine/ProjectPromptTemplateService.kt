package com.breakyuna.noveltranslator.nativecore.engine

import com.breakyuna.noveltranslator.nativecore.domain.ProjectTextPolicy
import com.breakyuna.noveltranslator.nativecore.domain.ProjectTextPolicyCodec
import com.breakyuna.noveltranslator.nativecore.domain.PromptTemplate

/** Rendering prepares a new policy draft; it neither enables prompts nor persists the project. */
object ProjectPromptTemplateService {
    fun render(policy: ProjectTextPolicy, template: PromptTemplate, variables: Map<String, String>,
        checkActive: () -> Unit = {}): ProjectTextPolicy {
        checkActive()
        val prompt = template.render(variables, checkActive)
        // ProjectTextPolicy enforces its own tighter 16 KiB character limit. Never truncate
        // the template engine's larger output to make it fit a project field.
        return policy.changed(prompt = prompt).also { ProjectTextPolicyCodec.encode(it) }
    }
}
