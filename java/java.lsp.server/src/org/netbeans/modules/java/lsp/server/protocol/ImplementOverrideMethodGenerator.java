/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.netbeans.modules.java.lsp.server.protocol;

import com.google.gson.Gson;
import com.sun.source.util.TreePath;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import org.eclipse.lsp4j.ApplyWorkspaceEditParams;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionKind;
import org.eclipse.lsp4j.CodeActionParams;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.MessageType;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.netbeans.api.java.source.CompilationController;
import org.netbeans.api.java.source.ElementUtilities;
import org.netbeans.api.java.source.JavaSource;
import org.netbeans.api.java.source.TreeUtilities;
import org.netbeans.modules.java.editor.codegen.GeneratorUtils;
import org.netbeans.modules.java.lsp.server.Utils;
import org.netbeans.modules.parsing.api.ResultIterator;
import org.openide.filesystems.FileObject;
import org.openide.util.NbBundle;
import org.openide.util.lookup.ServiceProvider;

/**
 *
 * @author Dusan Balek
 */
@ServiceProvider(service = CodeActionsProvider.class, position = 70)
public final class ImplementOverrideMethodGenerator extends CodeActionsProvider {

    public static final String GENERATE_IMPLEMENT_METHOD =  "java.generate.implementMethod";
    public static final String GENERATE_OVERRIDE_METHOD =  "java.generate.overrideMethod";

    private final Set<String> commands = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(GENERATE_IMPLEMENT_METHOD, GENERATE_OVERRIDE_METHOD)));
    private final Gson gson = new Gson();

    public ImplementOverrideMethodGenerator() {
    }

    @Override
    @NbBundle.Messages({
        "DN_GenerateImplementMethod=Generate Implement Method...",
        "DN_GenerateOverrideMethod=Generate Override Method...",
        "DN_From=(from {0})",
    })
    public List<CodeAction> getCodeActions(ResultIterator resultIterator, CodeActionParams params) throws Exception {
        List<String> only = params.getContext().getOnly();
        if (only == null || !only.contains(CodeActionKind.Source)) {
            return Collections.emptyList();
        }
        CompilationController info = CompilationController.get(resultIterator.getParserResult());
        if (info == null) {
            return Collections.emptyList();
        }
        info.toPhase(JavaSource.Phase.RESOLVED);
        int offset = getOffset(info, params.getRange().getStart());
        TreePath tp = info.getTreeUtilities().pathFor(offset);
        tp = info.getTreeUtilities().getPathElementOfKind(TreeUtilities.CLASS_TREE_KINDS, tp);
        if (tp == null) {
            return Collections.emptyList();
        }
        TypeElement typeElement = (TypeElement) info.getTrees().getElement(tp);
        if (typeElement == null || typeElement.getKind() == ElementKind.ANNOTATION_TYPE) {
            return Collections.emptyList();
        }
        List<CodeAction> result = new ArrayList<>();
        String uri = Utils.toUri(info.getFileObject());
        ElementUtilities eu = info.getElementUtilities();
        if (typeElement.getKind().isClass() || typeElement.getKind().isInterface() && SourceVersion.RELEASE_8.compareTo(info.getSourceVersion()) <= 0) {
            List<QuickPickItem> implementMethods = new ArrayList<>();
            for (ExecutableElement method : eu.findUnimplementedMethods(typeElement, true)) {
                boolean mustImplement = !method.getModifiers().contains(Modifier.DEFAULT);
                Element enclosingElement = method.getEnclosingElement();
                String enclosingTypeName = enclosingElement.getKind().isClass() || enclosingElement.getKind().isInterface() ? Bundle.DN_From(((TypeElement)enclosingElement).getQualifiedName().toString()) : null;
                implementMethods.add(new QuickPickItem(createLabel(info, method), enclosingTypeName, null, mustImplement, new ElementData(method)));
            }
            if (!implementMethods.isEmpty()) {
                result.add(createCodeAction(Bundle.DN_GenerateImplementMethod(), CODE_GENERATOR_KIND, GENERATE_IMPLEMENT_METHOD, uri, offset, implementMethods));
            }
        }
        if (typeElement.getKind().isClass() || typeElement.getKind().isInterface()) {
            List<QuickPickItem> overrideMethods = new ArrayList<>();
            for (ExecutableElement method : eu.findOverridableMethods(typeElement)) {
                Element enclosingElement = method.getEnclosingElement();
                String enclosingTypeName = enclosingElement.getKind().isClass() || enclosingElement.getKind().isInterface() ? Bundle.DN_From(((TypeElement) enclosingElement).getQualifiedName().toString()) : null;
                QuickPickItem item = new QuickPickItem(createLabel(info, method));
                if (enclosingTypeName != null) {
                    item.setDescription(enclosingTypeName);
                }
                item.setUserData(new ElementData(method));
                overrideMethods.add(item);
            }
            if (!overrideMethods.isEmpty()) {
                result.add(createCodeAction(Bundle.DN_GenerateOverrideMethod(), CODE_GENERATOR_KIND, GENERATE_OVERRIDE_METHOD, uri, offset, overrideMethods));
            }
        }
        return result;
    }

    @Override
    public Set<String> getCommands() {
        return commands;
    }

    @Override
    @NbBundle.Messages({
        "DN_SelectImplementMethod=Select methods to implement",
        "DN_SelectOverrideMethod=Select methods to override",
    })
    public CompletableFuture<Object> processCommand(NbCodeLanguageClient client, String command, List<Object> arguments) {
        if (arguments.size() > 2) {
            String uri = gson.fromJson(gson.toJson(arguments.get(0)), String.class);
            int offset = gson.fromJson(gson.toJson(arguments.get(1)), Integer.class);
            List<QuickPickItem> methods = Arrays.asList(gson.fromJson(gson.toJson(arguments.get(2)), QuickPickItem[].class));
            String text = command == GENERATE_IMPLEMENT_METHOD ? Bundle.DN_SelectImplementMethod() : Bundle.DN_SelectOverrideMethod();
            boolean isImplement = command == GENERATE_IMPLEMENT_METHOD;
            client.showQuickPick(new ShowQuickPickParams(text, true, methods)).thenAccept(selected -> {
                if (selected != null && !selected.isEmpty()) {
                    generate(client, uri, offset, isImplement, selected);
                }
            });
        } else {
            client.logMessage(new MessageParams(MessageType.Error, String.format("Illegal number of arguments received for command: %s", command)));
        }
        return CompletableFuture.completedFuture(true);
    }

    private void generate(NbCodeLanguageClient client, String uri, int offset, boolean isImplement, List<QuickPickItem> methods) {
        try {
            FileObject file = Utils.fromUri(uri);
            JavaSource js = JavaSource.forFileObject(file);
            if (js == null) {
                throw new IOException("Cannot get JavaSource for: " + uri);
            }
            List<TextEdit> edits = TextDocumentServiceImpl.modify2TextEdits(js, wc -> {
                wc.toPhase(JavaSource.Phase.RESOLVED);
                TreePath tp = wc.getTreeUtilities().pathFor(offset);
                tp = wc.getTreeUtilities().getPathElementOfKind(TreeUtilities.CLASS_TREE_KINDS, tp);
                if (tp != null) {
                    List<ExecutableElement> selectedMethods = methods.stream().map(item -> {
                        ElementData data = gson.fromJson(gson.toJson(item.getUserData()), ElementData.class);
                        return (ExecutableElement)data.resolve(wc);
                    }).collect(Collectors.toList());
                    if (isImplement) {
                        GeneratorUtils.generateAbstractMethodImplementations(wc, tp, selectedMethods, -1);
                    } else {
                        GeneratorUtils.generateMethodOverrides(wc, tp, selectedMethods, -1);
                    }
                }
            });
            client.applyEdit(new ApplyWorkspaceEditParams(new WorkspaceEdit(Collections.singletonMap(uri, edits))));
        } catch (IOException | IllegalArgumentException ex) {
            client.logMessage(new MessageParams(MessageType.Error, ex.getLocalizedMessage()));
        }
    }
}
