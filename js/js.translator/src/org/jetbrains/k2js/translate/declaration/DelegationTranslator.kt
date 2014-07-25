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

package org.jetbrains.k2js.translate.declaration


import com.google.dart.compiler.backend.js.ast.*
import com.intellij.util.SmartList
import org.jetbrains.jet.lang.descriptors.*
import org.jetbrains.jet.lang.psi.*
import org.jetbrains.k2js.translate.context.Namer
import org.jetbrains.k2js.translate.context.TranslationContext
import org.jetbrains.k2js.translate.general.AbstractTranslator
import org.jetbrains.k2js.translate.general.Translation
import org.jetbrains.k2js.translate.utils.BindingUtils
import org.jetbrains.k2js.translate.utils.JsAstUtils
import org.jetbrains.k2js.translate.utils.JsDescriptorUtils
import org.jetbrains.k2js.translate.utils.TranslationUtils

import org.jetbrains.jet.lang.resolve.DescriptorUtils
import org.jetbrains.jet.backend.common.CodegenUtil
import java.util.HashMap

public class DelegationTranslator(private val classDeclaration: JetClassOrObject, context: TranslationContext) : AbstractTranslator(context) {

    private val classDescriptor: ClassDescriptor = BindingUtils.getClassDescriptor(context.bindingContext(), classDeclaration);
    private val delegationBySpecifiers = classDeclaration.getDelegationSpecifiers().filterIsInstance(javaClass<JetDelegatorByExpressionSpecifier>());

    private class Field (public val name: String, public val generateField: Boolean) {}
    private val fields = HashMap<JetDelegatorByExpressionSpecifier, Field>();

    {
        for (specifier in delegationBySpecifiers) {
            val expression = specifier.getDelegateExpression() ?: throw IllegalArgumentException("delegate expression should not be null: ${specifier.getText()}")
            val descriptor = getSuperClass(specifier)
            val propertyDescriptor = CodegenUtil.getDelegatePropertyIfAny(expression, classDescriptor, bindingContext())

            if (CodegenUtil.isFinalPropertyWithBackingField(propertyDescriptor, bindingContext()))
                fields.put(specifier, Field(propertyDescriptor!!.getName().asString(), false))
            else {
                val typeFqName = DescriptorUtils.getFqNameSafe(descriptor)
                val delegateName = TranslationUtils.getMangledMemberNameForExplicitDelegation(Namer.getDelegatePrefix(), classDeclaration.getFqName(), typeFqName)
                fields.put(specifier, Field(delegateName, true))
            }
        }
    }

    private fun getSuperClass(specifier: JetDelegationSpecifier): ClassDescriptor {
        val typeRef = specifier.getTypeReference() ?: throw IllegalArgumentException("type reference should not be null: ${specifier.getText()}")
        return BindingUtils.getClassDescriptorForTypeReference(bindingContext(), typeRef)
    }

    public fun addInitCode(statements: MutableList<JsStatement>) {
        for (specifier in delegationBySpecifiers) {
            val field = fields.get(specifier)!!
            if (field.generateField) {
                val expression = specifier.getDelegateExpression()!!
                val delegateInitExpr = Translation.translateAsExpression(expression, context())
                statements.add(JsAstUtils.defineSimpleProperty(field.name, delegateInitExpr))
            }
        }
    }

    private fun generateDelegates(toClass: ClassDescriptor, field: Field, properties: MutableList<JsPropertyInitializer>) {
        for ((descriptor, overriddenDescriptor) in CodegenUtil.getDelegates(classDescriptor, toClass)) {
            when (descriptor) {
                is PropertyDescriptor ->
                    PropertyTranslator.translateAccessorsForDelegate(descriptor, field.name, properties, context())
                is FunctionDescriptor ->
                    generateDelegateCallForFunctionMember(descriptor, overriddenDescriptor as FunctionDescriptor, field.name, properties)
                else ->
                    throw IllegalArgumentException("Expected property or function ${descriptor}")
            }
        }
    }

    public fun generateDelegated(properties: MutableList<JsPropertyInitializer>) {
        for (specifier in delegationBySpecifiers) {
            generateDelegates(getSuperClass(specifier), fields.get(specifier)!!, properties)
        }
    }

    private fun generateDelegateCallForFunctionMember(descriptor: FunctionDescriptor, overriddenDescriptor: FunctionDescriptor, delegateName: String, properties: MutableList<JsPropertyInitializer>)  {
        val delegateMemberFunctionName = context().getNameForDescriptor(descriptor)
        val overriddenMemberFunctionName = context().getNameForDescriptor(overriddenDescriptor)
        val functionObject = context().getFunctionObject(descriptor)
        val args = SmartList<JsExpression>()

        if (JsDescriptorUtils.isExtension(descriptor)) {
            val extensionFunctionReceiverName = functionObject.getScope().declareName(Namer.getReceiverParameterName())
            functionObject.getParameters().add(JsParameter(extensionFunctionReceiverName))
            args.add(JsNameRef(extensionFunctionReceiverName))
        }

        val delegateRefName = context().getScopeForDescriptor(descriptor).declareName(delegateName)
        val delegateRef = JsNameRef(delegateRefName, JsLiteral.THIS)
        val overriddenMemberFunctionRef = JsNameRef(overriddenMemberFunctionName, delegateRef)

        for (param in descriptor.getValueParameters()) {
            val paramName = param.getName().asString()
            val jsParamName = functionObject.getScope().declareName(paramName)
            functionObject.getParameters().add(JsParameter(jsParamName))
            args.add(JsNameRef(jsParamName))
        }
        functionObject.getBody().getStatements().add(JsReturn(JsInvocation(overriddenMemberFunctionRef, args)))
        properties.add(JsPropertyInitializer(delegateMemberFunctionName.makeRef(), functionObject))
    }
}
