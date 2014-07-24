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

package org.jetbrains.jet.lang.descriptors.impl

import org.jetbrains.jet.lang.resolve.name.Name
import org.jetbrains.jet.lang.PlatformToKotlinClassMap
import org.jetbrains.jet.lang.descriptors.ModuleDescriptorBase
import org.jetbrains.jet.lang.descriptors.PackageFragmentProvider
import org.jetbrains.jet.lang.descriptors.ModuleFactory
import java.util.ArrayList
import org.jetbrains.jet.lang.resolve.ImportPath
import kotlin.properties.Delegates

public class ModuleDescriptorImpl(name: Name, defaultImports: List<ImportPath>, platformToKotlinClassMap: PlatformToKotlinClassMap) :
        ModuleDescriptorBase(name, defaultImports, platformToKotlinClassMap) {
    {
        if (!name.isSpecial()) {
            throw IllegalArgumentException("module name must be special: $name")
        }
    }

    private val dependencies: MutableList<ModuleDescriptorImpl> = ArrayList()

    public var ownPackageFragmentProvider: PackageFragmentProvider by Delegates.notNull()
    public fun addDependencyOnModule(dependency: ModuleDescriptorImpl) {
        dependencies.add(dependency)
    }

    override fun setPackageFragmentProviderForSources(providerForSources: PackageFragmentProvider) {
        ownPackageFragmentProvider = providerForSources
    }

    //TODO: store it and prohibit modification once it is computed
    override fun getPackageFragmentProvider(): PackageFragmentProvider {
        return CompositePackageFragmentProvider(dependencies.map { it.ownPackageFragmentProvider })
    }

    class object {
        public object FACTORY : ModuleFactory<ModuleDescriptorImpl> {
            override fun createModule(name: Name, defaultImports: List<ImportPath>, classMap: PlatformToKotlinClassMap)
                    = ModuleDescriptorImpl(name, defaultImports, classMap)
        }
    }
}