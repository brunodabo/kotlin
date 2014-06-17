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

package org.jetbrains.jet.plugin.codeInsight;

import com.intellij.codeInsight.daemon.GutterIconNavigationHandler;
import com.intellij.codeInsight.daemon.LineMarkerInfo;
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.psi.NavigatablePsiElement;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.rt.execution.junit.FileComparisonFailure;
import com.intellij.testFramework.ExpectedHighlightingData;
import junit.framework.AssertionFailedError;
import org.jetbrains.jet.JetTestUtils;
import org.jetbrains.jet.lang.psi.JetElement;
import org.jetbrains.jet.plugin.JetLightCodeInsightFixtureTestCase;
import org.jetbrains.jet.plugin.PluginTestCaseBase;
import org.jetbrains.jet.plugin.highlighter.JetLineMarkerProvider;
import org.jetbrains.jet.plugin.libraries.NavigateToLibrarySourceTest;
import org.jetbrains.jet.testing.HighlightTestDataUtil;

import java.awt.event.MouseEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class OverrideImplementLineMarkerTest extends JetLightCodeInsightFixtureTestCase {
    @Override
    protected String getBasePath() {
        return PluginTestCaseBase.TEST_DATA_PROJECT_RELATIVE + "/codeInsight/lineMarker";
    }

    public void testTrait() throws Throwable {
        doTest();
    }

    public void testClass() throws Throwable {
        doTest();
    }

    public void testOverrideFunction() throws Throwable {
        doTest();
    }

    public void testPropertyOverride() throws Throwable {
        doTest();
    }

    public void testDelegatedFun() throws Exception {
        doTest();
    }

    public void testFakeOverrideFun() throws Exception {
        doTest();
    }

    public void testDelegatedProperty() throws Exception {
        doTest();
    }

    public void testClassObjectInStaticNestedClass() throws Exception {
        doTest();
    }

    public void testFakeOverrideProperty() throws Exception {
        doTest();
    }

    public void testFakeOverrideFunWithMostRelevantImplementation() throws Exception {
        doTest();
    }

    public void testOverrideIconForOverloadMethodBug() {
        doTest();
    }

    public void testOverloads() {
        doTest();
    }

    public void testToStringInTrait() {
        doTest();
    }

    public void testNavigateToSeveralSuperElements() {
        doTest();
    }

    public void testFakeOverridesForTraitFunWithImpl() {
        doTest();
    }

    public void testFakeOverrideToStringInTrait() {
        doTest();
    }

    private void doTest() {
        try {
            myFixture.configureByFile(fileName());
            Project project = myFixture.getProject();
            Document document = myFixture.getEditor().getDocument();

            ExpectedHighlightingData data = new ExpectedHighlightingData(
                    document, false, false, false, myFixture.getFile());
            data.init();

            PsiDocumentManager.getInstance(project).commitAllDocuments();

            myFixture.doHighlighting();

            List<LineMarkerInfo> markers = DaemonCodeAnalyzerImpl.getLineMarkers(document, project);

            try {
                data.checkLineMarkers(markers, document.getText());
                assertNavigationElements(markers);
            }
            catch (AssertionError error) {
                try {
                    String actualTextWithTestData = HighlightTestDataUtil.getTextWithTags(markers, false, myFixture.getFile().getText());
                    JetTestUtils.assertEqualsToFile(new File(getTestDataPath(), fileName()), actualTextWithTestData);
                }
                catch (FileComparisonFailure failure) {
                    throw new FileComparisonFailure(error.getMessage() + "\n" + failure.getMessage(),
                                                    failure.getExpected(),
                                                    failure.getActual(),
                                                    failure.getFilePath());
                }
            }
        }
        catch (Exception exc) {
            throw new RuntimeException(exc);
        }
    }

    private void assertNavigationElements(List<LineMarkerInfo> markers) {
        //String expectedNavigationData = getExpectedSuperDeclarationNavigationData();
        //if (expectedNavigationData.isEmpty()) return;
        //
        //Collection<PsiElement> navigateElements = new ArrayList<PsiElement>();
        //for (LineMarkerInfo marker : markers) {
        //    PsiElement element = marker.getElement();
        //    GutterIconNavigationHandler handler = marker.getNavigationHandler();
        //
        //    if (handler instanceof JetLineMarkerProvider.KotlinSuperNavigationHandler) {
        //        //noinspection unchecked
        //        handler.navigate(null, element);
        //        navigateElements.addAll(((JetLineMarkerProvider.KotlinSuperNavigationHandler) handler).getNavigationElements());
        //    }
        //}
        //String actualNavigationData = NavigateToLibrarySourceTest.getActualAnnotatedLibraryCode(myFixture.getProject(), navigateElements);
        //
        //assertSameLines(expectedNavigationData, actualNavigationData);
    }

    private String getExpectedSuperDeclarationNavigationData() {
        Document document = myFixture.getDocument(myFixture.getFile());
        assertNotNull(document);
        return JetTestUtils.getLastCommentedLines(document);
    }
}
