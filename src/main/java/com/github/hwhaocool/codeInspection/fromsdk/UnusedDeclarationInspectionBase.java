// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.github.hwhaocool.codeInspection.fromsdk;

import com.github.hwhaocool.codeInspection.deadcode.Constants;
import com.github.hwhaocool.codeInspection.fromsdk.unusedSymbol.UnusedSymbolLocalInspectionImpl;
import com.intellij.analysis.AnalysisBundle;
import com.intellij.analysis.AnalysisScope;
import com.intellij.codeInsight.daemon.impl.analysis.HighlightUtilBase;
import com.intellij.codeInspection.GlobalInspectionContext;
import com.intellij.codeInspection.GlobalInspectionTool;
import com.intellij.codeInspection.GlobalJavaInspectionContext;
import com.intellij.codeInspection.InspectionManager;
import com.intellij.codeInspection.InspectionProfile;
import com.intellij.codeInspection.ProblemDescriptionsProcessor;
import com.intellij.codeInspection.ex.EntryPointsManager;
import com.intellij.codeInspection.ex.EntryPointsManagerBase;
import com.intellij.codeInspection.ex.GlobalInspectionContextBase;
import com.intellij.codeInspection.ex.JobDescriptor;
import com.intellij.codeInspection.reference.EntryPoint;
import com.intellij.codeInspection.reference.RefClass;
import com.intellij.codeInspection.reference.RefClassImpl;
import com.intellij.codeInspection.reference.RefElement;
import com.intellij.codeInspection.reference.RefElementImpl;
import com.intellij.codeInspection.reference.RefEntity;
import com.intellij.codeInspection.reference.RefField;
import com.intellij.codeInspection.reference.RefFieldImpl;
import com.intellij.codeInspection.reference.RefImplicitConstructor;
import com.intellij.codeInspection.reference.RefImplicitConstructorImpl;
import com.intellij.codeInspection.reference.RefJavaElement;
import com.intellij.codeInspection.reference.RefJavaElementImpl;
import com.intellij.codeInspection.reference.RefJavaVisitor;
import com.intellij.codeInspection.reference.RefManager;
import com.intellij.codeInspection.reference.RefMethod;
import com.intellij.codeInspection.reference.RefMethodImpl;
import com.intellij.codeInspection.reference.RefUtil;
import com.intellij.codeInspection.unusedSymbol.UnusedSymbolLocalInspectionBase;
import com.intellij.codeInspection.util.RefFilter;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.InvalidDataException;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.WriteExternalException;
import com.intellij.profile.codeInspection.InspectionProjectProfileManager;
import com.intellij.psi.CommonClassNames;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiModifierListOwner;
import com.intellij.psi.PsiType;
import com.intellij.psi.impl.PsiClassImplUtil;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiMethodUtil;
import com.intellij.util.containers.ContainerUtil;
import org.jdom.Element;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;
import org.jetbrains.uast.UClass;
import org.jetbrains.uast.UDeclarationKt;
import org.jetbrains.uast.UElement;
import org.jetbrains.uast.UField;
import org.jetbrains.uast.UMethod;
import org.jetbrains.uast.UParameter;
import org.jetbrains.uast.UastVisibility;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 原版代码来自 com.intellij.codeInspection.deadCode.UnusedDeclarationInspectionBase， 修改了很多
 * @author YellowTail
 * @since 2020-12-08
 */
public class UnusedDeclarationInspectionBase extends GlobalInspectionTool {
    private static final Logger LOG = Logger.getInstance(UnusedDeclarationInspectionBase.class);

    public boolean ADD_MAINS_TO_ENTRIES = true;
    public boolean ADD_APPLET_TO_ENTRIES = true;
    public boolean ADD_SERVLET_TO_ENTRIES = true;
    public boolean ADD_NONJAVA_TO_ENTRIES = true;
    private boolean TEST_ENTRY_POINTS = true;


    /**
     * 这个是重中之重， intellij 对插件 shortName 的校验很严格，包括从上下文拿到插件对象，有的地方也是通过 shortName 来实现的，一定要修改，且要统一
     */
    public static final String SHORT_NAME = Constants.SHORT_NAME;

//    public static final String SHORT_NAME = "unused";
//    public static final String ALTERNATIVE_ID = "UnusedDeclaration";

    final List<EntryPoint> myExtensions = ContainerUtil.createLockFreeCopyOnWriteList();
    public final UnusedSymbolLocalInspectionBase myLocalInspectionBase = createUnusedSymbolLocalInspection();

    protected static final Key<Set<RefElement>> PROCESSED_SUSPICIOUS_ELEMENTS_KEY = Key.create("java.unused.declaration.processed.suspicious.elements");
    protected static final Key<Integer> PHASE_KEY = Key.create("java.unused.declaration.phase");

    private final boolean myEnabledInEditor;

    @SuppressWarnings("TestOnlyProblems")
    public UnusedDeclarationInspectionBase() {
        this(!ApplicationManager.getApplication().isUnitTestMode());
    }

    @NotNull
    public String getGroupDisplayName() {

        return "Declaration Redundancy";
    }

    @NotNull
    @Override
    public UnusedSymbolLocalInspectionBase getSharedLocalInspectionTool() {
        return myLocalInspectionBase;
    }

    @TestOnly
    public UnusedDeclarationInspectionBase(boolean enabledInEditor) {
        List<EntryPoint> extensions = EntryPointsManagerBase.DEAD_CODE_EP_NAME.getExtensionList();
        List<EntryPoint> deadCodeAddIns = new ArrayList<>(extensions.size());
        for (EntryPoint entryPoint : extensions) {
            try {
                deadCodeAddIns.add(entryPoint.clone());
            } catch (Exception e) {
                LOG.error(e);
            }
        }
        Collections.sort(deadCodeAddIns, (o1, o2) -> o1.getDisplayName().compareToIgnoreCase(o2.getDisplayName()));
        myExtensions.addAll(deadCodeAddIns);
        myEnabledInEditor = enabledInEditor;
    }

    protected UnusedSymbolLocalInspectionBase createUnusedSymbolLocalInspection() {
        return new UnusedSymbolLocalInspectionImpl();
    }



    private boolean isAddMainsEnabled() {
        return ADD_MAINS_TO_ENTRIES;
    }

    private boolean isAddAppletEnabled() {
        return ADD_APPLET_TO_ENTRIES;
    }

    private boolean isAddServletEnabled() {
        return ADD_SERVLET_TO_ENTRIES;
    }

    private boolean isAddNonJavaUsedEnabled() {
        return ADD_NONJAVA_TO_ENTRIES;
    }

    public boolean isTestEntryPoints() {
        return TEST_ENTRY_POINTS;
    }

    public void setTestEntryPoints(boolean testEntryPoints) {
        TEST_ENTRY_POINTS = testEntryPoints;
    }

    @Override
    public void readSettings(@NotNull Element node) throws InvalidDataException {
        super.readSettings(node);
        myLocalInspectionBase.readSettings(node);
        for (EntryPoint extension : myExtensions) {
            extension.readExternal(node);
        }

        final String testEntriesAttr = node.getAttributeValue("test_entries");
        TEST_ENTRY_POINTS = testEntriesAttr == null || Boolean.parseBoolean(testEntriesAttr);
    }

    @Override
    public void writeSettings(@NotNull Element node) throws WriteExternalException {
        myLocalInspectionBase.writeSettings(node);
        writeUnusedDeclarationSettings(node);

        if (!TEST_ENTRY_POINTS) {
            node.setAttribute("test_entries", Boolean.toString(false));
        }
    }

    protected void writeUnusedDeclarationSettings(Element node) throws WriteExternalException {
        super.writeSettings(node);
        for (EntryPoint extension : myExtensions) {
            extension.writeExternal(node);
        }
    }

    private static boolean isExternalizableNoParameterConstructor(@NotNull UMethod method, RefClass refClass) {
        if (!method.isConstructor()) {
            return false;
        }
        if (method.getVisibility() != UastVisibility.PUBLIC) {
            return false;
        }
        final List<UParameter> parameterList = method.getUastParameters();
        if (!parameterList.isEmpty()) {
            return false;
        }
        final UClass aClass = UDeclarationKt.getContainingDeclaration(method, UClass.class);
        return aClass == null || isExternalizable(aClass, refClass);
    }

    private static boolean isSerializationImplicitlyUsedField(@NotNull UField field) {
        final String name = field.getName();
        if (!HighlightUtilBase.SERIAL_VERSION_UID_FIELD_NAME.equals(name) && !"serialPersistentFields".equals(name)) {
            return false;
        }
        if (!field.isStatic()) {
            return false;
        }
        UClass aClass = UDeclarationKt.getContainingDeclaration(field, UClass.class);
        return aClass == null || isSerializable(aClass, null);
    }

    private static boolean isWriteObjectMethod(@NotNull UMethod method, RefClass refClass) {
        final String name = method.getName();
        if (!"writeObject".equals(name)) {
            return false;
        }
        List<UParameter> parameters = method.getUastParameters();
        if (parameters.size() != 1) {
            return false;
        }
        if (!equalsToText(parameters.get(0).getType(), "java.io.ObjectOutputStream")) {
            return false;
        }
        if (method.isStatic()) {
            return false;
        }
        UClass aClass = UDeclarationKt.getContainingDeclaration(method, UClass.class);
        return !(aClass != null && !isSerializable(aClass, refClass));
    }

    private static boolean isReadObjectMethod(@NotNull UMethod method, RefClass refClass) {
        final String name = method.getName();
        if (!"readObject".equals(name)) {
            return false;
        }
        List<UParameter> parameters = method.getUastParameters();
        if (parameters.size() != 1) {
            return false;
        }
        if (!equalsToText(parameters.get(0).getType(), "java.io.ObjectInputStream")) {
            return false;
        }
        if (method.isStatic()) {
            return false;
        }
        UClass aClass = UDeclarationKt.getContainingDeclaration(method, UClass.class);
        return !(aClass != null && !isSerializable(aClass, refClass));
    }

    private static boolean isWriteReplaceMethod(@NotNull UMethod method, RefClass refClass) {
        final String name = method.getName();
        if (!"writeReplace".equals(name)) {
            return false;
        }
        List<UParameter> parameters = method.getUastParameters();
        if (parameters.size() != 0) {
            return false;
        }
        if (!equalsToText(method.getReturnType(), CommonClassNames.JAVA_LANG_OBJECT)) {
            return false;
        }
        if (method.isStatic()) {
            return false;
        }
        UClass aClass = UDeclarationKt.getContainingDeclaration(method, UClass.class);
        return !(aClass != null && !isSerializable(aClass, refClass));
    }

    private static boolean isReadResolveMethod(@NotNull UMethod method, RefClass refClass) {
        final String name = method.getName();
        if (!"readResolve".equals(name)) {
            return false;
        }
        List<UParameter> parameters = method.getUastParameters();
        if (parameters.size() != 0) {
            return false;
        }
        if (!equalsToText(method.getReturnType(), CommonClassNames.JAVA_LANG_OBJECT)) {
            return false;
        }
        if (method.isStatic()) {
            return false;
        }
        final UClass aClass = UDeclarationKt.getContainingDeclaration(method, UClass.class);
        return !(aClass != null && !isSerializable(aClass, refClass));
    }

    private static boolean equalsToText(PsiType type, String text) {
        return type != null && type.equalsToText(text);
    }

    private static boolean isSerializable(UClass aClass, @Nullable RefClass refClass) {
        PsiClass psi = aClass.getPsi();
        final PsiClass serializableClass = JavaPsiFacade.getInstance(psi.getProject()).findClass("java.io.Serializable", psi.getResolveScope());
        return serializableClass != null && isSerializable(aClass, refClass, serializableClass);
    }

    private static boolean isExternalizable(@NotNull UClass aClass, RefClass refClass) {
        PsiClass psi = aClass.getPsi();
        final PsiClass externalizableClass = JavaPsiFacade.getInstance(psi.getProject()).findClass("java.io.Externalizable", psi.getResolveScope());
        return externalizableClass != null && isSerializable(aClass, refClass, externalizableClass);
    }

    private static boolean isSerializable(UClass aClass, RefClass refClass, PsiClass serializableClass) {
        if (aClass == null) {
            return false;
        }
        if (aClass.getJavaPsi().isInheritor(serializableClass, true)) {
            return true;
        }
        if (refClass != null) {
            final Set<RefClass> subClasses = refClass.getSubClasses();
            for (RefClass subClass : subClasses) {
                //TODO reimplement
                if (isSerializable(subClass.getUastElement(), subClass, serializableClass)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public boolean isReadActionNeeded() {
        return false;
    }

    @Override
    public void runInspection(@NotNull final AnalysisScope scope,
                              @NotNull InspectionManager manager,
                              @NotNull final GlobalInspectionContext globalContext,
                              @NotNull ProblemDescriptionsProcessor problemDescriptionsProcessor) {

        globalContext.getRefManager().iterate(new RefJavaVisitor() {
            @Override
            public void visitElement(@NotNull final RefEntity refEntity) {


                if (refEntity instanceof RefElementImpl) {
                    final RefElementImpl refElement = (RefElementImpl) refEntity;
                    if (!refElement.isSuspicious()) {
                        return;
                    }

                    PsiFile file = refElement.getContainingFile();

                    if (file == null) {
                        return;
                    }
                    final boolean isSuppressed = refElement.isSuppressed(getShortName(), getAlternativeID());
                    if (isSuppressed || !((GlobalInspectionContextBase) globalContext).isToCheckFile(file, UnusedDeclarationInspectionBase.this)) {
                        if (isSuppressed || !scope.contains(file)) {
                            getEntryPointsManager(globalContext).addEntryPoint(refElement, false);
                        }
                    }
                }
            }
        });

        globalContext.putUserData(PHASE_KEY, 1);
        globalContext.putUserData(PROCESSED_SUSPICIOUS_ELEMENTS_KEY, new HashSet<>());
    }

    public boolean isEntryPoint(@NotNull RefElement owner) {
        System.out.println("UnusedDeclarationInspectionBase isEntryPoint RefElement");

        PsiElement element = owner.getPsiElement();
        if (owner instanceof RefJavaElement) {
            UElement uElement = ((RefJavaElement) owner).getUastElement();
            if (uElement != null) {
                element = uElement.getJavaPsi();
            }
        }
        if (RefUtil.isImplicitUsage(element)) {
            return true;
        }
        if (element instanceof PsiModifierListOwner) {
            final EntryPointsManager entryPointsManager = EntryPointsManager.getInstance(element.getProject());
            if (entryPointsManager.isEntryPoint(element)) {
                return true;
            }
        }
        if (element != null) {
            for (EntryPoint extension : myExtensions) {
                if (extension.isSelected() && extension.isEntryPoint(owner, element)) {
                    return true;
                }
            }

            if (isAddMainsEnabled() && owner instanceof RefMethod && ((RefMethod) owner).isAppMain()) {
                return true;
            }

            if (owner instanceof RefClass) {
                if (isAddAppletEnabled() && ((RefClass) owner).isApplet() || isAddServletEnabled() && ((RefClass) owner).isServlet()) {
                    return true;
                }
            }
        }

        return false;
    }

    public boolean isEntryPoint(@NotNull PsiElement element) {
        System.out.println("UnusedDeclarationInspectionBase isEntryPoint PsiElement");

        final Project project = element.getProject();
        final JavaPsiFacade psiFacade = JavaPsiFacade.getInstance(project);
        if (element instanceof PsiMethod && isAddMainsEnabled() && PsiClassImplUtil.isMainOrPremainMethod((PsiMethod) element)) {
            return true;
        }
        if (element instanceof PsiClass) {
            PsiClass aClass = (PsiClass) element;
            final PsiClass applet = psiFacade.findClass("java.applet.Applet", GlobalSearchScope.allScope(project));
            if (isAddAppletEnabled() && applet != null && aClass.isInheritor(applet, true)) {
                return true;
            }

            final PsiClass servlet = psiFacade.findClass("javax.servlet.Servlet", GlobalSearchScope.allScope(project));
            if (isAddServletEnabled() && servlet != null && aClass.isInheritor(servlet, true)) {
                return true;
            }
            if (isAddMainsEnabled()) {
                if (hasMainMethodDeep(aClass)) {
                    return true;
                }
            }
        }
        if (element instanceof PsiModifierListOwner) {
            final EntryPointsManager entryPointsManager = EntryPointsManager.getInstance(project);
            if (entryPointsManager.isEntryPoint(element)) {
                return true;
            }
        }
        for (EntryPoint extension : myExtensions) {
            if (extension.isSelected() && extension.isEntryPoint(element)) {
                return true;
            }
        }
        return RefUtil.isImplicitUsage(element);
    }

    private static boolean hasMainMethodDeep(PsiClass aClass) {
        if (PsiMethodUtil.hasMainMethod(aClass)) {
            return true;
        }
        for (PsiClass innerClass : aClass.getInnerClasses()) {
            if (innerClass.hasModifierProperty(PsiModifier.STATIC) && PsiMethodUtil.hasMainMethod(innerClass)) {
                return true;
            }
        }
        return false;
    }

    public boolean isGlobalEnabledInEditor() {
        return myEnabledInEditor;
    }

    @NotNull
    public static UnusedDeclarationInspectionBase findUnusedDeclarationInspection(@NotNull PsiElement element) {
        System.out.println("UnusedDeclarationInspectionBase findUnusedDeclarationInspection");

        InspectionProfile profile = InspectionProjectProfileManager.getInstance(element.getProject()).getCurrentProfile();
        UnusedDeclarationInspectionBase tool = (UnusedDeclarationInspectionBase) profile.getUnwrappedTool(SHORT_NAME, element);
        return tool == null ? new UnusedDeclarationInspectionBase() : tool;
    }

    public static boolean isDeclaredAsEntryPoint(@NotNull PsiElement method) {
        return findUnusedDeclarationInspection(method).isEntryPoint(method);
    }

    private static class StrictUnreferencedFilter extends UnreferencedFilter {
        private StrictUnreferencedFilter(@NotNull UnusedDeclarationInspectionBase tool, @NotNull GlobalInspectionContext context) {
            super(tool, context);
        }

        @Override
        public int getElementProblemCount(@NotNull RefJavaElement refElement) {
            final int problemCount = super.getElementProblemCount(refElement);
            if (problemCount > -1) {
                return problemCount;
            }
            return refElement.isReferenced() ? 0 : 1;
        }
    }

    // 会被自动调用
    @Override
    public boolean queryExternalUsagesRequests(@NotNull InspectionManager manager,
                                               @NotNull GlobalInspectionContext globalContext,
                                               @NotNull ProblemDescriptionsProcessor problemDescriptionsProcessor) {

        System.out.println("UnusedDeclarationInspectionBase queryExternalUsagesRequests");

        checkForReachableRefs(globalContext);

        int phase = Objects.requireNonNull(globalContext.getUserData(PHASE_KEY));

        // 已经处理的 ref 对象，避免重复处理
        Set<RefElement> processedSuspicious = globalContext.getUserData(PROCESSED_SUSPICIOUS_ELEMENTS_KEY);

        final boolean firstPhase = phase == 1;
        final RefFilter filter = firstPhase ? new StrictUnreferencedFilter(this, globalContext) :
                new RefUnreachableFilter(this, globalContext);
        LOG.assertTrue(processedSuspicious != null, "phase: " + phase);

        final boolean[] requestAdded = {false};
        globalContext.getRefManager().iterate(new RefJavaVisitor() {
            @Override
            public void visitElement(@NotNull RefEntity refEntity) {
                if (!(refEntity instanceof RefJavaElement)) {
                    //跳过文件类型、 package的
                    return;
                }
                if (refEntity instanceof RefClass && ((RefClass) refEntity).isAnonymous()) {
                    //跳过匿名类
                    return;
                }
                RefJavaElement refElement = (RefJavaElement) refEntity;
                if (filter.accepts(refElement) && !processedSuspicious.contains(refElement)) {
                    refEntity.accept(new RefJavaVisitor() {

                        @Override
                        public void visitField(@NotNull final RefField refField) {

                            printName("UnusedDeclarationInspectionBase queryExternalUsagesRequests visitField  %s", refField);

                            processedSuspicious.add(refField);
                            UField uField = refField.getUastElement();
                            if (uField != null && isSerializationImplicitlyUsedField(uField)) {
                                getEntryPointsManager(globalContext).addEntryPoint(refField, false);
                            } else {
                                globalContext.getExtension(GlobalJavaInspectionContext.CONTEXT).enqueueFieldUsagesProcessor(refField, psiReference -> {
                                    getEntryPointsManager(globalContext).addEntryPoint(refField, false);
                                    return false;
                                });
                                requestAdded[0] = true;
                            }
                        }

                        @Override
                        public void visitMethod(@NotNull final RefMethod refMethod) {

                            printName("UnusedDeclarationInspectionBase queryExternalUsagesRequests visitMethod  %s", refMethod);

                            processedSuspicious.add(refMethod);
                            if (refMethod instanceof RefImplicitConstructor) {
                                RefClass ownerClass = refMethod.getOwnerClass();
                                LOG.assertTrue(ownerClass != null);
                                visitClass(ownerClass);
                                return;
                            }
                            if (refMethod.isConstructor()) {
                                RefClass ownerClass = refMethod.getOwnerClass();
                                LOG.assertTrue(ownerClass != null);
                                queryQualifiedNameUsages(ownerClass);
                            }
                            UMethod uMethod = (UMethod) refMethod.getUastElement();
                            if (uMethod != null && isSerializablePatternMethod(uMethod, refMethod.getOwnerClass())) {
                                getEntryPointsManager(globalContext).addEntryPoint(refMethod, false);
                            } else if (!refMethod.isExternalOverride() && !PsiModifier.PRIVATE.equals(refMethod.getAccessModifier())) {
                                processedSuspicious.addAll(refMethod.getDerivedMethods());
                                enqueueMethodUsages(globalContext, refMethod);
                                requestAdded[0] = true;
                            }
                        }

                        @Override
                        public void visitClass(@NotNull final RefClass refClass) {

                            printName("UnusedDeclarationInspectionBase queryExternalUsagesRequests visitClass  %s", refClass);

                            // 添加到已分析集合，避免重复分析
                            processedSuspicious.add(refClass);

                            if (refClass.isAnonymous()) {
                                // 匿名类，不分析了
                                return;
                            }

//                            PsiElement psiElement = refClass.getPsiElement();
//
//                            Query<PsiReference> search = ReferencesSearch.search(psiElement);
//                            PsiReference first = search.findFirst();
//                            if (null == first) {
//                                problemDescriptionsProcessor.addProblemElement(refEntity);
//                            }

                            globalContext.getExtension(GlobalJavaInspectionContext.CONTEXT)
                                    .enqueueDerivedClassesProcessor(refClass, inheritor -> {

                                getEntryPointsManager(globalContext).addEntryPoint(refClass, false);

                                return false;
                            });


                            // 排队 计算 class 的 使用情况
                            globalContext.getExtension(GlobalJavaInspectionContext.CONTEXT)
                                    .enqueueClassUsagesProcessor(refClass, psiReference -> {

                                printName("UnusedDeclarationInspectionBase queryExternalUsagesRequests visitClass enqueueClassUsagesProcessor %s", refClass);

                                getEntryPointsManager(globalContext).addEntryPoint(refClass, false);

                                return false;
                            });

                            queryQualifiedNameUsages(refClass);
                            requestAdded[0] = true;

                        }

                        public void queryQualifiedNameUsages(@NotNull RefClass refClass) {
                            if (firstPhase && isAddNonJavaUsedEnabled()) {

                                globalContext.getExtension(GlobalJavaInspectionContext.CONTEXT)
                                        .enqueueQualifiedNameOccurrencesProcessor(refClass, () -> {

                                    EntryPointsManager entryPointsManager = getEntryPointsManager(globalContext);
                                    entryPointsManager.addEntryPoint(refClass, false);

                                    for (RefMethod constructor : refClass.getConstructors()) {
                                        entryPointsManager.addEntryPoint(constructor, false);
                                    }
                                });

                                //references from java-like are already in graph or
                                //they would be checked during GlobalJavaInspectionContextImpl.performPostRunActivities
                                for (RefElement element : refClass.getInReferences()) {
                                    if (!(element instanceof RefJavaElement)) {
                                        getEntryPointsManager(globalContext).addEntryPoint(refElement, false);
                                    }
                                }
                                requestAdded[0] = true;
                            }
                        }
                    });
                }
            }
        });

        if (!requestAdded[0]) {
            if (phase == 2) {
                globalContext.putUserData(PROCESSED_SUSPICIOUS_ELEMENTS_KEY, null);
                return false;
            } else {
                globalContext.putUserData(PHASE_KEY, 2);
            }
        }

        return true;
    }

    private static boolean isSerializablePatternMethod(@NotNull UMethod psiMethod, RefClass refClass) {
        return isReadObjectMethod(psiMethod, refClass) || isWriteObjectMethod(psiMethod, refClass) || isReadResolveMethod(psiMethod, refClass) ||
                isWriteReplaceMethod(psiMethod, refClass) || isExternalizableNoParameterConstructor(psiMethod, refClass);
    }

    private static void enqueueMethodUsages(GlobalInspectionContext globalContext, final RefMethod refMethod) {
        if (refMethod.getSuperMethods().isEmpty()) {
            globalContext.getExtension(GlobalJavaInspectionContext.CONTEXT).enqueueMethodUsagesProcessor(refMethod, psiReference -> {
                getEntryPointsManager(globalContext).addEntryPoint(refMethod, false);
                return false;
            });
        } else {
            for (RefMethod refSuper : refMethod.getSuperMethods()) {
                enqueueMethodUsages(globalContext, refSuper);
            }
        }
    }

    @Override
    public JobDescriptor @Nullable [] getAdditionalJobs(GlobalInspectionContext context) {
        return new JobDescriptor[]{context.getStdJobDescriptors().BUILD_GRAPH, context.getStdJobDescriptors().FIND_EXTERNAL_USAGES};
    }


    void checkForReachableRefs(@NotNull final GlobalInspectionContext context) {
        CodeScanner codeScanner = new CodeScanner();

        // Cleanup previous reachability information.
        RefManager refManager = context.getRefManager();
        refManager.iterate(new RefJavaVisitor() {
            @Override
            public void visitElement(@NotNull RefEntity refEntity) {
                if (refEntity instanceof RefJavaElementImpl) {
                    final RefJavaElementImpl refElement = (RefJavaElementImpl) refEntity;
                    if (!((GlobalInspectionContextBase) context).isToCheckMember(refElement, UnusedDeclarationInspectionBase.this)) {
                        return;
                    }
                    refElement.setReachable(false);
                }
            }
        });


        for (RefElement entry : getEntryPointsManager(context).getEntryPoints(refManager)) {
            entry.accept(codeScanner);
        }

        while (codeScanner.newlyInstantiatedClassesCount() != 0) {
            codeScanner.cleanInstantiatedClassesCount();
            codeScanner.processDelayedMethods();
        }
    }

    private static EntryPointsManager getEntryPointsManager(final GlobalInspectionContext context) {
        return context.getExtension(GlobalJavaInspectionContext.CONTEXT).getEntryPointsManager(context.getRefManager());
    }

    private static class CodeScanner extends RefJavaVisitor {
        private final Map<RefClass, Set<RefMethod>> myClassIDtoMethods = new HashMap<>();
        private final Set<RefClass> myInstantiatedClasses = new HashSet<>();
        private int myInstantiatedClassesCount;
        private final Set<RefMethod> myProcessedMethods = new HashSet<>();

        @Override
        public void visitMethod(@NotNull RefMethod method) {

            printName("UnusedDeclarationInspectionBase CodeScanner visitMethod  %s", method);

            if (!myProcessedMethods.contains(method)) {
                // Process class's static initializers
                if (method.isStatic() || method.isConstructor() || method.isEntry()) {
                    if (method.isStatic()) {
                        RefElementImpl owner = (RefElementImpl) method.getOwner();
                        if (owner != null) {
                            owner.setReachable(true);
                        }
                    } else {
                        RefClass ownerClass = method.getOwnerClass();
                        if (ownerClass != null) {
                            addInstantiatedClass(ownerClass);
                        } else {
                            LOG.error("owner class is null for " + method.getPsiElement()
                                    + " is static ? " + method.isStatic()
                                    + "; is abstract ? " + method.isAbstract()
                                    + "; is main method ? " + method.isAppMain()
                                    + "; is constructor " + method.isConstructor()
                                    + "; containing file " + method.getPointer().getVirtualFile().getFileType());
                        }
                    }
                    myProcessedMethods.add(method);
                    makeContentReachable((RefJavaElementImpl) method);
                    makeClassInitializersReachable(method.getOwnerClass());
                } else {
                    if (isClassInstantiated(method.getOwnerClass())) {
                        myProcessedMethods.add(method);
                        makeContentReachable((RefJavaElementImpl) method);
                    } else {
                        addDelayedMethod(method);
                    }

                    for (RefMethod refSub : method.getDerivedMethods()) {
                        visitMethod(refSub);
                    }
                }
            }
        }

        @Override
        public void visitClass(@NotNull RefClass refClass) {

            printName("UnusedDeclarationInspectionBase CodeScanner visitClass  %s", refClass);

            boolean alreadyActive = refClass.isReachable();
            ((RefClassImpl) refClass).setReachable(true);

            if (!alreadyActive) {
                // Process class's static initializers.
                makeClassInitializersReachable(refClass);
            }

            addInstantiatedClass(refClass);
        }

        @Override
        public void visitField(@NotNull RefField field) {

            printName("UnusedDeclarationInspectionBase CodeScanner visitField  %s", field);

            // Process class's static initializers.
            if (!field.isReachable()) {
                makeContentReachable((RefJavaElementImpl) field);
                makeClassInitializersReachable(field.getOwnerClass());
            }
        }

        private void addInstantiatedClass(@NotNull RefClass refClass) {
            if (myInstantiatedClasses.add(refClass)) {
                ((RefClassImpl) refClass).setReachable(true);
                myInstantiatedClassesCount++;

                final List<RefMethod> refMethods = refClass.getLibraryMethods();
                for (RefMethod refMethod : refMethods) {
                    refMethod.accept(this);
                }
                for (RefClass baseClass : refClass.getBaseClasses()) {
                    addInstantiatedClass(baseClass);
                }
            }
        }

        private void makeContentReachable(RefJavaElementImpl refElement) {
            refElement.setReachable(true);
            for (RefElement refCallee : refElement.getOutReferences()) {
                refCallee.accept(this);
            }
        }

        private void makeClassInitializersReachable(@Nullable RefClass refClass) {
            if (refClass != null) {
                for (RefElement refCallee : refClass.getOutReferences()) {
                    refCallee.accept(this);
                }
            }
        }

        private void addDelayedMethod(RefMethod refMethod) {
            Set<RefMethod> methods = myClassIDtoMethods.get(refMethod.getOwnerClass());
            if (methods == null) {
                methods = new HashSet<>();
                myClassIDtoMethods.put(refMethod.getOwnerClass(), methods);
            }
            methods.add(refMethod);
        }

        private boolean isClassInstantiated(RefClass refClass) {
            return refClass == null || refClass.isUtilityClass() || myInstantiatedClasses.contains(refClass);
        }

        private int newlyInstantiatedClassesCount() {
            return myInstantiatedClassesCount;
        }

        private void cleanInstantiatedClassesCount() {
            myInstantiatedClassesCount = 0;
        }

        private void processDelayedMethods() {
            RefClass[] instClasses = myInstantiatedClasses.toArray(new RefClass[0]);
            for (RefClass refClass : instClasses) {
                if (isClassInstantiated(refClass)) {
                    Set<RefMethod> methods = myClassIDtoMethods.get(refClass);
                    if (methods != null) {
                        RefMethod[] arMethods = methods.toArray(new RefMethod[0]);
                        for (RefMethod arMethod : arMethods) {
                            arMethod.accept(this);
                        }
                    }
                }
            }
        }
    }

    public List<EntryPoint> getExtensions() {
        return myExtensions;
    }

    public static String getDisplayNameText() {
        System.out.println("getDisplayNameText");
        return AnalysisBundle.message("inspection.dead.code.display.name");
    }

    public static void printName(String format, RefJavaElement refJavaElement) {

        String name = null;

        if (refJavaElement instanceof RefClassImpl) {
            name = RefClassImpl.class.cast(refJavaElement).getName();
        }
        else if (refJavaElement instanceof RefMethodImpl) {
            name =  RefMethodImpl.class.cast(refJavaElement).getName();
        }
        else if (refJavaElement instanceof RefFieldImpl) {
            name =  RefFieldImpl.class.cast(refJavaElement).getName();
        }
        else if (refJavaElement instanceof RefImplicitConstructorImpl) {
            name =  RefImplicitConstructorImpl.class.cast(refJavaElement).getName();
        } else {
            name = " empty";
        }

        System.out.println(String.format(format, name));
    }
}