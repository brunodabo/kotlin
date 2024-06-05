/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.analysis.api.components

import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.analysis.api.scopes.KaScope

/**
 * Provides declarations generated by compiler plugins.
 */
@KaExperimentalApi
public interface KaCompilerPluginGeneratedDeclarationsProvider {
    /**
     * Returns a [KaScope] containing all top-level declarations generated
     * by compiler plugins specifically for [this] module.
     *
     * Important: the resulting scope **does not** include the generated declarations for the
     * dependencies of [this] module.
     */
    @KaExperimentalApi
    public val KaModule.topLevelCompilerPluginGeneratedDeclarationsScope: KaScope
}