/*
 * Copyright 2010-2013 JetBrains s.r.o.
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

package org.jetbrains.jet.testing;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.*;
import com.intellij.openapi.roots.impl.libraries.LibraryEx;
import com.intellij.openapi.roots.impl.libraries.ProjectLibraryTable;
import com.intellij.openapi.roots.libraries.Library;
import com.intellij.openapi.roots.libraries.LibraryTable;
import com.intellij.openapi.roots.ui.configuration.libraryEditor.NewLibraryEditor;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.VfsUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.jet.codegen.forTestCompile.ForTestCompileRuntime;

/**
 * Helper for configuring kotlin runtime in tested project.
 */
public class ConfigLibraryUtil {
    private static final String DEFAULT_JAVA_RUNTIME_LIB_NAME = "JAVA_RUNTIME_LIB_NAME";

    private ConfigLibraryUtil() {
    }

    private static NewLibraryEditor getKotlinRuntimeLibEditor(String libName) {
        NewLibraryEditor editor = new NewLibraryEditor();
        editor.setName(libName);
        editor.addRoot(VfsUtil.getUrlForLibraryRoot(ForTestCompileRuntime.runtimeJarForTests()), OrderRootType.CLASSES);

        return editor;
    }

    public static void configureKotlinRuntime(Module module, Sdk sdk) {
        configureLibrary(module, sdk, getKotlinRuntimeLibEditor(DEFAULT_JAVA_RUNTIME_LIB_NAME));
    }

    public static void unConfigureKotlinRuntime(Module module, Sdk sdk) {
        unConfigureLibrary(module, sdk, DEFAULT_JAVA_RUNTIME_LIB_NAME);
    }

    public static void configureLibrary(Module module, Sdk sdk, NewLibraryEditor libraryEditor) {
        configureSdk(module, sdk);
        addLibrary(libraryEditor, module);
    }

    public static void unConfigureLibrary(final Module module, final Sdk sdk, String libraryName) {
        configureSdk(module, sdk);
        removeLibrary(module, libraryName);
    }

    public static void configureSdk(
            @NotNull final Module module,
            @NotNull final Sdk sdk) {
        ApplicationManager.getApplication().runWriteAction(new Runnable() {
            @Override
            public void run() {
                final ModuleRootManager rootManager = ModuleRootManager.getInstance(module);
                final ModifiableRootModel rootModel = rootManager.getModifiableModel();

                rootModel.setSdk(sdk);
                rootModel.commit();
            }
        });
    }

    public static Library addLibrary(final NewLibraryEditor editor, final Module module) {
        return ApplicationManager.getApplication().runWriteAction(new Computable<Library>() {
            @Override
            public Library compute() {
                final ModuleRootManager rootManager = ModuleRootManager.getInstance(module);
                ModifiableRootModel model = rootManager.getModifiableModel();

                Library library = addLibrary(editor, model);

                model.commit();

                return library;
            }
        });
    }

    public static Library addLibrary(NewLibraryEditor editor, ModifiableRootModel model) {
        //TODO: merge with org.jetbrains.jet.plugin.JdkAndMockLibraryProjectDescriptor#configureModule
        LibraryTable.ModifiableModel projectTableModel = ProjectLibraryTable.getInstance(model.getProject()).getModifiableModel();
        Library library = projectTableModel.createLibrary(editor.getName());

        Library.ModifiableModel libModel = library.getModifiableModel();
        editor.applyTo((LibraryEx.ModifiableModelEx) libModel);

        libModel.commit();
        projectTableModel.commit();
        model.addLibraryEntry(library);

        return library;
    }


    public static void removeLibrary(@NotNull final Module module, @NotNull final String libraryName) {
        ApplicationManager.getApplication().runWriteAction(new Runnable() {
            @Override
            public void run() {
                final ModuleRootManager rootManager = ModuleRootManager.getInstance(module);
                final ModifiableRootModel model = rootManager.getModifiableModel();

                for (OrderEntry orderEntry : model.getOrderEntries()) {
                    if (orderEntry instanceof LibraryOrderEntry) {
                        LibraryOrderEntry libraryOrderEntry = (LibraryOrderEntry) orderEntry;

                        Library library = libraryOrderEntry.getLibrary();
                        if (library != null) {
                            String name = library.getName();
                            if (name != null && name.equals(libraryName)) {

                                // Dispose attached roots
                                Library.ModifiableModel modifiableModel = library.getModifiableModel();
                                for (String rootUrl : library.getRootProvider().getUrls(OrderRootType.CLASSES)) {
                                    modifiableModel.removeRoot(rootUrl, OrderRootType.CLASSES);
                                }
                                for (String rootUrl : library.getRootProvider().getUrls(OrderRootType.SOURCES)) {
                                    modifiableModel.removeRoot(rootUrl, OrderRootType.SOURCES);
                                }
                                modifiableModel.commit();

                                model.getModuleLibraryTable().removeLibrary(library);

                                break;
                            }
                        }
                    }
                }

                model.commit();
            }
        });
    }
}
