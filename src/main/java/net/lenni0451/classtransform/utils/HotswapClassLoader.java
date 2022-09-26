package net.lenni0451.classtransform.utils;

import net.lenni0451.classtransform.utils.tree.IClassProvider;

import java.util.HashMap;
import java.util.Map;

public class HotswapClassLoader extends ClassLoader {

    private final IClassProvider classProvider;
    private final Map<String, byte[]> hotswapClasses;

    public HotswapClassLoader(final IClassProvider classProvider) {
        ClassLoader.registerAsParallelCapable();

        this.classProvider = classProvider;
        this.hotswapClasses = new HashMap<>();
    }

    public byte[] getHotswapClass(final String name) {
        return this.hotswapClasses.computeIfAbsent(name, n -> ASMUtils.toBytes(ASMUtils.createEmptyClass(n), this.classProvider));
    }

    public void defineHotswapClass(final String name) {
        if (this.hotswapClasses.containsKey(name)) return;
        try {
            byte[] classBytes = this.getHotswapClass(name);
            Class<?> clazz = this.defineClass(name, classBytes, 0, classBytes.length);
            clazz.getDeclaredConstructor().newInstance(); //Initialize the class
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

}