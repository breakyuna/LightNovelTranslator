package com.breakyuna.noveltranslator.ui.navigation

sealed class AppDestination(val route: String) {
    // Top-Level Destinations
    object Projects : AppDestination("projects")
    object Tasks : AppDestination("tasks")
    object History : AppDestination("history")
    object Settings : AppDestination("settings?tab={tab}") {
        fun createRoute(tab: Int = -1) = "settings?tab=$tab"
    }

    // Project Context Destinations (Entered from Project)
    object Workspace : AppDestination("workspace/{projectId}") {
        fun createRoute(projectId: Long) = "workspace/$projectId"
    }

    object Translation : AppDestination("translation/{projectId}") {
        fun createRoute(projectId: Long) = "translation/$projectId"
    }

    object Glossary : AppDestination("glossary/{projectId}") {
        fun createRoute(projectId: Long) = "glossary/$projectId"
    }

    object Reader : AppDestination("reader/{chapterId}") {
        fun createRoute(chapterId: Long) = "reader/$chapterId"
    }
}
