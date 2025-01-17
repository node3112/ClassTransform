package net.lenni0451.classtransform.utils.loader;

import net.lenni0451.classtransform.TransformerManager;
import net.lenni0451.classtransform.utils.tree.IClassProvider;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.*;
import java.security.CodeSigner;
import java.security.CodeSource;
import java.util.*;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

import static net.lenni0451.classtransform.utils.ASMUtils.slash;

/**
 * Inject into classes by using a custom class loader.
 */
public class InjectionClassLoader extends URLClassLoader {

    private final TransformerManager transformerManager;
    private final ClassLoader parent;
    private final Set<String> protectedPackages = new HashSet<>();
    private final Map<String, byte[]> runtimeResources = new HashMap<>();

    private EnumLoaderPriority priority = EnumLoaderPriority.CUSTOM_FIRST;

    public InjectionClassLoader(final TransformerManager transformerManager, final URL... urls) {
        this(transformerManager, InjectionClassLoader.class.getClassLoader(), urls);
    }

    public InjectionClassLoader(final TransformerManager transformerManager, final ClassLoader parent, final URL... urls) {
        super(urls, null);
        this.transformerManager = transformerManager;
        this.parent = parent;

        this.protectedPackages.add("java.");
        this.protectedPackages.add("javax.");
        this.protectedPackages.add("sun.");
        this.protectedPackages.add("com.sun.");
        this.protectedPackages.add("jdk.");
    }

    @Override
    protected Class<?> loadClass(final String name, final boolean resolve) throws ClassNotFoundException {
        if (this.priority.equals(EnumLoaderPriority.PARENT_FIRST)) return super.loadClass(name, resolve);
        synchronized (this.getClassLoadingLock(name)) {
            Class<?> loadedClass = this.findLoadedClass(name);
            if (loadedClass == null) {
                try {
                    loadedClass = this.findClass(name);
                } catch (ClassNotFoundException t) {
                    if (this.parent == null) throw t;
                }

                if (loadedClass == null) loadedClass = this.parent.loadClass(name);
            }

            if (resolve) this.resolveClass(loadedClass);
            return loadedClass;
        }
    }

    @Override
    protected Class<?> findClass(final String name) throws ClassNotFoundException {
        for (String protectedPackage : this.protectedPackages) {
            if (name.startsWith(protectedPackage)) {
                if (this.priority.equals(EnumLoaderPriority.PARENT_FIRST) && this.parent != null) return this.parent.loadClass(name);
                throw new ClassNotFoundException(name);
            }
        }

        try {
            URLConnection connection = this.getClassConnection(name);
            byte[] classBytes = this.getClassBytes(name);
            CodeSigner[] codeSigner = null;

            String packageName;
            if (name.contains(".")) packageName = name.substring(0, name.lastIndexOf('.'));
            else packageName = "";
            if (connection instanceof JarURLConnection) {
                JarURLConnection jarConnection = (JarURLConnection) connection;
                JarFile jarFile = jarConnection.getJarFile();

                if (jarFile != null && jarFile.getManifest() != null) {
                    Manifest manifest = jarFile.getManifest();
                    JarEntry entry = jarFile.getJarEntry(this.getJarPath(name, connection.getURL()));

                    if (entry != null) codeSigner = entry.getCodeSigners();
                    Package pkg = this.getPackage(packageName);
                    if (pkg == null) {
                        this.definePackage(packageName, manifest, jarConnection.getJarFileURL());
                    } else {
                        if (pkg.isSealed() && !pkg.isSealed(jarConnection.getJarFileURL())) {
                            throw new SecurityException("sealing violation: package " + packageName + " is sealed");
                        } else if (this.isSealed(packageName, manifest)) {
                            throw new SecurityException("sealing violation: can't seal package " + packageName + ": already loaded");
                        }
                    }
                }
            } else {
                Package pkg = this.getPackage(packageName);
                if (pkg == null) {
                    this.definePackage(packageName, null, null, null, null, null, null, null);
                } else if (pkg.isSealed()) {
                    throw new SecurityException("sealing violation: package " + packageName + " is sealed");
                }
            }

            byte[] transformedClassBytes = this.transformerManager.transform(name, classBytes);
            if (transformedClassBytes != null) classBytes = transformedClassBytes;

            CodeSource codeSource = null;
            if (connection != null) codeSource = new CodeSource(connection.getURL(), codeSigner);
            return this.defineClass(name, classBytes, 0, classBytes.length, codeSource);
        } catch (IndexOutOfBoundsException | ClassNotFoundException | SecurityException | ClassFormatError e) {
            throw e;
        } catch (Throwable t) {
            throw new ClassNotFoundException(name, t);
        }
    }

    private URLConnection getClassConnection(final String className) throws IOException {
        URL url = this.findResource(slash(className) + ".class");
        if (url != null) return url.openConnection();
        return null;
    }

    private String getJarPath(final String className, final URL url) {
        // jar:file:/library.jar!/META-INF/versions/9/Test.class
        // /META-INF/versions/9/Test.class
        //TODO: JarFiles can not access entries in the META-INF folder
        // maybe find another way to get the code signers

        return slash(className) + ".class";
    }

    private byte[] getClassBytes(final String name) throws ClassNotFoundException, IOException {
        InputStream classStream = this.getResourceAsStream(slash(name) + ".class");
        if (classStream == null) throw new ClassNotFoundException(name);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        int len;

        while ((len = classStream.read(buf)) != -1) baos.write(buf, 0, len);
        classStream.close();

        return baos.toByteArray();
    }

    private boolean isSealed(final String path, final Manifest manifest) {
        Attributes attributes = manifest.getAttributes(path);
        String sealed = null;
        if (attributes != null) sealed = attributes.getValue(Attributes.Name.SEALED);

        if (sealed == null) {
            attributes = manifest.getMainAttributes();
            if (attributes != null) sealed = attributes.getValue(Attributes.Name.SEALED);
        }
        return "true".equalsIgnoreCase(sealed);
    }


    @Override
    public URL getResource(final String name) {
        if (this.runtimeResources.containsKey(name)) return this.findResource(name);
        return super.getResource(name);
    }

    @Override
    public URL findResource(final String name) {
        if (this.runtimeResources.containsKey(name)) {
            try {
                return new URL("x-buffer", null, -1, name, new BytesURLStreamHandler(this.runtimeResources.get(name)));
            } catch (MalformedURLException e) {
                throw new RuntimeException("This should never have happened", e);
            }
        }
        return super.findResource(name);
    }

    @Override
    public Enumeration<URL> findResources(final String name) throws IOException {
        URL resource = this.getResource(name);
        if (resource != null) return new URLEnumeration(resource);
        return super.findResources(name);
    }


    /**
     * Add a protected package to prevent the classes from being transformed.
     *
     * @param protectedPackage The package to protect
     */
    public void addProtectedPackage(final String protectedPackage) {
        this.protectedPackages.add(protectedPackage);
    }

    /**
     * @return The transformer manager used by this class loader
     */
    public TransformerManager getTransformerManager() {
        return this.transformerManager;
    }

    /**
     * Add an url to the classpath.
     *
     * @param url The url to add
     */
    public void addURL(final URL url) {
        super.addURL(url);
    }

    /**
     * Add a new resource to the classpath during runtime.
     *
     * @param path The path to the resource
     * @param data The data of the resource
     */
    public void addRuntimeResource(final String path, final byte[] data) {
        this.runtimeResources.put(path, data);
    }

    /**
     * Copy a resource from a class loader to the runtime resources.
     *
     * @param classLoader The class loader to copy the resource from
     * @param path        The path to the resource
     */
    public void copyResource(final ClassLoader classLoader, final String path) {
        try (InputStream is = classLoader.getResourceAsStream(path)) {
            if (is == null) throw new IllegalArgumentException("Resource not found: " + path);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int len;
            while ((len = is.read(buf)) != -1) baos.write(buf, 0, len);
            this.addRuntimeResource(path, baos.toByteArray());
        } catch (IOException e) {
            throw new IllegalArgumentException("Resource not found: " + path);
        }
    }

    /**
     * Copy a class to the runtime resources.
     *
     * @param classProvider The class provider to get the bytecode from
     * @param className     The name of the class to copy
     */
    public void copyClass(final IClassProvider classProvider, final String className) {
        byte[] classBytes = classProvider.getClass(className);
        this.addRuntimeResource(slash(className) + ".class", classBytes);
    }

    /**
     * Set the priority of the load class method.
     *
     * @param priority The priority
     */
    public void setPriority(final EnumLoaderPriority priority) {
        this.priority = priority;
    }

    /**
     * Execute the main method of the given class.<br>
     * The only argument of the main method has to be a {@code String[]}.
     *
     * @param className  The name of the class containing the main method
     * @param methodName The name of the main method
     * @param args       The arguments to pass to the main method
     * @throws ClassNotFoundException    If the class could not be found
     * @throws NoSuchMethodException     If the main method could not be found
     * @throws InvocationTargetException If the main method throws an exception
     * @throws IllegalAccessException    If the main method could not be accessed
     */
    public void executeMain(final String className, final String methodName, final String... args) throws ClassNotFoundException, NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        Thread.currentThread().setContextClassLoader(this);

        Class<?> mainClass = this.loadClass(className);
        Method method = mainClass.getMethod(methodName, String[].class);
        method.setAccessible(true);
        method.invoke(null, new Object[]{args});
    }

}
