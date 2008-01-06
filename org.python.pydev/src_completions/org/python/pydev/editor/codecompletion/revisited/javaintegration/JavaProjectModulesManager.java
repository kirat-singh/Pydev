package org.python.pydev.editor.codecompletion.revisited.javaintegration;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectNature;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jface.text.IDocument;
import org.python.pydev.core.FullRepIterable;
import org.python.pydev.core.IModule;
import org.python.pydev.core.IModulesManager;
import org.python.pydev.core.IProjectModulesManager;
import org.python.pydev.core.IPythonNature;
import org.python.pydev.core.ISystemModulesManager;
import org.python.pydev.core.ModulesKey;
import org.python.pydev.plugin.PydevPlugin;

/**
 * This class wraps a java project as we'd wrap a python project in a ProjectModulesManager, to give info on the 
 * modules available. 
 *
 * Alternative to find the package names:
 *             SearchableEnvironment s = j.newSearchableNameEnvironment(new ICompilationUnit[]{unit});
 *             s.findPackages("bar".toCharArray(), new ISearchRequestor(){
 * 
 *                 public void acceptPackage(char[] packageName) {
 *                     System.out.println("Accept package:"+new String(packageName));
 *                 }
 * 
 *                 public void acceptType(char[] packageName, char[] typeName, char[][] enclosingTypeNames, int modifiers,
 *                         AccessRestriction accessRestriction) {
 *                     System.out.println("Accept type:"+new String(packageName)+" / "+new String(typeName));
 *                 }});
 * End Alternative
 * 
 * Message about it: http://www.eclipse.org/newsportal/article.php?id=21742&group=eclipse.tools.jdt#21742
 * 
 * @author Fabio
 */
public class JavaProjectModulesManager implements IModulesManager, IProjectModulesManager {

    private static final String[] EMPTY_STRINTG_ARRAY = new String[0];
    
    /**
     * Flag indicating whether JDT is supported in this installation.
     */
    private static boolean JDTSupported = true;
    
    
    // DEBUG CONSTANTS
    private static final boolean DEBUG_GET_MODULE = false;
    
    private static final boolean DEBUG_GET_DIRECT_MODULES = false;


    /**
     * This method will check the given project and if it's a java project, will create a 
     * @param project
     * @return
     */
    public static IModulesManager createJavaProjectModulesManagerIfPossible(IProject project) {
        if(JDTSupported == false){
            return null;
        }
        
        try{
            if(project.isOpen()){
                IProjectNature nature = project.getNature(JavaCore.NATURE_ID);
                if(nature instanceof IJavaProject){
                    IJavaProject javaProject = (IJavaProject) nature;
                    return new JavaProjectModulesManager(javaProject);
                }
            }
        }catch(Throwable e){
            if(JythonModulesManagerUtils.isOptionalJDTClassNotFound(e)){
                //ignore it at this point: we don't have JDT... set the static variable to it and don't even
                //try to get to this point again (no need to log it or anything).
                JDTSupported = false;
                return null;
            }else{
                PydevPlugin.log(e);
            }
        }
        
        return null;
    }

    private IJavaProject javaProject;

    public JavaProjectModulesManager(IJavaProject javaProject) {
        this.javaProject = javaProject;
    }


    /**
     * @return a map with the modules keys for all the available modules that start with the passed token.
     */
    public SortedMap<ModulesKey, ModulesKey> getAllDirectModulesStartingWith(final String moduleToGetTokensFrom) {
        if(DEBUG_GET_DIRECT_MODULES){
            System.out.println("getAllDirectModulesStartingWith: "+moduleToGetTokensFrom);
        }
        final TreeMap<ModulesKey, ModulesKey> ret = new TreeMap<ModulesKey, ModulesKey>();
        
        filterJavaPackages(new IFilter(){
            
            public boolean accept(String elementName, IPackageFragmentRoot packageRoot, IJavaElement javaElement) {
                if(elementName.startsWith(moduleToGetTokensFrom)){
                    if(DEBUG_GET_DIRECT_MODULES){
                        System.out.println("getAllDirectModulesStartingWith: found:"+elementName);
                    }
                    
                    ModulesKeyForJava key = new ModulesKeyForJava(elementName, packageRoot, javaElement);
                    ret.put(key, key);
                }
                return false;
            }
            
        });
        
        return ret;
    }

    /**
     * @return a set with all the module names contained in this modules manager (only in this modules manager,
     * as the addDependencies should never be true in this implementation).
     */
    public Set<String> getAllModuleNames(boolean addDependencies, final String partStartingWithLowerCase) {
        if(addDependencies){
            throw new RuntimeException("At this point, it should never be called with dependencies " +
            		"(because it's a java project already -- it manages that internally already)");
        }
        
        return filterJavaPackages(new IFilter(){

            public boolean accept(String elementName, IPackageFragmentRoot packageRoot, IJavaElement javaElement) {
                for (String mod : FullRepIterable.dotSplit(elementName)) {
                    if(mod.startsWith(partStartingWithLowerCase)){
                        return true;
                    }
                }
                return false;
            }

        });
        
    }


    /**
     * Interface to be passed to filter a java package.
     *
     * @author Fabio
     */
    public static interface IFilter{
        /**
         * @param elementName the name of the element (same as javaElement.getElementName())
         * @param packageRoot the java package where the element is contained
         * @param javaElement the java element 
         * 
         * @return true if the element should be added and false otherwise.
         */
        public boolean accept(String elementName, IPackageFragmentRoot packageRoot, IJavaElement javaElement);
    }
    
    
    /**
     * This method passes through all the java packages and calls the filter callback passed to choose
     * if it should be accepted or not. 
     */
    private Set<String> filterJavaPackages(IFilter filter) {
        HashSet<String> ret = new HashSet<String>();
        IClasspathEntry[] rawClasspath;
        try {
            rawClasspath = this.javaProject.getRawClasspath();
            for(IClasspathEntry entry:rawClasspath){
                int entryKind = entry.getEntryKind();
                IClasspathEntry resolvedClasspathEntry = JavaCore.getResolvedClasspathEntry(entry);
                if(entryKind != IClasspathEntry.CPE_CONTAINER){
                    //ignore if it's in the system classpath...
                    IPackageFragmentRoot[] roots = javaProject.findPackageFragmentRoots(resolvedClasspathEntry);
                    for (IPackageFragmentRoot root : roots) {
                        IJavaElement[] children = root.getChildren();
                        for(IJavaElement child:children){
                            String elementName = child.getElementName();
                            
                            if(filter.accept(elementName, root, child)){
                                ret.add(elementName);
                            }
                            
                        }
                    }
                }
            }
            return ret;
        } catch (JavaModelException e) {
            throw new RuntimeException(e);
        }
    }
        


    public String[] getBuiltins() {
        return EMPTY_STRINTG_ARRAY;
    }

    public List<String> getCompletePythonPath(String interpreter) {
        return new ArrayList<String>();
    }

    public IModule getModule(String name, IPythonNature nature, boolean dontSearchInit) {
        throw new RuntimeException("Not implemented");
    }

    public IModule getModule(String name, IPythonNature nature, boolean checkSystemManager, boolean dontSearchInit) {
        throw new RuntimeException("Not implemented");
    }

    public IPythonNature getNature() {
        throw new RuntimeException("Not implemented");
    }

    public ModulesKey[] getOnlyDirectModules() {
        throw new RuntimeException("Not implemented");
    }

    public Object getPythonPathHelper() {
        return null;
    }

    public IModule getRelativeModule(String name, IPythonNature nature) {
        throw new RuntimeException("Not implemented");
    }

    public int getSize(boolean addDependenciesSize) {
        throw new RuntimeException("Not implemented");
    }

    public ISystemModulesManager getSystemModulesManager() {
        throw new RuntimeException("Not implemented");
    }

    public boolean isInPythonPath(IResource member, IProject container) {
        throw new RuntimeException("Not implemented");
    }

    public String resolveModule(IResource member, IProject container) {
        throw new RuntimeException("Not implemented");
    }

    public String resolveModule(String full) {
        throw new RuntimeException("Not implemented");
    }

    public String resolveModule(String full, boolean checkSystemManager) {
        throw new RuntimeException("Not implemented");
    }

    public void setPythonNature(IPythonNature nature) {
        throw new RuntimeException("Not implemented");
    }

    public boolean startCompletionCache() {
        throw new RuntimeException("Not implemented");
    }
    
    public void endCompletionCache() {
        throw new RuntimeException("Not implemented");
    }

    public void endProcessing() {
        throw new RuntimeException("Not implemented");
    }

    public IModule getModuleInDirectManager(String name, IPythonNature nature, boolean dontSearchInit) {
        if(DEBUG_GET_MODULE){
            System.out.println("Trying to get module in java project modules manager: "+name);
        }
        try {
            IType type = this.javaProject.findType(name);
            if(DEBUG_GET_MODULE){
                System.out.println("Found: "+type);
            }
            
            if(type != null){
                
                //now, there's a catch here, we'll find any class in the project classpath, even if it's in the 
                //global classpath (e.g.: rt.jar), and this shouldn't be treated in this project modules manager
                //(that's treated in the Jython system manager)
                IJavaElement ancestor = type.getAncestor(IJavaElement.PACKAGE_FRAGMENT_ROOT);
                if(ancestor instanceof IPackageFragmentRoot){
                    IPackageFragmentRoot packageFragmentRoot = (IPackageFragmentRoot) ancestor;
                    IClasspathEntry rawClasspathEntry = packageFragmentRoot.getRawClasspathEntry();
                    if(rawClasspathEntry.getEntryKind() == IClasspathEntry.CPE_CONTAINER){
                        return null;
                    }
                }
                return new JavaClassModuleInProject(name, this.javaProject);
            }
        } catch (JavaModelException e) {
            throw new RuntimeException(e);
        }
        return null;
    }


    public String resolveModuleInDirectManager(IFile file) {
        throw new RuntimeException("Not implemented");
    }

    public String resolveModuleInDirectManager(String full) {
        return null;
    }

    //------------------------------------------------------------------------------------------------------------------
    //the methods below are not actually implemented for a java project (as they aren't really applicable)
    //------------------------------------------------------------------------------------------------------------------
    
    public SortedMap<ModulesKey, ModulesKey> getAllModulesStartingWith(String moduleToGetTokensFrom) {
        throw new RuntimeException("Not implemented"); //should never be called (this modules manager is inside another one that should handle it)
    }

    public IModule addModule(ModulesKey key) {
        throw new RuntimeException("Not implemented");
    }
    
    public void changePythonPath(String pythonpath, IProject project, IProgressMonitor monitor, String defaultSelectedInterpreter) {
        throw new RuntimeException("Not implemented");
    }
    
    public void removeModules(Collection<ModulesKey> toRem) {
        throw new RuntimeException("Not implemented");
    }
    
    public void processDelete(ModulesKey key) {
        throw new RuntimeException("Not implemented");
    }
    
    public void processInsert(ModulesKey key) {
        throw new RuntimeException("Not implemented");
    }
    
    public void processUpdate(ModulesKey data) {
        throw new RuntimeException("Not implemented");
    }
    
    public void rebuildModule(File f, IDocument doc, IProject project, IProgressMonitor monitor, IPythonNature nature) {
        throw new RuntimeException("Not implemented");
    }
    
    public void removeModule(File file, IProject project, IProgressMonitor monitor) {
        throw new RuntimeException("Not implemented");
    }
    
    public void setProject(IProject project, boolean restoreDeltas) {
        throw new RuntimeException("Not implemented");
    }

}
