package com.breakyuna.noveltranslator.ui.navigation

sealed class AppDestination(val route: String) {
    // Top-Level Destinations
    object Bookshelf : AppDestination("bookshelf")
    object Tasks : AppDestination("tasks?bookId={bookId}") {
        fun createRoute(bookId: Long? = null) = if (bookId != null && bookId > 0) "tasks?bookId=$bookId" else "tasks"
    }
    object History : AppDestination("history")
    object Settings : AppDestination("settings?tab={tab}") {
        fun createRoute(tab: Int = -1) = "settings?tab=$tab"
    }

    object BookWorkbench : AppDestination("workbench/{bookId}") {
        fun createRoute(bookId: Long) = "workbench/$bookId"
    }

    object PlatformReader : AppDestination("platform_reader/{bookId}?chapterId={chapterId}") {
        fun createRoute(bookId: Long, chapterId: Long? = null) = "platform_reader/$bookId?chapterId=${chapterId ?: -1L}"
    }

    /**
     * Agent assistant chat. Top-level route: the bottom bar and the rail stay
     * visible while it is on top (see [TopLevelDestination.AGENT]), unlike the
     * reader and the workbench.
     *
     * [sessionId] is optional; a negative value means "resume the most recent
     * conversation", which is what the plain entry points navigate to. Because
     * the argument has a default value, the bare route "agent" registered by
     * [TopLevelDestination.AGENT] resolves to this same destination.
     */
    object AgentChat : AppDestination("agent?sessionId={sessionId}") {
        fun createRoute(sessionId: Long? = null) = "agent?sessionId=${sessionId ?: -1L}"
    }
}
