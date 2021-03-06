/*
 * Copyright 2010-2017 JetBrains s.r.o.
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

package org.jetbrains.kotlin.idea.inspections

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElementVisitor
import org.jetbrains.kotlin.builtins.BuiltInsPackageFragment
import org.jetbrains.kotlin.config.LanguageFeature.ArrayLiteralsInAnnotations
import org.jetbrains.kotlin.idea.caches.resolve.analyze
import org.jetbrains.kotlin.idea.project.getLanguageVersionSettings
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.resolve.CollectionLiteralResolver
import org.jetbrains.kotlin.resolve.calls.callUtil.getResolvedCall

class ReplaceArrayOfWithLiteralInspection : AbstractKotlinInspection() {

    private val acceptableNames = setOf(CollectionLiteralResolver.ARRAY_OF_FUNCTION) +
                                  CollectionLiteralResolver.PRIMITIVE_TYPE_TO_ARRAY.values.toSet() +
                                  Name.identifier("emptyArray")

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        return object : KtVisitorVoid() {
            override fun visitCallExpression(expression: KtCallExpression) {
                super.visitCallExpression(expression)

                val project = expression.project
                if (!project.getLanguageVersionSettings().supportsFeature(ArrayLiteralsInAnnotations) &&
                    !ApplicationManager.getApplication().isUnitTestMode) return

                val calleeExpression = expression.calleeExpression as? KtNameReferenceExpression ?: return
                val resolvedCall = expression.getResolvedCall(expression.analyze()) ?: return
                val descriptor = resolvedCall.candidateDescriptor
                if (descriptor.containingDeclaration !is BuiltInsPackageFragment) return
                if (descriptor.name !in acceptableNames) return

                val parent = expression.parent
                when (parent) {
                    is KtValueArgument -> parent.parent.parent as? KtAnnotationEntry ?: return
                    is KtParameter -> {
                        val constructor = parent.parent.parent as? KtPrimaryConstructor ?: return
                        val containingClass = constructor.getContainingClassOrObject()
                        if (!containingClass.isAnnotation()) return
                    }
                    else -> return
                }

                val calleeName = calleeExpression.getReferencedName()
                holder.registerProblem(
                        calleeExpression,
                        "'$calleeName' call can be replaced with array literal [...]",
                        ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                        ReplaceWithArrayLiteralFix()
                )
            }
        }
    }

    private class ReplaceWithArrayLiteralFix : LocalQuickFix {
        override fun getFamilyName() = "Replace with [...]"

        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            val calleeExpression = descriptor.psiElement as KtExpression
            val callExpression = calleeExpression.parent as KtCallExpression
            val arguments = callExpression.valueArguments
            val arrayLiteral = KtPsiFactory(callExpression).buildExpression {
                appendFixedText("[")
                for ((index, argument) in arguments.withIndex()) {
                    appendExpression(argument.getArgumentExpression())
                    if (index != arguments.size - 1) {
                        appendFixedText(", ")
                    }
                }
                appendFixedText("]")
            } as KtCollectionLiteralExpression
            callExpression.replace(arrayLiteral)
        }
    }
}