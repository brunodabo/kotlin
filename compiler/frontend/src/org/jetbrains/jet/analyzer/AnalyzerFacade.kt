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

package org.jetbrains.jet.analyzer.new

import org.jetbrains.jet.lang.resolve.lazy.ResolveSession
import org.jetbrains.jet.lang.resolve.name.Name
import org.jetbrains.jet.lang.descriptors.ModuleDescriptor
import java.util.HashMap
import org.jetbrains.jet.lang.descriptors.ModuleDescriptorBase
import org.jetbrains.jet.lang.descriptors.ModuleDescriptorImplX
import org.jetbrains.jet.lang.resolve.ImportPath
import org.jetbrains.jet.lang.PlatformToKotlinClassMap
import com.intellij.openapi.project.Project
import org.jetbrains.jet.context.GlobalContextImpl
import org.jetbrains.jet.context.GlobalContext
import org.jetbrains.jet.lang.types.lang.KotlinBuiltIns

//TODO: ResolverForModule
public trait ResolverForModule {
    public val lazyResolveSession: ResolveSession
}

//TODO: ResolverForProject
public trait ResolverForProject<M, A : ResolverForModule> {
    public val analyzerByModuleDescriptor: Map<ModuleDescriptor, A>
    public val descriptorByModule: Map<M, ModuleDescriptor>
    public val moduleByDescriptor: Map<ModuleDescriptor, M>
}

public class ResolverForProjectImpl<M, A : ResolverForModule>(
        public override val descriptorByModule: Map<M, ModuleDescriptorImplX>,
        public override val moduleByDescriptor: Map<ModuleDescriptor, M>
) : ResolverForProject<M, A> {
    override val analyzerByModuleDescriptor: MutableMap<ModuleDescriptor, A> = HashMap()
}

public trait PlatformModuleParameters

public trait ModuleInfo<T : ModuleInfo<T>> {
    val name: Name
    fun dependencies(): List<ModuleInfo<T>>
    fun dependencyOnBuiltins(): DependencyOnBuiltins = DependenciesOnBuiltins.LAST

    //TODO: modifyDependencies(OrderedMap [ModuleInfo -> ModuleDescriptor])
    public trait DependencyOnBuiltins

    public enum class DependenciesOnBuiltins: DependencyOnBuiltins {
        NONE
        LAST
    }
}

public trait AnalyzerFacade<A : ResolverForModule, P : PlatformModuleParameters> {
    public fun <M: ModuleInfo<M>> setupResolverForProject(
            globalContext: GlobalContext,
            project: Project,
            modules: Collection<M>,
            platformParameters: (M) -> P
    ): ResolverForProject<M, A> {

        fun createResolverForProject(): ResolverForProjectImpl<M, A> {
            val descriptorByModule = HashMap<M, ModuleDescriptorImplX>()
            val moduleByDescriptor = HashMap<ModuleDescriptor, M>()
            modules.forEach {
                module ->
                val descriptor = ModuleDescriptorImplX(module.name, defaultImports, platformToKotlinClassMap)
                descriptorByModule[module] = descriptor
                moduleByDescriptor[descriptor] = module
            }
            return ResolverForProjectImpl(descriptorByModule, moduleByDescriptor)
        }

        val resolverForProject = createResolverForProject()

        fun setupModuleDependencies() {
            modules.forEach {
                module ->
                val currentModule = resolverForProject.descriptorByModule[module]!!
                module.dependencies().forEach {
                    dependency ->
                    val dependencyDescriptor = resolverForProject.descriptorByModule[dependency]!!
                    currentModule.addDependencyOnModule(dependencyDescriptor)
                }
                //TODO: more
                when (module.dependencyOnBuiltins()) {
                    is ModuleInfo.DependenciesOnBuiltins.LAST -> currentModule.addDependencyOnModule(KotlinBuiltIns.getInstance().getBuiltInsModule() as ModuleDescriptorImplX)
                }
            }
        }

        setupModuleDependencies()

        fun initializeResolverForProject() {
            modules.forEach {
                module ->
                val descriptor = resolverForProject.descriptorByModule[module]!!
                //TODO: communicate information that package fragment provider for sources has to be set
                val analyzer = createResolverForModule(project, globalContext, descriptor, platformParameters(module), resolverForProject)
                resolverForProject.analyzerByModuleDescriptor[descriptor] = analyzer
            }
        }

        initializeResolverForProject()
        return resolverForProject
    }

    protected fun <M> createResolverForModule(project: Project, globalContext: GlobalContext, moduleDescriptor: ModuleDescriptorBase,
                                              platformParameters: P, setup: ResolverForProject<M, A>): A

    public val defaultImports: List<ImportPath>
    public val platformToKotlinClassMap: PlatformToKotlinClassMap
}

