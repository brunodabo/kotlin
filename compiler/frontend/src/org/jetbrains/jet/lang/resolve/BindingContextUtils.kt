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

package org.jetbrains.jet.lang.resolve.bindingContextUtil

import org.jetbrains.jet.lang.resolve.BindingContext
import org.jetbrains.jet.lang.psi.JetReturnExpression
import org.jetbrains.jet.lang.descriptors.FunctionDescriptor
import org.jetbrains.jet.lang.resolve.BindingContext.LABEL_TARGET
import org.jetbrains.jet.lang.resolve.BindingContext.FUNCTION
import org.jetbrains.jet.lang.resolve.BindingContext.DECLARATION_TO_DESCRIPTOR
import org.jetbrains.jet.lang.psi.psiUtil.getParentByType
import org.jetbrains.jet.lang.psi.JetDeclarationWithBody
import org.jetbrains.jet.lang.resolve.DescriptorUtils
import org.jetbrains.jet.lang.descriptors.impl.AnonymousFunctionDescriptor
import org.jetbrains.jet.lang.descriptors.ClassDescriptor
import org.jetbrains.jet.lang.psi.JetSimpleNameExpression
import org.jetbrains.jet.lang.psi.JetPsiUtil
import org.jetbrains.jet.lang.resolve.calls.callUtil.getResolvedCall
import org.jetbrains.jet.lang.psi.JetExpression
import org.jetbrains.jet.lang.psi.JetExpressionWithLabel
import org.jetbrains.jet.lang.descriptors.ConstructorDescriptor
import org.jetbrains.jet.lang.psi.psiUtil.isPartOfImportDirective
import org.jetbrains.jet.lang.descriptors.ClassKind
import org.jetbrains.jet.lang.psi.JetUserType


public fun JetReturnExpression.getTargetFunctionDescriptor(context: BindingContext): FunctionDescriptor? {
    val targetLabel = getTargetLabel()
    if (targetLabel != null) return context[LABEL_TARGET, targetLabel]?.let { context[FUNCTION, it] }

    val declarationDescriptor = context[DECLARATION_TO_DESCRIPTOR, getParentByType(javaClass<JetDeclarationWithBody>())]
    val containingFunctionDescriptor = DescriptorUtils.getParentOfType(declarationDescriptor, javaClass<FunctionDescriptor>(), false)
    if (containingFunctionDescriptor == null) return null

    return stream(containingFunctionDescriptor) { DescriptorUtils.getParentOfType(it, javaClass<FunctionDescriptor>()) }
            .dropWhile { it is AnonymousFunctionDescriptor }
            .firstOrNull()
}

public fun JetExpression.refersToClassObject(context: BindingContext): Boolean {
    if (this !is JetSimpleNameExpression || this.isPartOfImportDirective()) return false

    val parent = getParent()
    if (parent is JetExpressionWithLabel || parent is JetUserType) return false

    val descriptor = context[BindingContext.REFERENCE_TARGET, this]
    if (descriptor !is ClassDescriptor
            || descriptor.getKind() != ClassKind.CLASS
            || descriptor.getClassObjectDescriptor() == null) return false

    if (!JetPsiUtil.isLHSOfDot(this)) return true

    val resolvedCall = (parent as? JetExpression).getResolvedCall(context)
    val candidateDescriptor = resolvedCall?.getCandidateDescriptor()
    val containingDeclaration = if (candidateDescriptor is ConstructorDescriptor) {
        candidateDescriptor.getContainingDeclaration().getContainingDeclaration()
    } else {
        candidateDescriptor?.getContainingDeclaration()
    }
    return descriptor.getClassObjectDescriptor() == containingDeclaration
}
