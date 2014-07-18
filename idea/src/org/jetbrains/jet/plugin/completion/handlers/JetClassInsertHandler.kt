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

package org.jetbrains.jet.plugin.completion.handlers

import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.RangeMarker
import com.intellij.psi.PsiDocumentManager
import org.jetbrains.jet.lang.descriptors.DeclarationDescriptor
import org.jetbrains.jet.lang.psi.JetFile
import org.jetbrains.jet.lang.resolve.DescriptorUtils
import org.jetbrains.jet.plugin.codeInsight.ShortenReferences
import org.jetbrains.jet.plugin.completion.JetLookupObject

public object JetClassInsertHandler : InsertHandler<LookupElement> {

    override fun handleInsert(context: InsertionContext, item: LookupElement) {
        val file = context.getFile()
        if (file is JetFile) {
            val descriptor = (item.getObject() as? JetLookupObject)?.getDescriptor()
            if (descriptor != null) {
                val startOffset = context.getStartOffset()
                val document = context.getDocument()
                if (!isAfterDot(document, startOffset)) {
                    val fqName = DescriptorUtils.getFqName(descriptor).asString()
                    // insert dot after because otherwise parser can sometimes produce no suitable reference here
                    val tempSuffix = ".xxx" // we add "xxx" after dot because of some bugs in resolve (see KT-5145)
                    document.replaceString(startOffset, context.getTailOffset(), fqName + tempSuffix)
                    val classNameEnd = startOffset + fqName.length()

                    val psiDocumentManager = PsiDocumentManager.getInstance(context.getProject())
                    psiDocumentManager.commitAllDocuments()
                    val rangeMarker = document.createRangeMarker(classNameEnd, classNameEnd + tempSuffix.length())

                    ShortenReferences.process(file, startOffset, classNameEnd)
                    psiDocumentManager.commitAllDocuments()
                    psiDocumentManager.doPostponedOperationsAndUnblockDocument(document)

                    if (rangeMarker.isValid()) {
                        document.deleteString(rangeMarker.getStartOffset(), rangeMarker.getEndOffset())
                    }
                }
            }
        }
    }

    private fun isAfterDot(document: Document, offset: Int): Boolean {
        var curOffset = offset
        val chars = document.getCharsSequence()
        while (curOffset > 0) {
            curOffset--
            val c = chars.charAt(curOffset)
            if (!Character.isWhitespace(c)) {
                return c == '.'
            }
        }
        return false
    }
}
