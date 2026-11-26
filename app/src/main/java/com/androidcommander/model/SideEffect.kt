package com.androidcommander.model

import java.io.File

/** One-shot effects the ViewModel can't perform itself because they need an Activity/Context. */
sealed interface SideEffect {
    data class ViewFile(val file: File) : SideEffect
    data class ExploreDir(val dir: File) : SideEffect
    data object RequestAllFilesAccess : SideEffect
    data class RequestSafGrant(val forPath: File) : SideEffect
    data object FinishActivity : SideEffect
}
