/*
 * Created on May 24, 2005
 *
 * @author Fabio Zadrozny
 */
package org.python.pydev.editor.codecompletion.revisited;

import java.io.File;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jface.text.IDocument;
import org.python.pydev.core.FullRepIterable;
import org.python.pydev.core.IModule;
import org.python.pydev.core.IModulesManager;
import org.python.pydev.core.IPythonNature;
import org.python.pydev.core.IToken;
import org.python.pydev.core.ModulesKey;
import org.python.pydev.core.ModulesKeyForZip;
import org.python.pydev.core.REF;
import org.python.pydev.core.docutils.StringUtils;
import org.python.pydev.editor.codecompletion.revisited.ModulesFoundStructure.ZipContents;
import org.python.pydev.editor.codecompletion.revisited.ModulesKeyTreeMap.Entry;
import org.python.pydev.editor.codecompletion.revisited.javaintegration.JythonModulesManagerUtils;
import org.python.pydev.editor.codecompletion.revisited.modules.AbstractModule;
import org.python.pydev.editor.codecompletion.revisited.modules.CompiledModule;
import org.python.pydev.editor.codecompletion.revisited.modules.EmptyModule;
import org.python.pydev.editor.codecompletion.revisited.modules.EmptyModuleForZip;
import org.python.pydev.editor.codecompletion.revisited.modules.SourceModule;
import org.python.pydev.plugin.PydevPlugin;

/**
 * This class manages the modules that are available
 * 
 * @author Fabio Zadrozny
 */
public abstract class ModulesManager implements IModulesManager, Serializable {

    private final static boolean DEBUG_BUILD = false;

    private final static boolean DEBUG_IO = false;
    
    private final static boolean DEBUG_ZIP = false;

    public ModulesManager() {
    }

    /**
     * This class is a cache to help in getting the managers that are referenced or referred.
     * 
     * It will not actually make any computations (the managers must be set from the outside)
     */
    protected static class CompletionCache {
        public ModulesManager[] referencedManagers;

        public ModulesManager[] referredManagers;

        public ModulesManager[] getManagers(boolean referenced) {
            if (referenced) {
                return this.referencedManagers;
            } else {
                return this.referredManagers;
            }
        }

        public void setManagers(ModulesManager[] ret, boolean referenced) {
            if (referenced) {
                this.referencedManagers = ret;
            } else {
                this.referredManagers = ret;
            }
        }
    }

    /**
     * A stack for keeping the completion cache
     */
    protected transient volatile CompletionCache completionCache = null;

    private transient volatile int completionCacheI = 0;

    /**
     * This method starts a new cache for this manager, so that needed info is kept while the request is happening
     * (so, some info may not need to be re-asked over and over for requests) 
     */
    public synchronized boolean startCompletionCache() {
        if (completionCache == null) {
            completionCache = new CompletionCache();
        }
        completionCacheI += 1;
        return true;
    }

    public synchronized void endCompletionCache() {
        completionCacheI -= 1;
        if (completionCacheI == 0) {
            completionCache = null;
        } else if (completionCacheI < 0) {
            throw new RuntimeException("Completion cache negative (request unsynched)");
        }
    }

    /**
     * Modules that we have in memory. This is persisted when saved.
     * 
     * Keys are ModulesKey with the name of the module. Values are AbstractModule objects.
     * 
     * Implementation changed to contain a cache, so that it does not grow to much (some out of memo errors
     * were thrown because of the may size when having too many modules).
     * 
     * It is sorted so that we can get things in a 'subtree' faster
     */
    protected transient ModulesKeyTreeMap<ModulesKey, ModulesKey> modulesKeys = new ModulesKeyTreeMap<ModulesKey, ModulesKey>();

    protected static transient ModulesManagerCache cache = createCache();

    private static ModulesManagerCache createCache() {
        return new ModulesManagerCache();
    }

    /**
     * This is the set of files that was found just right after unpickle (it should not be changed after that,
     * and serves only as a reference cache).
     */
    protected transient Set<File> files = new HashSet<File>();

    /**
     * Helper for using the pythonpath. Also persisted.
     */
    protected PythonPathHelper pythonPathHelper = new PythonPathHelper();

    public PythonPathHelper getPythonPathHelper() {
        return pythonPathHelper;
    }

    /**
     * The version for deserialization
     */
    private static final long serialVersionUID = 2L;

    /**
     * Custom deserialization is needed.
     */
    @SuppressWarnings("unchecked")
    private void readObject(ObjectInputStream aStream) throws IOException, ClassNotFoundException {
        modulesKeys = new ModulesKeyTreeMap<ModulesKey, ModulesKey>();

        files = new HashSet<File>();
        aStream.defaultReadObject();
        Set<ModulesKey> set = (Set<ModulesKey>) aStream.readObject();
        if (DEBUG_IO) {
            for (ModulesKey key : set) {
                System.out.println("Read:" + key);
            }
        }
        for (Iterator<ModulesKey> iter = set.iterator(); iter.hasNext();) {
            ModulesKey key = iter.next();
            //restore with empty modules.
            modulesKeys.put(key, key);
            if (key.file != null) {
                files.add(key.file);
            }
        }
    }

    /**
     * Custom serialization is needed.
     */
    private void writeObject(ObjectOutputStream aStream) throws IOException {
        synchronized (modulesKeys) {
            aStream.defaultWriteObject();
            //write only the keys
            HashSet<ModulesKey> set = new HashSet<ModulesKey>(modulesKeys.keySet());
            if (DEBUG_IO) {
                for (ModulesKey key : set) {
                    System.out.println("Write:" + key);
                }
            }
            aStream.writeObject(set);
        }
    }

    /**
     * @param modules The modules to set.
     */
    private void setModules(ModulesKeyTreeMap<ModulesKey, ModulesKey> keys) {
        this.modulesKeys = keys;
    }

    /**
     * @return Returns the modules.
     */
    protected Map<ModulesKey, AbstractModule> getModules() {
        throw new RuntimeException("Deprecated");
    }

    /**
     * Must be overriden so that the available builtins (forced or not) are returned.
     * @param defaultSelectedInterpreter 
     */
    public abstract String[] getBuiltins(String defaultSelectedInterpreter);

    /**
     * Change the pythonpath (used for both: system and project)
     * 
     * @param project: may be null
     * @param defaultSelectedInterpreter: may be null
     */
    public void changePythonPath(String pythonpath, final IProject project, IProgressMonitor monitor, String defaultSelectedInterpreter) {
        List<String> pythonpathList = pythonPathHelper.setPythonPath(pythonpath);
        ModulesFoundStructure modulesFound = pythonPathHelper.getModulesFoundStructure(pythonpathList, monitor);

        //now, on to actually filling the module keys
        ModulesKeyTreeMap<ModulesKey, ModulesKey> keys = new ModulesKeyTreeMap<ModulesKey, ModulesKey>();
        int j = 0;

        //now, create in memory modules for all the loaded files (empty modules).
        for (Iterator<Map.Entry<File, String>> iterator = modulesFound.regularModules.entrySet().iterator(); iterator.hasNext()
                && monitor.isCanceled() == false; j++) {
            Map.Entry<File, String> entry = iterator.next();
            File f = entry.getKey();
            String m = entry.getValue();

            if (j % 15 == 0) {
                //no need to report all the time (that's pretty fast now)
                monitor.setTaskName(new StringBuffer("Module resolved: ").append(m).toString());
                monitor.worked(1);
            }

            if (m != null) {
                //we don't load them at this time.
                ModulesKey modulesKey = new ModulesKey(m, f);

                //ok, now, let's resolve any conflicts that we might find...
                boolean add = false;

                //no conflict (easy)
                if (!keys.containsKey(modulesKey)) {
                    add = true;
                } else {
                    //we have a conflict, so, let's resolve which one to keep (the old one or this one)
                    if (PythonPathHelper.isValidSourceFile(f.getName())) {
                        //source files have priority over other modules (dlls) -- if both are source, there is no real way to resolve
                        //this priority, so, let's just add it over.
                        add = true;
                    }
                }

                if (add) {
                    //the previous must be removed (if there was any)
                    keys.remove(modulesKey);
                    keys.put(modulesKey, modulesKey);
                }
            }
        }
        
        for (ZipContents zipContents : modulesFound.zipContents) {
            if (monitor.isCanceled()) {
                break;
            }
            for (String filePathInZip : zipContents.foundFileZipPaths) {
                String modName = StringUtils.stripExtension(filePathInZip).replace('/', '.');
                if(DEBUG_ZIP){
                    System.out.println("Found in zip:"+modName);
                }
                ModulesKey k = new ModulesKeyForZip(modName, zipContents.zipFile, filePathInZip, true);
                keys.put(k, k);
                
                if(zipContents.zipContentsType == ZipContents.ZIP_CONTENTS_TYPE_JAR){
                    //folder modules are only created for jars (because for python files, the __init__.py is required).
                    for(String s:new FullRepIterable(FullRepIterable.getWithoutLastPart(modName))){ //the one without the last part was already added
                        k = new ModulesKeyForZip(s, zipContents.zipFile, s.replace('.', '/'), false);
                        keys.put(k, k);
                    }
                }
            }
        }

        onChangePythonpath(defaultSelectedInterpreter, keys);

        //assign to instance variable
        this.setModules(keys);

    }

    /**
     * Subclasses may do more things after the defaults were added to the cache (e.g.: the system modules manager may
     * add builtins)
     */
    protected void onChangePythonpath(String defaultSelectedInterpreter, SortedMap<ModulesKey, ModulesKey> keys) {
    }

    /**
     * This is the only method that should remove a module.
     * No other method should remove them directly.
     * 
     * @param key this is the key that should be removed
     */
    protected void doRemoveSingleModule(ModulesKey key) {
        synchronized (modulesKeys) {
            if (DEBUG_BUILD) {
                System.out.println("Removing module:" + key + " - " + this.getClass());
            }
            this.modulesKeys.remove(key);
            ModulesManager.cache.remove(key, this);
        }
    }

    /**
     * This method that actually removes some keys from the modules. 
     * 
     * @param toRem the modules to be removed
     */
    protected void removeThem(Collection<ModulesKey> toRem) {
        //really remove them here.
        for (Iterator<ModulesKey> iter = toRem.iterator(); iter.hasNext();) {
            doRemoveSingleModule(iter.next());
        }
    }

    public void removeModules(Collection<ModulesKey> toRem) {
        removeThem(toRem);
    }

    public void addModule(final ModulesKey key) {
        doAddSingleModule(key, new EmptyModule(key.name, key.file));
    }

    /**
     * This is the only method that should add / update a module.
     * No other method should add it directly (unless it is loading or rebuilding it).
     * 
     * @param key this is the key that should be added
     * @param n 
     */
    public void doAddSingleModule(final ModulesKey key, AbstractModule n) {
        if (DEBUG_BUILD) {
            System.out.println("Adding module:" + key + " - " + this.getClass());
        }
        synchronized (modulesKeys) {
            this.modulesKeys.put(key, key);
            ModulesManager.cache.add(key, n, this);
        }
    }

    /**
     * @return a set of all module keys
     */
    public Set<String> getAllModuleNames() {
        Set<String> s = new HashSet<String>();
        synchronized (modulesKeys) {
            for (ModulesKey key : this.modulesKeys.keySet()) {
                s.add(key.name);
            }
        }
        return s;
    }

    public SortedMap<ModulesKey, ModulesKey> getAllDirectModulesStartingWith(String strStartingWith) {
        if (strStartingWith.length() == 0) {
            return modulesKeys;
        }
        ModulesKey startingWith = new ModulesKey(strStartingWith, null);
        ModulesKey endingWith = new ModulesKey(startingWith + "z", null);
        synchronized (modulesKeys) {
            //we don't want it to be backed up by the same set (because it may be changed, so, we may get
            //a java.util.ConcurrentModificationException on places that use it)
            return new ModulesKeyTreeMap<ModulesKey, ModulesKey>(modulesKeys.subMap(startingWith, endingWith));
        }
    }

    public SortedMap<ModulesKey, ModulesKey> getAllModulesStartingWith(String strStartingWith) {
        return getAllDirectModulesStartingWith(strStartingWith);
    }

    public ModulesKey[] getOnlyDirectModules() {
        synchronized (modulesKeys) {
            return (ModulesKey[]) this.modulesKeys.keySet().toArray(new ModulesKey[0]);
        }
    }

    /**
     * @return
     */
    public int getSize() {
        return this.modulesKeys.size();
    }

    public IModule getModule(String name, IPythonNature nature, boolean dontSearchInit) {
        return getModule(true, name, nature, dontSearchInit);
    }

    /**
     * This method returns the module that corresponds to the path passed as a parameter.
     * 
     * @param name the name of the module we're looking for  (e.g.: mod1.mod2)
     * @param dontSearchInit is used in a negative form because initially it was isLookingForRelative, but
     * it actually defines if we should look in __init__ modules too, so, the name matches the old signature.
     * 
     * NOTE: isLookingForRelative description was: when looking for relative imports, we don't check for __init__
     * @return the module represented by this name
     */
    public IModule getModule(boolean acceptCompiledModule, String name, IPythonNature nature, boolean dontSearchInit) {
        AbstractModule n = null;
        ModulesKey keyForCacheAccess = new ModulesKey(null, null);

        if (!dontSearchInit) {
            if (n == null) {
                keyForCacheAccess.name = new StringBuffer(name).append(".__init__").toString();
                n = cache.getObj(keyForCacheAccess, this);
                if (n != null) {
                    name += ".__init__";
                }
            }
        }
        if (n == null) {
            keyForCacheAccess.name = name;
            n = cache.getObj(keyForCacheAccess, this);
        }

        if (n instanceof SourceModule) {
            //ok, module exists, let's check if it is synched with the filesystem version...
            SourceModule s = (SourceModule) n;
            if (!s.isSynched()) {
                //change it for an empty and proceed as usual.
                n = new EmptyModule(s.getName(), s.getFile());
                doAddSingleModule(createModulesKey(s.getName(), s.getFile()), n);
            }
        }

        if (n instanceof EmptyModule) {
            EmptyModule e = (EmptyModule) n;

            boolean found = false;

            if (!found && e.f != null) {
                
                if (!e.f.exists()) {
                    //if the file does not exist anymore, just remove it.
                    keyForCacheAccess.name = name;
                    keyForCacheAccess.file = e.f;
                    doRemoveSingleModule(keyForCacheAccess);
                    n = null;
                    
                    
                } else {
                    //file exists
                    
                    
                    //ok, handle case where the file is actually from a zip file...
                    if (e instanceof EmptyModuleForZip) {
                        EmptyModuleForZip emptyModuleForZip = (EmptyModuleForZip) e;
                        
                        if(emptyModuleForZip.pathInZip.endsWith(".class") || !emptyModuleForZip.isFile){
                            //handle java class... (if it's a class or a folder in a jar)
                            n = JythonModulesManagerUtils.createModuleFromJar(emptyModuleForZip);
                            
                        }else if(PythonPathHelper.isValidDll(emptyModuleForZip.pathInZip)){
                            //.pyd
                            n = new CompiledModule(name, IToken.TYPE_BUILTIN, nature.getAstManager());
                            
                        }else if(PythonPathHelper.isValidSourceFile(emptyModuleForZip.pathInZip)){
                            //handle python file from zip... we have to create it getting the contents from the zip file
                            try {
                                IDocument doc = REF.getDocFromZip(emptyModuleForZip.f, emptyModuleForZip.pathInZip);
                                n = AbstractModule.createModuleFromDoc(name, emptyModuleForZip.f, doc, nature, -1, false);
                                SourceModule zipModule = (SourceModule) n;
                                zipModule.zipFilePath = emptyModuleForZip.pathInZip;
                            } catch (Exception exc1) {
                                PydevPlugin.log(exc1);
                                n = null;
                            }
                        }
                        
                        
                    } else {
                        //regular case... just go on and create it.
                        try {
                            n = AbstractModule.createModule(name, e.f, nature, -1);
                        } catch (IOException exc) {
                            keyForCacheAccess.name = name;
                            keyForCacheAccess.file = e.f;
                            doRemoveSingleModule(keyForCacheAccess);
                            n = null;
                        }
                    }
                    
                    
                }

            } else { //ok, it does not have a file associated, so, we treat it as a builtin (this can happen in java jars)
                if (acceptCompiledModule) {
                    n = new CompiledModule(name, IToken.TYPE_BUILTIN, nature.getAstManager());
                } else {
                    return null;
                }
            }

            if (n != null) {
                doAddSingleModule(createModulesKey(name, e.f), n);
            } else {
                System.err.println("The module " + name + " could not be found nor created!");
            }
        }

        if (n instanceof EmptyModule) {
            throw new RuntimeException("Should not be an empty module anymore!");
        }
        return n;
    }

    private ModulesKey createModulesKey(String name, File f) {
        ModulesKey newEntry = new ModulesKey(name, f);
        Entry<ModulesKey, ModulesKey> oldEntry = this.modulesKeys.getEntry(newEntry);
        if(oldEntry != null){
            return oldEntry.getKey();
        }else{
            return newEntry;
        }
    }

    protected AbstractModule getBuiltinModule(String name, IPythonNature nature, boolean dontSearchInit) {
        //default implementation returns null (only the SystemModulesManager will actually return something here)
        return null;
    }

    /**
     * Passes through all the compiled modules in memory and clears its tokens (so that
     * we restore them when needed).
     */
    public void clearCache() {
        ModulesManager.cache.internalCache.clear();
    }

    /** 
     * @see org.python.pydev.core.IProjectModulesManager#isInPythonPath(org.eclipse.core.resources.IResource, org.eclipse.core.resources.IProject)
     */
    public boolean isInPythonPath(IResource member, IProject container) {
        return resolveModule(member, container) != null;
    }

    /** 
     * @see org.python.pydev.core.IProjectModulesManager#resolveModule(org.eclipse.core.resources.IResource, org.eclipse.core.resources.IProject)
     */
    public String resolveModule(IResource member, IProject container) {
        File inOs = member.getRawLocation().toFile();
        return resolveModule(REF.getFileAbsolutePath(inOs));
    }

    protected String getResolveModuleErr(IResource member) {
        return "Unable to find the path " + member + " in the project were it\n" + 
            "is added as a source folder for pydev." + this.getClass();
    }

    /**
     * @param full
     * @return
     */
    public String resolveModule(String full) {
        return pythonPathHelper.resolveModule(full, false);
    }

    public List<String> getPythonPath() {
        return pythonPathHelper.getPythonpath();
    }

}
