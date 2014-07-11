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

package org.jetbrains.jet.codegen;

import com.intellij.openapi.vfs.StandardFileSystems;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.containers.Stack;
import kotlin.Function1;
import kotlin.KotlinPackage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.jet.codegen.binding.CalculatedClosure;
import org.jetbrains.jet.codegen.context.CodegenContext;
import org.jetbrains.jet.codegen.context.MethodContext;
import org.jetbrains.jet.codegen.context.PackageContext;
import org.jetbrains.jet.codegen.state.JetTypeMapper;
import org.jetbrains.jet.descriptors.serialization.descriptors.DeserializedCallableMemberDescriptor;
import org.jetbrains.jet.lang.descriptors.*;
import org.jetbrains.jet.lang.descriptors.annotations.Annotations;
import org.jetbrains.jet.lang.descriptors.impl.SimpleFunctionDescriptorImpl;
import org.jetbrains.jet.lang.descriptors.impl.TypeParameterDescriptorImpl;
import org.jetbrains.jet.lang.resolve.DescriptorUtils;
import org.jetbrains.jet.lang.resolve.calls.CallResolverUtil;
import org.jetbrains.jet.lang.resolve.java.descriptor.JavaPackageFragmentDescriptor;
import org.jetbrains.jet.lang.resolve.java.lazy.descriptors.LazyPackageFragmentScopeForJavaPackage;
import org.jetbrains.jet.lang.resolve.kotlin.KotlinJvmBinaryClass;
import org.jetbrains.jet.lang.resolve.kotlin.VirtualFileKotlinClass;
import org.jetbrains.jet.lang.resolve.kotlin.incremental.IncrementalPackageFragmentProvider;
import org.jetbrains.jet.lang.resolve.name.Name;
import org.jetbrains.jet.lang.resolve.scopes.JetScope;
import org.jetbrains.jet.lang.types.JetType;
import org.jetbrains.jet.lang.types.TypeUtils;
import org.jetbrains.jet.lang.types.checker.JetTypeChecker;
import org.jetbrains.jet.lang.types.lang.KotlinBuiltIns;

import java.io.File;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static org.jetbrains.jet.lang.descriptors.Modality.ABSTRACT;

public class JvmCodegenUtil {

    private JvmCodegenUtil() {
    }

    public static boolean isInterface(DeclarationDescriptor descriptor) {
        if (descriptor instanceof ClassDescriptor) {
            ClassKind kind = ((ClassDescriptor) descriptor).getKind();
            return kind == ClassKind.TRAIT || kind == ClassKind.ANNOTATION_CLASS;
        }
        return false;
    }

    public static boolean isInterface(JetType type) {
        return isInterface(type.getConstructor().getDeclarationDescriptor());
    }

    public static SimpleFunctionDescriptor createInvoke(FunctionDescriptor fd) {
        int arity = fd.getValueParameters().size();
        SimpleFunctionDescriptorImpl invokeDescriptor = SimpleFunctionDescriptorImpl.create(
                fd.getExpectedThisObject() != null
                ? KotlinBuiltIns.getInstance().getExtensionFunction(arity) : KotlinBuiltIns.getInstance().getFunction(arity),
                Annotations.EMPTY,
                Name.identifier("invoke"),
                CallableMemberDescriptor.Kind.DECLARATION,
                SourceElement.NO_SOURCE
        );

        invokeDescriptor.initialize(DescriptorUtils.getReceiverParameterType(fd.getReceiverParameter()),
                                    fd.getExpectedThisObject(),
                                    Collections.<TypeParameterDescriptorImpl>emptyList(),
                                    fd.getValueParameters(),
                                    fd.getReturnType(),
                                    Modality.FINAL,
                                    Visibilities.PUBLIC
        );
        return invokeDescriptor;
    }

    public static boolean isConst(@NotNull CalculatedClosure closure) {
        return closure.getCaptureThis() == null && closure.getCaptureReceiverType() == null && closure.getCaptureVariables().isEmpty();
    }

    public static <T> T peekFromStack(Stack<T> stack) {
        return stack.empty() ? null : stack.peek();
    }

    public static JetType getSuperClass(ClassDescriptor classDescriptor) {
        List<ClassDescriptor> superclassDescriptors = DescriptorUtils.getSuperclassDescriptors(classDescriptor);
        for (ClassDescriptor descriptor : superclassDescriptors) {
            if (descriptor.getKind() != ClassKind.TRAIT) {
                return descriptor.getDefaultType();
            }
        }
        return KotlinBuiltIns.getInstance().getAnyType();
    }

    @Nullable
    public static FunctionDescriptor getDeclaredFunctionByRawSignature(
            @NotNull ClassDescriptor owner,
            @NotNull Name name,
            @NotNull ClassifierDescriptor returnedClassifier,
            @NotNull ClassifierDescriptor... valueParameterClassifiers
    ) {
        Collection<FunctionDescriptor> functions = owner.getDefaultType().getMemberScope().getFunctions(name);
        for (FunctionDescriptor function : functions) {
            if (!CallResolverUtil.isOrOverridesSynthesized(function)
                && function.getTypeParameters().isEmpty()
                && valueParameterClassesMatch(function.getValueParameters(), Arrays.asList(valueParameterClassifiers))
                && rawTypeMatches(function.getReturnType(), returnedClassifier)) {
                return function;
            }
        }
        return null;
    }

    private static boolean valueParameterClassesMatch(
            @NotNull List<ValueParameterDescriptor> parameters,
            @NotNull List<ClassifierDescriptor> classifiers) {
        if (parameters.size() != classifiers.size()) return false;
        for (int i = 0; i < parameters.size(); i++) {
            ValueParameterDescriptor parameterDescriptor = parameters.get(i);
            ClassifierDescriptor classDescriptor = classifiers.get(i);
            if (!rawTypeMatches(parameterDescriptor.getType(), classDescriptor)) {
                return false;
            }
        }
        return true;
    }

    private static boolean rawTypeMatches(JetType type, ClassifierDescriptor classifier) {
        return type.getConstructor().getDeclarationDescriptor().getOriginal() == classifier.getOriginal();
    }

    public static boolean isCallInsideSameClassAsDeclared(CallableMemberDescriptor declarationDescriptor, CodegenContext context) {
        boolean isFakeOverride = declarationDescriptor.getKind() == CallableMemberDescriptor.Kind.FAKE_OVERRIDE;
        boolean isDelegate = declarationDescriptor.getKind() == CallableMemberDescriptor.Kind.DELEGATION;

        DeclarationDescriptor containingDeclaration = declarationDescriptor.getContainingDeclaration();
        containingDeclaration = containingDeclaration.getOriginal();

        return !isFakeOverride && !isDelegate &&
               (((context.hasThisDescriptor() && containingDeclaration == context.getThisDescriptor()) ||
                 (context.getParentContext() instanceof PackageContext
                  && isSamePackageInSameModule(context.getParentContext().getContextDescriptor(), containingDeclaration)))
                && context.getContextKind() != OwnerKind.TRAIT_IMPL);
    }

    private static boolean isSamePackageInSameModule(
            @NotNull DeclarationDescriptor callerOwner,
            @NotNull DeclarationDescriptor calleeOwner
    ) {
        if (callerOwner instanceof PackageFragmentDescriptor && calleeOwner instanceof PackageFragmentDescriptor) {
            PackageFragmentDescriptor callerFragment = (PackageFragmentDescriptor) callerOwner;
            PackageFragmentDescriptor calleeFragment = (PackageFragmentDescriptor) calleeOwner;

            // backing field should be used directly within same module of same package
            if (callerFragment == calleeFragment) {
                return true;
            }
            return callerFragment.getFqName().equals(calleeFragment.getFqName())
                   && calleeFragment instanceof IncrementalPackageFragmentProvider.IncrementalPackageFragment;
        }
        return false;
    }

    public static boolean isCallInsideSameModuleAsDeclared(
            @NotNull CallableMemberDescriptor declarationDescriptor,
            @NotNull CodegenContext context,
            @Nullable File outDirectory
    ) {
        if (context == CodegenContext.STATIC) {
            return true;
        }
        DeclarationDescriptor contextDescriptor = context.getContextDescriptor();

        CallableMemberDescriptor directMember = getDirectMember(declarationDescriptor);
        if (directMember instanceof DeserializedCallableMemberDescriptor) {
            return isContainedByCompiledPartOfOurModule(((DeserializedCallableMemberDescriptor) directMember), outDirectory);
        }
        else {
            return DescriptorUtils.areInSameModule(directMember, contextDescriptor);
        }
    }

    private static boolean isContainedByCompiledPartOfOurModule(
            @NotNull DeserializedCallableMemberDescriptor descriptor,
            @Nullable File outDirectory
    ) {
        if (descriptor.getContainingDeclaration() instanceof IncrementalPackageFragmentProvider.IncrementalPackageFragment) {
            return true;
        }

        if (outDirectory == null) {
            return false;
        }

        if (!(descriptor.getContainingDeclaration() instanceof JavaPackageFragmentDescriptor)) {
            return false;
        }
        JavaPackageFragmentDescriptor packageFragment = (JavaPackageFragmentDescriptor) descriptor.getContainingDeclaration();
        JetScope packageScope = packageFragment.getMemberScope();
        if (!(packageScope instanceof LazyPackageFragmentScopeForJavaPackage)) {
            return false;
        }
        KotlinJvmBinaryClass binaryClass = ((LazyPackageFragmentScopeForJavaPackage) packageScope).getKotlinBinaryClass();

        if (binaryClass instanceof VirtualFileKotlinClass) {
            VirtualFile file = ((VirtualFileKotlinClass) binaryClass).getFile();
            if (file.getFileSystem().getProtocol() == StandardFileSystems.FILE_PROTOCOL) {
                File ioFile = VfsUtilCore.virtualToIoFile(file);
                return ioFile.getAbsolutePath().startsWith(outDirectory.getAbsolutePath() + File.separator);
            }
        }
        return false;
    }

    public static boolean hasAbstractMembers(@NotNull ClassDescriptor classDescriptor) {
        return KotlinPackage.any(classDescriptor.getDefaultType().getMemberScope().getAllDescriptors(),
                                 new Function1<DeclarationDescriptor, Boolean>() {
                                     @Override
                                     public Boolean invoke(DeclarationDescriptor descriptor) {
                                         return descriptor instanceof CallableMemberDescriptor &&
                                                ((CallableMemberDescriptor) descriptor).getModality() == ABSTRACT;
                                     }
                                 }
        );
    }

    public static boolean couldUseDirectAccessToProperty(
            @NotNull PropertyDescriptor propertyDescriptor,
            boolean forGetter,
            boolean isInsideClass,
            boolean isDelegated,
            MethodContext context
    ) {
        if (context.isInlineFunction()) {
            return false;
        }
        PropertyAccessorDescriptor accessorDescriptor = forGetter ? propertyDescriptor.getGetter() : propertyDescriptor.getSetter();
        boolean isExtensionProperty = propertyDescriptor.getReceiverParameter() != null;
        boolean specialTypeProperty = isDelegated ||
                                      isExtensionProperty ||
                                      DescriptorUtils.isClassObject(propertyDescriptor.getContainingDeclaration()) ||
                                      JetTypeMapper.isAccessor(propertyDescriptor);
        return isInsideClass &&
               !specialTypeProperty &&
               (accessorDescriptor == null ||
                accessorDescriptor.isDefault() &&
                (!isExternallyAccessible(propertyDescriptor) || accessorDescriptor.getModality() == Modality.FINAL));
    }

    private static boolean isExternallyAccessible(@NotNull PropertyDescriptor propertyDescriptor) {
        return propertyDescriptor.getVisibility() != Visibilities.PRIVATE ||
               DescriptorUtils.isClassObject(propertyDescriptor.getContainingDeclaration()) ||
               DescriptorUtils.isTopLevelDeclaration(propertyDescriptor);
    }

    @NotNull
    public static ImplementationBodyCodegen getParentBodyCodegen(@Nullable MemberCodegen<?> classBodyCodegen) {
        assert classBodyCodegen != null && classBodyCodegen.getParentCodegen() instanceof ImplementationBodyCodegen
                : "Class object should have appropriate parent BodyCodegen";

        return (ImplementationBodyCodegen) classBodyCodegen.getParentCodegen();
    }

    @Nullable
    public static ClassDescriptor getExpectedThisObjectForConstructorCall(
            @NotNull ConstructorDescriptor descriptor,
            @Nullable CalculatedClosure closure
    ) {
        //for compilation against sources
        if (closure != null) {
            return closure.getCaptureThis();
        }

        //for compilation against binaries
        //TODO: It's best to use this code also for compilation against sources
        // but sometimes structures that have expectedThisObject (bug?) mapped to static classes
        ReceiverParameterDescriptor expectedThisObject = descriptor.getExpectedThisObject();
        if (expectedThisObject != null) {
            ClassDescriptor expectedThisClass = (ClassDescriptor) expectedThisObject.getContainingDeclaration();
            if (!expectedThisClass.getKind().isSingleton()) {
                return expectedThisClass;
            }
        }

        return null;
    }

    public static boolean isEnumValueOfMethod(@NotNull FunctionDescriptor functionDescriptor) {
        List<ValueParameterDescriptor> methodTypeParameters = functionDescriptor.getValueParameters();
        JetType nullableString = TypeUtils.makeNullable(KotlinBuiltIns.getInstance().getStringType());
        return "valueOf".equals(functionDescriptor.getName().asString())
               && methodTypeParameters.size() == 1
               && JetTypeChecker.DEFAULT.isSubtypeOf(methodTypeParameters.get(0).getType(), nullableString);
    }

    public static boolean isEnumValuesMethod(@NotNull FunctionDescriptor functionDescriptor) {
        List<ValueParameterDescriptor> methodTypeParameters = functionDescriptor.getValueParameters();
        return "values".equals(functionDescriptor.getName().asString())
               && methodTypeParameters.isEmpty();
    }

    @NotNull
    public static CallableMemberDescriptor getDirectMember(@NotNull CallableMemberDescriptor descriptor) {
        return descriptor instanceof PropertyAccessorDescriptor
               ? ((PropertyAccessorDescriptor) descriptor).getCorrespondingProperty()
               : descriptor;
    }
}
