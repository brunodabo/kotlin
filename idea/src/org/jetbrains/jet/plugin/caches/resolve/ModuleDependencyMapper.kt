/*
 * Copyright 2010-2014 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.jet.plugin.caches.resolve

import com.intellij.openapi.project.Project
import com.intellij.openapi.module.Module
import org.jetbrains.jet.lang.resolve.name.Name
import org.jetbrains.jet.lang.psi.JetFile
import org.jetbrains.jet.context.GlobalContext
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ModuleSourceOrderEntry
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.openapi.module.ModuleManager
import org.jetbrains.jet.utils.keysToMap
import org.jetbrains.jet.lang.descriptors.ModuleDescriptor
import com.intellij.openapi.roots.ModuleOrderEntry
import com.intellij.openapi.roots.LibraryOrderEntry
import org.jetbrains.jet.plugin.project.ResolveSessionForBodies
import org.jetbrains.jet.lang.resolve.java.new.JvmAnalyzerFacade
import org.jetbrains.jet.lang.resolve.java.new.JvmPlatformParameters
import org.jetbrains.jet.lang.resolve.java.new.JvmAnalyzer
import org.jetbrains.jet.lang.resolve.java.structure.impl.JavaClassImpl
import com.intellij.openapi.roots.libraries.LibraryTablesRegistrar
import com.intellij.openapi.roots.libraries.Library
import org.jetbrains.jet.analyzer.new.ModuleInfo
import com.intellij.openapi.roots.JdkOrderEntry
import com.intellij.openapi.module.impl.scopes.JdkScope
import com.intellij.openapi.module.impl.scopes.LibraryScopeBase
import com.intellij.openapi.roots.OrderRootType
import org.jetbrains.kotlin.util.sure
import com.intellij.psi.PsiElement
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.LibraryOrSdkOrderEntry
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.jet.lang.resolve.java.jetAsJava.KotlinLightElement
import org.jetbrains.jet.asJava.unwrapped
import com.intellij.openapi.module.impl.scopes.LibraryScope

private abstract class PluginModuleInfo : ModuleInfo<PluginModuleInfo> {
    //TODO: add project to this fun and remove from classes params?
    abstract fun filesScope(): GlobalSearchScope
    // scope to accept synthetic files to this module
    open fun syntheticFilesScope() = filesScope()
}

private data class ModuleSourcesInfo(val project: Project, val module: Module) : PluginModuleInfo() {
    override val name = Name.special("<sources for module ${module.getName()}>")

    override fun filesScope() = GlobalSearchScope.moduleScope(module)

    override fun dependencies(): List<ModuleInfo<PluginModuleInfo>> {
        return ModuleRootManager.getInstance(module).getOrderEntries().map {
            orderEntry ->
            when (orderEntry) {
                is ModuleSourceOrderEntry -> {
                    ModuleSourcesInfo(project, orderEntry.getRootModel().getModule())
                }
                is ModuleOrderEntry -> {
                    val dependencyModule = orderEntry.getModule()
                    //TODO: null?
                    ModuleSourcesInfo(project, dependencyModule!!)
                }
                is LibraryOrderEntry -> {
                    //TODO: null?
                    val library = orderEntry.getLibrary()!!
                    val isKotlinRuntime = library.getName() == "KotlinJavaRuntime"
                    if (isKotlinRuntime) {
                    }
                    LibraryInfo(project, library)
                }
                is JdkOrderEntry -> {
                    //TODO: null?
                    SdkInfo(project, orderEntry)
                }
                else -> {
                    null
                }
            }
        }.filterNotNull()
    }
}

private data class LibraryInfo(val project: Project, val library: Library) : PluginModuleInfo() {
    override val name: Name = Name.special("<library ${library.getName()}>")

    override fun filesScope() = LibraryWithoutSourcesScope(project, library)

    override fun syntheticFilesScope() = LibraryScope(project, library)

    override fun dependencies(): List<ModuleInfo<PluginModuleInfo>> = listOf()
}

private data class SdkInfo(val project: Project, /*TODO: param name*/val sdk: JdkOrderEntry) : PluginModuleInfo() {
    //TODO: null?
    override val name: Name = Name.special("<library ${sdk.getJdk()!!.getName()}>")

    override fun filesScope() = JdkScope(project, sdk)

    override fun dependencies(): List<ModuleInfo<PluginModuleInfo>> = listOf()
}


fun createMappingForProject(
        globalContext: GlobalContext,
        project: Project,
        analyzerFacade: JvmAnalyzerFacade,
        syntheticFilesProvider: (GlobalSearchScope) -> Collection<JetFile>
): ModuleSetup {

    val ideaModules = ModuleManager.getInstance(project).getSortedModules().toList()
    val modulesSources = ideaModules.keysToMap { ModuleSourcesInfo(project, it) }
    val ideaLibraries = LibraryTablesRegistrar.getInstance()!!.getLibraryTable(project).getLibraries().toList()
    val libraries = ideaLibraries.keysToMap { LibraryInfo(project, it) }
    val sdkInfos = ideaModules.keysToMap { (ModuleRootManager.getInstance(it).getOrderEntries().first { it is JdkOrderEntry } as JdkOrderEntry).let { SdkInfo(project, it) } }
    val modules = modulesSources.values() + libraries.values() + sdkInfos.values()
    val jvmPlatformParameters = {(module: PluginModuleInfo) ->
        JvmPlatformParameters(syntheticFilesProvider(module.syntheticFilesScope()), module.filesScope()) {
            javaClass ->
            val psiClass = (javaClass as JavaClassImpl).getPsi()
            psiClass.getModuleInfo().sure("Module not found for ${psiClass.getName()} in ${psiClass.getContainingFile()}")
        }
    }
    val analysisSetup = analyzerFacade.setupAnalysis(globalContext, project, modules, jvmPlatformParameters)

    val moduleToBodiesResolveSession = modules.keysToMap {
        module ->
        val descriptor = analysisSetup.descriptorByModule[module]!!
        val analyzer = analysisSetup.analyzerByModuleDescriptor[descriptor]!!
        ResolveSessionForBodies(project, analyzer.lazyResolveSession)
    }
    return ModuleSetup(analysisSetup.descriptorByModule, analysisSetup.analyzerByModuleDescriptor, moduleToBodiesResolveSession)
}

//TODO: actually nullable
class ModuleSetup(private val descriptorByModule: Map<PluginModuleInfo, ModuleDescriptor>,
                  private val setupByModuleDescriptor: Map<ModuleDescriptor, JvmAnalyzer>,
                  private val bodiesResolveByModule: Map<PluginModuleInfo, ResolveSessionForBodies>
) {
    fun descriptorByModule(module: PluginModuleInfo) = descriptorByModule[module]!!
    fun setupByModule(module: PluginModuleInfo) = setupByModuleDescriptor[descriptorByModule[module]!!]!!
    fun setupByDescriptor(module: ModuleDescriptor) = setupByModuleDescriptor[module]!!
    fun resolveSessionForBodiesByModule(module: PluginModuleInfo) = bodiesResolveByModule[module]!!
    fun resolveSessionForBodiesByModuleDescriptor(module: ModuleDescriptor): ResolveSessionForBodies? {
        val moduleInfo = descriptorByModule.entrySet().firstOrNull() { it.value == module }?.key ?: return null
        return bodiesResolveByModule[moduleInfo]
    }
    val modules: Collection<PluginModuleInfo> = descriptorByModule.keySet()
}

//TODO: duplication with LibraryScope
private data class LibraryWithoutSourcesScope(project: Project, private val library: Library)
: LibraryScopeBase(project, library.getFiles(OrderRootType.CLASSES), array<VirtualFile>()) {
}

private fun PsiElement.getModuleInfo(): PluginModuleInfo? {
    //TODO: clearer code
    if (this is KotlinLightElement<*, *>) return this.unwrapped?.getModuleInfo()

    val project = getProject()
    //TODO: deal with non physical file
    //TODO: can be clearer and more optimal?
    //TODO: utilities for transforming entry to info
    val virtualFile = getContainingFile().sure("${getText()}").getOriginalFile().getVirtualFile().sure("${getContainingFile()}")
    val projectFileIndex = ProjectFileIndex.SERVICE.getInstance(project)
    val orderEntries = projectFileIndex.getOrderEntriesForFile(virtualFile)
    val moduleSourceOrderEntry = orderEntries.filterIsInstance(javaClass<ModuleSourceOrderEntry>()).firstOrNull()
    if (moduleSourceOrderEntry != null) {
        return ModuleSourcesInfo(project, moduleSourceOrderEntry.getRootModel().getModule())
    }
    val libraryOrSdkOrderEntry = orderEntries.filterIsInstance(javaClass<LibraryOrSdkOrderEntry>()).firstOrNull()
    return when (libraryOrSdkOrderEntry) {
        //TODO: deal with null again
        is LibraryOrderEntry -> LibraryInfo(project, libraryOrSdkOrderEntry.getLibrary().sure("bla bla"))
        is JdkOrderEntry -> SdkInfo(project, libraryOrSdkOrderEntry)
        else -> null
    }
}