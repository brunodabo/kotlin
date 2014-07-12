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

package org.jetbrains.jet.lang.resolve.calls.callUtil

import org.jetbrains.jet.lang.descriptors.CallableDescriptor
import org.jetbrains.jet.lang.resolve.calls.model.ResolvedCall
import org.jetbrains.jet.lang.resolve.calls.model.ArgumentUnmapped
import org.jetbrains.jet.lang.descriptors.ValueParameterDescriptor
import org.jetbrains.jet.lang.resolve.calls.model.ArgumentMatch
import org.jetbrains.jet.lang.resolve.calls.model.ArgumentMatchStatus
import org.jetbrains.jet.lang.psi.Call
import org.jetbrains.jet.lang.psi.ValueArgument
import org.jetbrains.jet.lang.resolve.calls.util.CallMaker
import org.jetbrains.jet.lang.resolve.BindingContext
import org.jetbrains.jet.lang.resolve.calls.ArgumentTypeResolver
import org.jetbrains.jet.lang.psi.JetElement
import org.jetbrains.jet.lang.psi.JetExpression
import org.jetbrains.jet.lang.psi.JetPsiUtil
import org.jetbrains.jet.lang.psi.JetSimpleNameExpression
import org.jetbrains.jet.lang.psi.JetCallElement
import org.jetbrains.jet.lang.psi.JetQualifiedExpression
import org.jetbrains.jet.lang.psi.JetOperationExpression
import org.jetbrains.jet.lang.psi.JetInstanceExpressionWithLabel
import org.jetbrains.jet.lang.psi.JetUserType
import org.jetbrains.jet.lang.psi.JetConstructorCalleeExpression
import org.jetbrains.jet.lang.resolve.BindingContext.CALL
import org.jetbrains.jet.lang.psi.JetBinaryExpression
import org.jetbrains.jet.lang.psi.JetUnaryExpression
import org.jetbrains.jet.lang.psi.JetArrayAccessExpression
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.jet.lang.resolve.BindingContext.RESOLVED_CALL
import org.jetbrains.kotlin.util.sure
import org.jetbrains.jet.lang.psi.psiUtil.getTextWithLocation
import org.jetbrains.jet.lang.descriptors.FunctionDescriptor
import org.jetbrains.jet.lang.psi.JetCallExpression
import org.jetbrains.jet.lang.psi.JetFunctionLiteralArgument

// resolved call

public fun <D : CallableDescriptor> ResolvedCall<D>.noErrorsInValueArguments(): Boolean {
    return getCall().getValueArguments().all { argument -> !getArgumentMapping(argument!!).isError() }
}

public fun <D : CallableDescriptor> ResolvedCall<D>.hasUnmappedArguments(): Boolean {
    return getCall().getValueArguments().any { argument -> getArgumentMapping(argument!!) == ArgumentUnmapped }
}

public fun <D : CallableDescriptor> ResolvedCall<D>.hasUnmappedParameters(): Boolean {
    val parameterToArgumentMap = getValueArguments()
    return !parameterToArgumentMap.keySet().containsAll(getResultingDescriptor().getValueParameters())
}

public fun <D : CallableDescriptor> ResolvedCall<D>.hasTypeMismatchErrorOnParameter(parameter: ValueParameterDescriptor): Boolean {
    val resolvedValueArgument = getValueArguments()[parameter]
    if (resolvedValueArgument == null) return true

    return resolvedValueArgument.getArguments().any { argument ->
        val argumentMapping = getArgumentMapping(argument)
        argumentMapping is ArgumentMatch && argumentMapping.status == ArgumentMatchStatus.TYPE_MISMATCH
    }
}

// call

public fun Call.hasUnresolvedArguments(context: BindingContext): Boolean {
    val arguments = getValueArguments().map { it?.getArgumentExpression() }
    return arguments.any {
        argument ->
        val expressionType = context[BindingContext.EXPRESSION_TYPE, argument]
        argument != null && !ArgumentTypeResolver.isFunctionLiteralArgument(argument)
                && (expressionType == null || expressionType.isError())
    }
}

[suppress("UNCHECKED_CAST")]
public fun Call.getValueArgumentsInParentheses(): List<ValueArgument> =
        getValueArguments().filter { it !is JetFunctionLiteralArgument } as List<ValueArgument>

[suppress("UNCHECKED_CAST")]
public fun JetCallExpression.getValueArgumentsInParentheses(): List<ValueArgument> =
        getValueArguments().filter { it !is JetFunctionLiteralArgument } as List<ValueArgument>

public fun Call.getFunctionLiteralArgument(): JetFunctionLiteralArgument? = getFunctionLiteralArguments().head

public fun JetCallElement.getFunctionLiteralArgument(): JetFunctionLiteralArgument? = getFunctionLiteralArguments().head

// Get call / resolved call from binding context

public fun JetElement.getCalleeExpressionIfAny(): JetExpression? {
    val element = if (this is JetExpression) JetPsiUtil.safeDeparenthesize(this, false) else this
    return when (element) {
        is JetSimpleNameExpression -> element
        is JetCallElement -> element.getCalleeExpression()
        is JetQualifiedExpression -> element.getSelectorExpression()?.getCalleeExpressionIfAny()
        is JetOperationExpression -> element.getOperationReference()
        else -> null
    }
}

/**
 *  For expressions like <code>a(), a[i], a.b.c(), +a, a + b, (a()), a(): Int, @label a()</code>
 *  returns a corresponding call.
 *
 *  Note: special construction like <code>a!!, a ?: b, if (c) a else b</code> are resolved as calls,
 *  so there is a corresponding call for them.
 */
public fun JetElement.getCall(context: BindingContext): Call? {
    val element = if (this is JetExpression) JetPsiUtil.deparenthesize(this) else this
    if (element == null) return null

    val parent = element.getParent()
    val reference = when {
        parent is JetInstanceExpressionWithLabel -> parent : JetInstanceExpressionWithLabel
        parent is JetUserType -> parent.getParent()?.getParent() as? JetConstructorCalleeExpression
        else -> element.getCalleeExpressionIfAny()
    }
    if (reference != null) {
        return context[CALL, reference]
    }
    return context[CALL, element]
}

public fun JetElement.getParentCall(context: BindingContext, strict: Boolean = true): Call? {
    val callExpressionTypes = array<Class<out JetElement>?>(
            javaClass<JetSimpleNameExpression>(), javaClass<JetCallElement>(), javaClass<JetBinaryExpression>(),
            javaClass<JetUnaryExpression>(), javaClass<JetArrayAccessExpression>())

    val parent = if (strict) {
        PsiTreeUtil.getParentOfType(this, *callExpressionTypes)
    } else {
        PsiTreeUtil.getNonStrictParentOfType(this, *callExpressionTypes)
    }
    return parent?.getCall(context)
}

public fun Call?.getResolvedCall(context: BindingContext): ResolvedCall<out CallableDescriptor>? {
    return context[RESOLVED_CALL, this]
}

public fun JetElement?.getResolvedCall(context: BindingContext): ResolvedCall<out CallableDescriptor>? {
    return this?.getCall(context)?.getResolvedCall(context)
}

public fun JetElement?.getParentResolvedCall(context: BindingContext, strict: Boolean = true): ResolvedCall<out CallableDescriptor>? {
    return this?.getParentCall(context, strict)?.getResolvedCall(context)
}

public fun JetElement.getCallWithAssert(context: BindingContext): Call {
    return getCall(context).sure("No call for ${this.getTextWithLocation()}")
}

public fun JetElement.getResolvedCallWithAssert(context: BindingContext): ResolvedCall<out CallableDescriptor> {
    return getResolvedCall(context).sure("No resolved call for ${this.getTextWithLocation()}")
}

public fun Call.getResolvedCallWithAssert(context: BindingContext): ResolvedCall<out CallableDescriptor> {
    return getResolvedCall(context).sure("No resolved call for ${this.getCallElement().getTextWithLocation()}")
}

public fun JetExpression.getFunctionResolvedCallWithAssert(context: BindingContext): ResolvedCall<out FunctionDescriptor> {
    val resolvedCall = getResolvedCallWithAssert(context)
    assert(resolvedCall.getResultingDescriptor() is FunctionDescriptor) {
        "ResolvedCall for this expression must be ResolvedCall<? extends FunctionDescriptor>: ${this.getTextWithLocation()}"
    }
    [suppress("UNCHECKED_CAST")]
    return resolvedCall as ResolvedCall<out FunctionDescriptor>
}