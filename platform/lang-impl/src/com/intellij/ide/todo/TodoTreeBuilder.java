/*
 * Copyright 2000-2015 JetBrains s.r.o.
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

package com.intellij.ide.todo;

import com.intellij.ide.highlighter.HighlighterFactory;
import com.intellij.ide.todo.nodes.TodoFileNode;
import com.intellij.ide.todo.nodes.TodoItemNode;
import com.intellij.ide.todo.nodes.TodoTreeHelper;
import com.intellij.ide.util.treeView.AbstractTreeNode;
import com.intellij.ide.util.treeView.NodeDescriptor;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.highlighter.EditorHighlighter;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.IndexNotReadyException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.vcs.FileStatusListener;
import com.intellij.openapi.vcs.FileStatusManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.search.PsiTodoSearchHelper;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtilCore;
import com.intellij.testFramework.LightVirtualFile;
import com.intellij.ui.tree.AsyncTreeModel;
import com.intellij.ui.tree.StructureTreeModel;
import com.intellij.usageView.UsageTreeColorsScheme;
import com.intellij.util.Processor;
import com.intellij.util.ui.tree.TreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.concurrency.Promise;
import org.jetbrains.concurrency.Promises;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import java.util.*;

import static com.intellij.ide.util.treeView.TreeState.expand;

/**
 * @author Vladimir Kondratyev
 */
public abstract class TodoTreeBuilder implements Disposable {
  private static final Logger LOG = Logger.getInstance("#com.intellij.ide.todo.TodoTreeBuilder");
  protected final Project myProject;

  /**
   * All files that have T.O.D.O items are presented as tree. This tree help a lot
   * to separate these files by directories.
   */
  protected final FileTree myFileTree;
  /**
   * This set contains "dirty" files. File is "dirty" if it's currently not unknown
   * whether the file contains T.O.D.O item or not. To determine this it's necessary
   * to perform some (perhaps, CPU expensive) operation. These "dirty" files are
   * validated in {@code validateCache()} method.
   */
  protected final HashSet<VirtualFile> myDirtyFileSet;

  protected final HashMap<VirtualFile, EditorHighlighter> myFile2Highlighter;

  protected final PsiTodoSearchHelper mySearchHelper;
  private final JTree myTree;
  /**
   * If this flag is false then the refresh() method does nothing. But when
   * the flag becomes true and myDirtyFileSet isn't empty the update is invoked.
   * This is done for optimization reasons: if TodoPane is not visible then
   * updates isn't invoked.
   */
  private boolean myUpdatable;

  /** Updates tree if containing files change VCS status. */
  private final MyFileStatusListener myFileStatusListener;
  private TodoTreeStructure myTreeStructure;
  private StructureTreeModel myModel;
  private boolean myDisposed;

  TodoTreeBuilder(JTree tree, Project project) {
    myTree = tree;
    myProject = project;

    myFileTree = new FileTree();
    myDirtyFileSet = new HashSet<>();

    myFile2Highlighter = new HashMap<>();

    PsiManager psiManager = PsiManager.getInstance(myProject);
    mySearchHelper = PsiTodoSearchHelper.SERVICE.getInstance(myProject);
    psiManager.addPsiTreeChangeListener(new MyPsiTreeChangeListener());

    myFileStatusListener = new MyFileStatusListener();

    //setCanYieldUpdate(true);
  }

  public StructureTreeModel getModel() {
    return myModel;
  }

  public void setModel(StructureTreeModel model) {
    myModel = model;
  }

  /**
   * Initializes the builder. Subclasses should don't forget to call this method after constructor has
   * been invoked.
   */
  public final void init() {
    myTreeStructure = createTreeStructure();
    myTreeStructure.setTreeBuilder(this);

    try {
      rebuildCache();
    }
    catch (IndexNotReadyException ignore) {}

    FileStatusManager.getInstance(myProject).addFileStatusListener(myFileStatusListener);
  }

  public boolean isDisposed() {
    return myDisposed;
  }

  @Override
  public final void dispose() {
    myDisposed = true;
    FileStatusManager.getInstance(myProject).removeFileStatusListener(myFileStatusListener);
  }

  final boolean isUpdatable() {
    return myUpdatable;
  }

  /**
   * Sets whether the builder updates the tree when data change.
   */
  final void setUpdatable(boolean updatable) {
    if (myUpdatable != updatable) {
      myUpdatable = updatable;
      if (updatable) {
        DumbService.getInstance(myProject).runWhenSmart(() -> updateTree());
      }
    }
  }

  @NotNull
  protected abstract TodoTreeStructure createTreeStructure();

  public final TodoTreeStructure getTodoTreeStructure() {
    return myTreeStructure;
  }

  Promise<?> performUpdate(Runnable runnable) {
    return myModel.getInvoker().runOrInvokeLater(() -> {
      if (!myDirtyFileSet.isEmpty()) { // suppress redundant cache validations
        DumbService.getInstance(myProject).runWhenSmart(() -> {
          validateCache();
          getTodoTreeStructure().validateCache();
        });
      }
      runnable.run();
    });
  }

  /**
   * @return read-only iterator of all current PSI files that can contain TODOs.
   *         Don't invoke its {@code remove} method. For "removing" use {@code markFileAsDirty} method.
   *         <b>Note, that {@code next()} method of iterator can return {@code null} elements.</b>
   *         These {@code null} elements correspond to the invalid PSI files (PSI file cannot be found by
   *         virtual file, or virtual file is invalid).
   *         The reason why we return such "dirty" iterator is the performance.
   */
  public Iterator<PsiFile> getAllFiles() {
    final Iterator<VirtualFile> iterator = myFileTree.getFileIterator();
    return new Iterator<PsiFile>() {
      @Override
      public boolean hasNext() {
        return iterator.hasNext();
      }

      @Override
      @Nullable public PsiFile next() {
        VirtualFile vFile = iterator.next();
        if (vFile == null || !vFile.isValid()) {
          return null;
        }
        PsiFile psiFile = PsiManager.getInstance(myProject).findFile(vFile);
        if (psiFile == null || !psiFile.isValid()) {
          return null;
        }
        return psiFile;
      }

      @Override
      public void remove() {
        throw new IllegalArgumentException();
      }
    };
  }

  /**
   * @return read-only iterator of all valid PSI files that can have T.O.D.O items
   *         and which are located under specified {@code psiDirectory}.
   * @see FileTree#getFiles(VirtualFile)
   */
  public Iterator<PsiFile> getFiles(PsiDirectory psiDirectory) {
    return getFiles(psiDirectory, true);
  }

  /**
   * @return read-only iterator of all valid PSI files that can have T.O.D.O items
   *         and which are located under specified {@code psiDirectory}.
   * @see FileTree#getFiles(VirtualFile)
   */
  public Iterator<PsiFile> getFiles(PsiDirectory psiDirectory, final boolean skip) {
    List<VirtualFile> files = myFileTree.getFiles(psiDirectory.getVirtualFile());
    List<PsiFile> psiFileList = new ArrayList<>(files.size());
    PsiManager psiManager = PsiManager.getInstance(myProject);
    for (VirtualFile file : files) {
      final Module module = ModuleUtilCore.findModuleForPsiElement(psiDirectory);
      if (module != null) {
        final boolean isInContent = ModuleRootManager.getInstance(module).getFileIndex().isInContent(file);
        if (!isInContent) continue;
      }
      if (file.isValid()) {
        PsiFile psiFile = psiManager.findFile(file);
        if (psiFile != null) {
          final PsiDirectory directory = psiFile.getContainingDirectory();
          if (directory == null || !skip || !TodoTreeHelper.getInstance(myProject).skipDirectory(directory)) {
            psiFileList.add(psiFile);
          }
        }
      }
    }
    return psiFileList.iterator();
  }

  /**
   * @return read-only iterator of all valid PSI files that can have T.O.D.O items
   *         and which are located under specified {@code psiDirectory}.
   * @see FileTree#getFiles(VirtualFile)
   */
  public Iterator<PsiFile> getFilesUnderDirectory(PsiDirectory psiDirectory) {
    List<VirtualFile> files = myFileTree.getFilesUnderDirectory(psiDirectory.getVirtualFile());
    List<PsiFile> psiFileList = new ArrayList<>(files.size());
    PsiManager psiManager = PsiManager.getInstance(myProject);
    for (VirtualFile file : files) {
      final Module module = ModuleUtilCore.findModuleForPsiElement(psiDirectory);
      if (module != null) {
        final boolean isInContent = ModuleRootManager.getInstance(module).getFileIndex().isInContent(file);
        if (!isInContent) continue;
      }
      if (file.isValid()) {
        PsiFile psiFile = psiManager.findFile(file);
        if (psiFile != null) {
          psiFileList.add(psiFile);
        }
      }
    }
    return psiFileList.iterator();
  }



  /**
    * @return read-only iterator of all valid PSI files that can have T.O.D.O items
    *         and which in specified {@code module}.
    * @see FileTree#getFiles(VirtualFile)
    */
   public Iterator<PsiFile> getFiles(Module module) {
    if (module.isDisposed()) return Collections.<PsiFile>emptyList().iterator();
    ArrayList<PsiFile> psiFileList = new ArrayList<>();
    final ProjectFileIndex fileIndex = ProjectRootManager.getInstance(myProject).getFileIndex();
    final VirtualFile[] contentRoots = ModuleRootManager.getInstance(module).getContentRoots();
    for (VirtualFile virtualFile : contentRoots) {
      List<VirtualFile> files = myFileTree.getFiles(virtualFile);
      PsiManager psiManager = PsiManager.getInstance(myProject);
      for (VirtualFile file : files) {
        if (fileIndex.getModuleForFile(file) != module) continue;
        if (file.isValid()) {
          PsiFile psiFile = psiManager.findFile(file);
          if (psiFile != null) {
            psiFileList.add(psiFile);
          }
        }
      }
    }
    return psiFileList.iterator();
   }


  /**
   * @return {@code true} if specified {@code psiFile} can contains too items.
   *         It means that file is in "dirty" file set or in "current" file set.
   */
  private boolean canContainTodoItems(PsiFile psiFile) {
    VirtualFile vFile = psiFile.getVirtualFile();
    return myFileTree.contains(vFile) || myDirtyFileSet.contains(vFile);
  }

  /**
   * Marks specified PsiFile as dirty. It means that file is being add into "dirty" file set.
   * It presents in current file set also but the next validateCache call will validate this
   * "dirty" file. This method should be invoked when any modifications inside the file
   * have happened.
   */
  private void markFileAsDirty(@NotNull PsiFile psiFile) {
    VirtualFile vFile = psiFile.getVirtualFile();
    if (vFile != null && !(vFile instanceof LightVirtualFile)) { // If PSI file isn't valid then its VirtualFile can be null
      myDirtyFileSet.add(vFile);
    }
  }

  void rebuildCache(){
    myFileTree.clear();
    myDirtyFileSet.clear();
    myFile2Highlighter.clear();

    collectFiles(virtualFile -> {
      myFileTree.add(virtualFile);
      return true;
    });
    getTodoTreeStructure().validateCache();
  }

  void collectFiles(Processor<? super VirtualFile> collector) {
    TodoTreeStructure treeStructure=getTodoTreeStructure();
    PsiFile[] psiFiles= mySearchHelper.findFilesWithTodoItems();
    for (PsiFile psiFile : psiFiles) {
      if (mySearchHelper.getTodoItemsCount(psiFile) > 0 && treeStructure.accept(psiFile)) {
        collector.process(psiFile.getVirtualFile());
      }
    }
  }

  void rebuildCache(Set<? extends VirtualFile> files){
    myFileTree.clear();
    myDirtyFileSet.clear();
    myFile2Highlighter.clear();

    for (VirtualFile virtualFile : files) {
      myFileTree.add(virtualFile);
    }

    getTodoTreeStructure().validateCache();
  }

  private void validateCache() {
    TodoTreeStructure treeStructure = getTodoTreeStructure();
    // First of all we need to update "dirty" file set.
    for (Iterator<VirtualFile> i = myDirtyFileSet.iterator(); i.hasNext();) {
      VirtualFile file = i.next();
      PsiFile psiFile = file.isValid() ? PsiManager.getInstance(myProject).findFile(file) : null;
      if (psiFile == null || !treeStructure.accept(psiFile)) {
        if (myFileTree.contains(file)) {
          myFileTree.removeFile(file);
          if (myFile2Highlighter.containsKey(file)) { // highlighter isn't needed any more
            myFile2Highlighter.remove(file);
          }
        }
      }
      else { // file is valid and contains T.O.D.O items
        myFileTree.removeFile(file);
        myFileTree.add(file); // file can be moved. remove/add calls move it to another place
        if (myFile2Highlighter.containsKey(file)) { // update highlighter text
          Document document = PsiDocumentManager.getInstance(myProject).getDocument(psiFile);
          EditorHighlighter highlighter = myFile2Highlighter.get(file);
          highlighter.setText(document.getCharsSequence());
        }
      }
      i.remove();
    }
    LOG.assertTrue(myDirtyFileSet.isEmpty());
    // Now myDirtyFileSet should be empty
  }

  /*@Override
  protected boolean isAutoExpandNode(NodeDescriptor descriptor) {
    return getTodoTreeStructure().isAutoExpandNode(descriptor);
  }

  @Override
  protected boolean isAlwaysShowPlus(NodeDescriptor nodeDescriptor) {
    final Object element= nodeDescriptor.getElement();
    if (element instanceof TodoItemNode){
      return false;
    } else if(element instanceof PsiFileNode) {
      try {
        return getTodoTreeStructure().mySearchHelper.getTodoItemsCount(((PsiFileNode)element).getValue()) > 0;
      }
      catch (IndexNotReadyException e) {
        return true;
      }
    }
    return true;
  }*/

  /**
   * @return first {@code SmartTodoItemPointer} that is the children (in depth) of the specified {@code element}.
   *         If {@code element} itself is a {@code TodoItem} then the method returns the {@code element}.
   */
  public TodoItemNode getFirstPointerForElement(@Nullable Object element) {
    if (element instanceof TodoItemNode) {
      return (TodoItemNode)element;
    }
    else if (element == null) {
      return null;
    }
    else {
      Object[] children = getTodoTreeStructure().getChildElements(element);
      if (children.length == 0) {
        return null;
      }
      Object firstChild = children[0];
      if (firstChild instanceof TodoItemNode) {
        return (TodoItemNode)firstChild;
      }
      else {
        return getFirstPointerForElement(firstChild);
      }
    }
  }

  /**
   * @return last {@code SmartTodoItemPointer} that is the children (in depth) of the specified {@code element}.
   *         If {@code element} itself is a {@code TodoItem} then the method returns the {@code element}.
   */
  public TodoItemNode getLastPointerForElement(Object element) {
    if (element instanceof TodoItemNode) {
      return (TodoItemNode)element;
    }
    else {
      Object[] children = getTodoTreeStructure().getChildElements(element);
      if (children.length == 0) {
        return null;
      }
      Object firstChild = children[children.length - 1];
      if (firstChild instanceof TodoItemNode) {
        return (TodoItemNode)firstChild;
      }
      else {
        return getLastPointerForElement(firstChild);
      }
    }
  }

  public final Promise<?> updateTree() {
    if (myUpdatable) {
      return performUpdate(() -> myModel.invalidate());
    }
    return Promises.resolvedPromise();
  }

  public void select(Object obj) {
    Object root = myTree.getModel().getRoot();
    TodoNodeVisitor visitor;
    if (obj instanceof TodoItemNode) {
      SmartTodoItemPointer value = ((TodoItemNode)obj).getValue();
      if (value != null) {
        visitor = new TodoNodeVisitor(value::getTodoItem,
                                      value.getTodoItem().getFile().getVirtualFile());
      }
      else {
        visitor = new TodoNodeVisitor(() -> unwrapUserObject(new TreePath(root)), null);
      }
    }
    else {
      Object o = obj instanceof AbstractTreeNode ? ((AbstractTreeNode)obj).getValue() : null;
      visitor = new TodoNodeVisitor(() -> obj instanceof AbstractTreeNode ? ((AbstractTreeNode)obj).getValue() : obj,
                                    o instanceof PsiElement ? PsiUtilCore.getVirtualFile((PsiElement)o) : null);
    }

    expand(myTree, promise -> 
      ((AsyncTreeModel)myTree.getModel())
        .accept(visitor)
        .onProcessed(path -> {
          if (!selectPath(myTree, path) && root != null) {
            //select root if path disappeared from the tree
            selectPath(myTree, new TreePath(new Object[] {root, ((DefaultMutableTreeNode)root).getChildAt(0)}));
          }
          promise.setResult(null);
        })
    );
  }

  private static boolean selectPath(@NotNull JTree tree, TreePath path) {
    if (path == null) return false;
    tree.expandPath(path); // request to expand found path
    TreeUtil.selectPath(tree, path); // select and scroll to center
    return true;
  }

  static PsiFile getFileForNode(DefaultMutableTreeNode node) {
    Object obj = node.getUserObject();
    if (obj instanceof TodoFileNode) {
      return ((TodoFileNode)obj).getValue();
    }
    else if (obj instanceof TodoItemNode) {
      SmartTodoItemPointer pointer = ((TodoItemNode)obj).getValue();
      return pointer.getTodoItem().getFile();
    }
    return null;
  }

  /**
   * Sets whether packages are shown or not.
   */
  void setShowPackages(boolean state) {
    getTodoTreeStructure().setShownPackages(state);
    rebuildTreeOnSettingChange();
  }

  /**
   * @param state if {@code true} then view is in "flatten packages" mode.
   */
  void setFlattenPackages(boolean state) {
    getTodoTreeStructure().setFlattenPackages(state);
    rebuildTreeOnSettingChange();
  }
  
  void setShowModules(boolean state) {
    getTodoTreeStructure().setShownModules(state);
    rebuildTreeOnSettingChange();
  }

  private void rebuildTreeOnSettingChange() {
    ArrayList<Object> pathsToSelect = new ArrayList<>();
    
    Object root = myTree.getModel().getRoot();
    if (root != null) {
      TreeUtil.collectSelectedPaths(myTree, new TreePath(root)).forEach(path -> pathsToSelect.add(unwrapUserObject(path)));
    }
    myTree.clearSelection();
    getTodoTreeStructure().validateCache();
    updateTree()
      .onSuccess(o -> {
        for (Object obj : pathsToSelect) {
          select(obj);
        }
      });
  }
  
  private static Object unwrapUserObject(TreePath path) {
    Object obj = path.getLastPathComponent();
    if (obj instanceof DefaultMutableTreeNode) {
      obj = ((DefaultMutableTreeNode)obj).getUserObject();
    }
    return obj;
  }

  /**
   * Sets new {@code TodoFilter}, rebuild whole the caches and immediately update the tree.
   *
   * @see TodoTreeStructure#setTodoFilter
   */
  void setTodoFilter(TodoFilter filter) {
    getTodoTreeStructure().setTodoFilter(filter);
    try {
      rebuildCache();
    }
    catch (IndexNotReadyException ignored) {}
    updateTree();
  }

  /**
   * @return next {@code TodoItem} for the passed {@code pointer}. Returns {@code null}
   *         if the {@code pointer} is the last t.o.d.o item in the tree.
   */
  public TodoItemNode getNextPointer(TodoItemNode pointer) {
    Object sibling = getNextSibling(pointer);
    if (sibling == null) {
      return null;
    }
    if (sibling instanceof TodoItemNode) {
      return (TodoItemNode)sibling;
    }
    else {
      return getFirstPointerForElement(sibling);
    }
  }

  /**
   * @return next sibling of the passed element. If there is no sibling then
   *         returns {@code null}.
   */
  Object getNextSibling(Object obj) {
    Object parent = getTodoTreeStructure().getParentElement(obj);
    if (parent == null) {
      return null;
    }
    Object[] children = getTodoTreeStructure().getChildElements(parent);
    Arrays.sort(children, (Comparator)MyComparator.ourInstance);
    int idx = -1;
    for (int i = 0; i < children.length; i++) {
      if (obj.equals(children[i])) {
        idx = i;
        break;
      }
    }
    if (idx == -1) {
      return null;
    }
    if (idx < children.length - 1) {
      return children[idx + 1];
    }
    // passed object is the last in the list. In this case we have to return first child of the
    // next parent's sibling.
    return getNextSibling(parent);
  }

  /**
   * @return next {@code SmartTodoItemPointer} for the passed {@code pointer}. Returns {@code null}
   *         if the {@code pointer} is the last t.o.d.o item in the tree.
   */
  public TodoItemNode getPreviousPointer(TodoItemNode pointer) {
    Object sibling = getPreviousSibling(pointer);
    if (sibling == null) {
      return null;
    }
    if (sibling instanceof TodoItemNode) {
      return (TodoItemNode)sibling;
    }
    else {
      return getLastPointerForElement(sibling);
    }
  }

  /**
   * @return previous sibling of the element of passed type. If there is no sibling then
   *         returns {@code null}.
   */
  Object getPreviousSibling(Object obj) {
    Object parent = getTodoTreeStructure().getParentElement(obj);
    if (parent == null) {
      return null;
    }
    Object[] children = getTodoTreeStructure().getChildElements(parent);
    Arrays.sort(children, (Comparator)MyComparator.ourInstance);
    int idx = -1;
    for (int i = 0; i < children.length; i++) {
      if (obj.equals(children[i])) {
        idx = i;

        break;
      }
    }
    if (idx == -1) {
      return null;
    }
    if (idx > 0) {
      return children[idx - 1];
    }
    // passed object is the first in the list. In this case we have to return last child of the
    // previous parent's sibling.
    return getPreviousSibling(parent);
  }

  /**
   * @return {@code SelectInEditorManager} for the specified {@code psiFile}. Highlighters are
   *         lazy created and initialized.
   */
  public EditorHighlighter getHighlighter(PsiFile psiFile, Document document) {
    VirtualFile file = psiFile.getVirtualFile();
    if (myFile2Highlighter.containsKey(file)) {
      return myFile2Highlighter.get(file);
    }
    else {
      EditorHighlighter highlighter = HighlighterFactory.createHighlighter(UsageTreeColorsScheme.getInstance().getScheme(), file.getName(), myProject);
      highlighter.setText(document.getCharsSequence());
      myFile2Highlighter.put(file, highlighter);
      return highlighter;
    }
  }

  public boolean isDirectoryEmpty(@NotNull PsiDirectory psiDirectory){
    return myFileTree.isDirectoryEmpty(psiDirectory.getVirtualFile());
  }
  
  protected static final class MyComparator implements Comparator<NodeDescriptor> {
    public static final Comparator<NodeDescriptor> ourInstance = new MyComparator();

    @Override
    public int compare(NodeDescriptor descriptor1, NodeDescriptor descriptor2) {
      int weight1 = descriptor1.getWeight();
      int weight2 = descriptor2.getWeight();
      if (weight1 != weight2) {
        return weight1 - weight2;
      }
      else {
        return descriptor1.getIndex() - descriptor2.getIndex();
      }
    }
  }

  private final class MyPsiTreeChangeListener extends PsiTreeChangeAdapter {
    @Override
    public void childAdded(@NotNull PsiTreeChangeEvent e) {
      // If local modification
      if (e.getFile() != null) {
        markFileAsDirty(e.getFile());
        updateTree();
        return;
      }
      // If added element if PsiFile and it doesn't contains TODOs, then do nothing
      PsiElement child = e.getChild();
      if (!(child instanceof PsiFile)) {
        return;
      }
      PsiFile psiFile = (PsiFile)e.getChild();
      markFileAsDirty(psiFile);
      updateTree();
    }

    @Override
    public void beforeChildRemoval(@NotNull PsiTreeChangeEvent e) {
      // local modification
      final PsiFile file = e.getFile();
      if (file != null) {
        markFileAsDirty(file);
        updateTree();
        return;
      }
      PsiElement child = e.getChild();
      if (child instanceof PsiFile) { // file will be removed
        PsiFile psiFile = (PsiFile)child;
        markFileAsDirty(psiFile);
        updateTree();
      }
      else if (child instanceof PsiDirectory) { // directory will be removed
        PsiDirectory psiDirectory = (PsiDirectory)child;
        for (Iterator<PsiFile> i = getAllFiles(); i.hasNext();) {
          PsiFile psiFile = i.next();
          if (psiFile == null) { // skip invalid PSI files
            continue;
          }
          if (PsiTreeUtil.isAncestor(psiDirectory, psiFile, true)) {
            markFileAsDirty(psiFile);
          }
        }
        updateTree();
      }
      else {
        if (PsiTreeUtil.getParentOfType(child, PsiComment.class, false) != null) { // change inside comment
          markFileAsDirty(child.getContainingFile());
          updateTree();
        }
      }
    }

    @Override
    public void childMoved(@NotNull PsiTreeChangeEvent e) {
      if (e.getFile() != null) { // local change
        markFileAsDirty(e.getFile());
        updateTree();
        return;
      }
      if (e.getChild() instanceof PsiFile) { // file was moved
        PsiFile psiFile = (PsiFile)e.getChild();
        if (!canContainTodoItems(psiFile)) { // moved file doesn't contain TODOs
          return;
        }
        markFileAsDirty(psiFile);
        updateTree();
      }
      else if (e.getChild() instanceof PsiDirectory) { // directory was moved. mark all its files as dirty.
        PsiDirectory psiDirectory = (PsiDirectory)e.getChild();
        boolean shouldUpdate = false;
        for (Iterator<PsiFile> i = getAllFiles(); i.hasNext();) {
          PsiFile psiFile = i.next();
          if (psiFile == null) { // skip invalid PSI files
            continue;
          }
          if (PsiTreeUtil.isAncestor(psiDirectory, psiFile, true)) {
            markFileAsDirty(psiFile);
            shouldUpdate = true;
          }
        }
        if (shouldUpdate) {
          updateTree();
        }
      }
    }

    @Override
    public void childReplaced(@NotNull PsiTreeChangeEvent e) {
      if (e.getFile() != null) {
        markFileAsDirty(e.getFile());
        updateTree();
      }
    }

    @Override
    public void childrenChanged(@NotNull PsiTreeChangeEvent e) {
      if (e.getFile() != null) {
        markFileAsDirty(e.getFile());
        updateTree();
      }
    }

    @Override
    public void propertyChanged(@NotNull PsiTreeChangeEvent e) {
      String propertyName = e.getPropertyName();
      if (propertyName.equals(PsiTreeChangeEvent.PROP_ROOTS)) { // rebuild all tree when source roots were changed
        myModel.getInvoker().runOrInvokeLater(
          () -> DumbService.getInstance(myProject).runWhenSmart(() -> rebuildCache())
        );
        updateTree();
      }
      else if (PsiTreeChangeEvent.PROP_WRITABLE.equals(propertyName) || PsiTreeChangeEvent.PROP_FILE_NAME.equals(propertyName)) {
        PsiFile psiFile = (PsiFile)e.getElement();
        if (!canContainTodoItems(psiFile)) { // don't do anything if file cannot contain to-do items
          return;
        }
        updateTree();
      }
      else if (PsiTreeChangeEvent.PROP_DIRECTORY_NAME.equals(propertyName)) {
        PsiDirectory psiDirectory = (PsiDirectory)e.getElement();
        Iterator<PsiFile> iterator = getFiles(psiDirectory);
        if (iterator.hasNext()) {
          updateTree();
        }
      }
    }
  }

  private final class MyFileStatusListener implements FileStatusListener {
    @Override
    public void fileStatusesChanged() {
      updateTree();
    }

    @Override
    public void fileStatusChanged(@NotNull VirtualFile virtualFile) {
      PsiFile psiFile = PsiManager.getInstance(myProject).findFile(virtualFile);
      if (psiFile != null && canContainTodoItems(psiFile)) {
        updateTree();
      }
    }
  }
}