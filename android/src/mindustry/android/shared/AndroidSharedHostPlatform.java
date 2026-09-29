package mindustry.android.shared;

import android.content.*;
import android.os.*;
import arc.files.*;
import dalvik.system.*;
import mindustry.android.*;
import mindustry.core.*;

/** Android-safe headless Platform: Mods still need Dex/Rhino rather than desktop URLClassLoader semantics. */
final class AndroidSharedHostPlatform implements Platform{
    private final Context context;

    AndroidSharedHostPlatform(Context context){ this.context = context.getApplicationContext(); }

    @Override public rhino.Context getScriptContext(){ return AndroidRhinoContext.enter(context.getCacheDir()); }

    @Override public ClassLoader loadJar(Fi jar, ClassLoader parent) throws Exception{
        try{
            jar.file().setReadOnly();
            return dex(jar, parent);
        }catch(SecurityException error){
            if(Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) throw error;
            Fi cacheRoot = new Fi(context.getCacheDir()).child("shared-host-mods");
            Fi modRoot = cacheRoot.child(jar.nameWithoutExtension());
            Fi cached = modRoot.child(Long.toHexString(jar.lastModified()) + ".zip");
            if(!cached.exists() || cached.length() != jar.length()){
                modRoot.mkdirs();
                jar.copyTo(cached);
            }
            cached.file().setReadOnly();
            return dex(cached, parent);
        }
    }

    private ClassLoader dex(Fi jar, ClassLoader parent){
        return new DexClassLoader(jar.file().getPath(), context.getCodeCacheDir().getAbsolutePath(), null, parent){
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException{
                Class<?> loaded = findLoadedClass(name);
                if(loaded == null){
                    try{ loaded = findClass(name); }
                    catch(ClassNotFoundException | NoClassDefFoundError ignored){ return parent.loadClass(name); }
                }
                if(resolve) resolveClass(loaded);
                return loaded;
            }
        };
    }
}
