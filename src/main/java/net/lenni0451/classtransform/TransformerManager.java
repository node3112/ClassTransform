package net.lenni0451.classtransform;

import net.lenni0451.classtransform.annotations.CInline;
import net.lenni0451.classtransform.annotations.CTarget;
import net.lenni0451.classtransform.annotations.CTransformer;
import net.lenni0451.classtransform.annotations.injection.CASM;
import net.lenni0451.classtransform.mappings.AMapper;
import net.lenni0451.classtransform.mappings.impl.VoidMapper;
import net.lenni0451.classtransform.targets.IInjectionTarget;
import net.lenni0451.classtransform.targets.impl.*;
import net.lenni0451.classtransform.transformer.*;
import net.lenni0451.classtransform.transformer.impl.*;
import net.lenni0451.classtransform.transformer.impl.general.InnerClassGeneralHandler;
import net.lenni0451.classtransform.transformer.impl.general.MemberCopyGeneralHandler;
import net.lenni0451.classtransform.transformer.impl.general.SyntheticMethodGeneralHandler;
import net.lenni0451.classtransform.utils.ASMUtils;
import net.lenni0451.classtransform.utils.FailStrategy;
import net.lenni0451.classtransform.utils.HotswapClassLoader;
import net.lenni0451.classtransform.utils.log.DefaultLogger;
import net.lenni0451.classtransform.utils.log.ILogger;
import net.lenni0451.classtransform.utils.tree.IClassProvider;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;

import java.lang.instrument.*;
import java.security.ProtectionDomain;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static net.lenni0451.classtransform.utils.ASMUtils.dot;

/**
 * The TransformerManager handles all things needed for class transformation.<br>
 * This class implements {@link ClassFileTransformer} so it can be used with an {@link Instrumentation} agent.
 */
public class TransformerManager implements ClassFileTransformer {

    private final IClassProvider classProvider;
    private final AMapper mapper;
    private final List<AnnotationHandler> annotationHandler = new ArrayList<>();
    private final Map<String, IInjectionTarget> injectionTargets = new HashMap<>();
    private ILogger logger = new DefaultLogger();
    private FailStrategy failStrategy = FailStrategy.EXIT;
    private Instrumentation instrumentation;
    private HotswapClassLoader hotswapClassLoader;

    private final List<IAnnotationHandlerPreprocessor> annotationHandlerPreprocessor = new ArrayList<>();
    private final List<IBytecodeTransformer> bytecodeTransformer = new ArrayList<>();
    private final Map<String, List<IRawTransformer>> rawTransformer = new HashMap<>();
    private final Map<String, List<ClassNode>> transformer = new HashMap<>();
    private final List<IPostTransformer> postTransformer = new ArrayList<>();

    private final Set<String> registeredTransformer = new HashSet<>();
    private final Set<String> transformedClasses = new HashSet<>();

    /**
     * @param classProvider The class provider used to get the class bytecode
     */
    public TransformerManager(final IClassProvider classProvider) {
        this(classProvider, new VoidMapper());
    }

    /**
     * @param classProvider The class provider used to get the class bytecode
     * @param mapper        The mapper used to remap ClassTransform annotation targets and transformers if enabled
     */
    public TransformerManager(final IClassProvider classProvider, final AMapper mapper) {
        this.classProvider = classProvider;
        this.mapper = mapper;
        this.mapper.load();

        //Annotation handler
        this.annotationHandler.add(new CASMAnnotationHandler(CASM.Shift.TOP));
        this.annotationHandler.add(new InnerClassGeneralHandler()); //Make inner classes public to allow access from the transformed class
        this.annotationHandler.add(new SyntheticMethodGeneralHandler()); //Rename synthetic members to be unique
        //HandlerPosition#PRE
        this.annotationHandler.add(new CShadowAnnotationHandler());
        this.annotationHandler.add(new COverrideAnnotationHandler());
        this.annotationHandler.add(new CWrapCatchAnnotationHandler());
        this.annotationHandler.add(new CInjectAnnotationHandler());
        this.annotationHandler.add(new CRedirectAnnotationHandler());
        this.annotationHandler.add(new CModifyConstantAnnotationHandler());
        //HandlerPosition#POST
        this.annotationHandler.add(new CInlineAnnotationHandler());
        this.annotationHandler.add(new CUpgradeAnnotationHandler());
        this.annotationHandler.add(new MemberCopyGeneralHandler()); //Copy all leftover members to the transformed class
        this.annotationHandler.add(new CASMAnnotationHandler(CASM.Shift.BOTTOM));

        //Injection targets
        this.injectionTargets.put("HEAD", new HeadTarget());
        this.injectionTargets.put("RETURN", new ReturnTarget());
        this.injectionTargets.put("THROW", new ThrowTarget());
        this.injectionTargets.put("TAIL", new TailTarget());
        this.injectionTargets.put("INVOKE", new InvokeTarget());
        this.injectionTargets.put("FIELD", new FieldTarget());
        this.injectionTargets.put("GETFIELD", new FieldTarget(Opcodes.GETFIELD, Opcodes.GETSTATIC));
        this.injectionTargets.put("PUTFIELD", new FieldTarget(Opcodes.PUTFIELD, Opcodes.PUTSTATIC));
        this.injectionTargets.put("NEW", new NewTarget());
        this.injectionTargets.put("OPCODE", new OpcodeTarget());
        this.injectionTargets.put("CONSTANT", new ConstantTarget());
    }

    /**
     * Set the logger used for printing infos, warnings and errors.
     *
     * @param logger The logger implementation to use
     */
    public void setLogger(final ILogger logger) {
        this.logger = logger;
    }

    /**
     * Set the fail strategy used when a transformer fails.
     *
     * @param failStrategy The fail strategy to use
     */
    public void setFailStrategy(final FailStrategy failStrategy) {
        this.failStrategy = failStrategy;
    }

    /**
     * Add an annotation handler preprocessor to the preprocessor list.<br>
     * You can modify class transform annotations before they get parsed.
     *
     * @param annotationHandlerPreprocessor The annotation handler preprocessor to add
     */
    public void addTransformerPreprocessor(final IAnnotationHandlerPreprocessor annotationHandlerPreprocessor) {
        this.annotationHandlerPreprocessor.add(annotationHandlerPreprocessor);
    }

    /**
     * Add a bytecode transformer to the transformer list.<br>
     * Bytecode transformer are the ClassTransform implementation of {@link ClassFileTransformer} .
     *
     * @param bytecodeTransformer The bytecode transformer to add
     */
    public void addBytecodeTransformer(final IBytecodeTransformer bytecodeTransformer) {
        this.bytecodeTransformer.add(bytecodeTransformer);
    }

    /**
     * Add a raw class transformer to the transformer list.<br>
     * Raw class transformer are similar to bytecode transformer but only for the specified class.<br>
     * A {@link ClassNode} is passed to the transformer instead of the bytecode array.
     *
     * @param className      The name of the class to transform
     * @param rawTransformer The raw transformer to add
     */
    public void addRawTransformer(final String className, final IRawTransformer rawTransformer) {
        this.rawTransformer.computeIfAbsent(className, n -> new ArrayList<>()).add(rawTransformer);
        this.transformedClasses.add(className);
        this.retransformClasses(Collections.singleton(className));
    }

    /**
     * Add a transformer class to the transformer list.<br>
     * Use the direct class name for a single transformer <i>(e.g. <b>package.Transformer</b>)</i><br>
     * Use the package ending with '*' for all transformer in the packet (not sub packages) <i>(e.g. <b>package.*</b>)</i><br>
     * Use the package ending with '**' for all transformer in the package and sub packages <i>(e.g. <b>package.**</b>)</i><br>
     * If the class is specified directly an exception will be thrown if the class is missing the {@link CTransformer} annotation.
     *
     * @param transformer The name of transformer class to add
     * @throws IllegalStateException If the class is specified directly and is missing the {@link CTransformer} annotation
     * @throws RuntimeException      If the class bytecode could not be parsed using ASM
     */
    public void addTransformer(final String transformer) {
        List<byte[]> classes = new ArrayList<>();
        boolean wildcard = false;
        if (transformer.endsWith(".**")) {
            wildcard = true;
            String packageName = transformer.substring(0, transformer.length() - 2);
            for (Map.Entry<String, Supplier<byte[]>> entry : this.classProvider.getAllClasses().entrySet()) {
                if (entry.getKey().startsWith(packageName)) classes.add(entry.getValue().get());
            }
        } else if (transformer.endsWith(".*")) {
            wildcard = true;
            String packageName = transformer.substring(0, transformer.length() - 1);
            for (Map.Entry<String, Supplier<byte[]>> entry : this.classProvider.getAllClasses().entrySet()) {
                if (entry.getKey().startsWith(packageName)) {
                    String classPackage = entry.getKey().substring(0, entry.getKey().lastIndexOf('.') + 1);
                    if (classPackage.equals(packageName)) classes.add(entry.getValue().get());
                }
            }
        } else {
            classes.add(this.classProvider.getClass(transformer));
        }
        for (byte[] bytecode : classes) {
            String name = null;
            try {
                ClassNode classNode = ASMUtils.fromBytes(bytecode);
                name = classNode.name;
                Set<String> transformedClasses = this.addTransformer(classNode, !wildcard);
                if (!transformedClasses.isEmpty()) this.retransformClasses(transformedClasses);
                else if (!wildcard) this.logger.warn("Transformer '%s' does not transform any classes", name);
            } catch (Throwable e) {
                if (name == null) throw new RuntimeException("Unable to parse transformer bytecode", e);
                else throw new RuntimeException("Unable to load transformer '" + name + "'", e);
            }
        }
    }

    /**
     * Add a {@link ClassNode} directly to the transformer list.<br>
     * The class must still be annotated with {@link CTransformer}.
     *
     * @param classNode The class node to add
     * @return A set of all transformed classes
     * @throws IllegalStateException If the class is missing the {@link CTransformer} annotation
     */
    public Set<String> addTransformer(final ClassNode classNode) {
        return this.addTransformer(classNode, true);
    }

    /**
     * Add a {@link ClassNode} directly to the transformer list.<br>
     * The class must still be annotated with {@link CTransformer}.
     *
     * @param classNode         The class node to add
     * @param requireAnnotation If an exception should be thrown if the class is missing the {@link CTransformer} annotation
     * @return A set of all transformed classes
     * @throws IllegalStateException If the class is missing the {@link CTransformer} annotation and {@code requireAnnotation} is {@code true}
     */
    public Set<String> addTransformer(final ClassNode classNode, final boolean requireAnnotation) {
        for (IAnnotationHandlerPreprocessor preprocessor : this.annotationHandlerPreprocessor) preprocessor.process(classNode);
        List<Object> annotation;
        if (classNode.invisibleAnnotations == null || (annotation = classNode.invisibleAnnotations.stream().filter(a -> a.desc.equals(Type.getDescriptor(CTransformer.class))).map(a -> a.values).findFirst().orElse(null)) == null) {
            if (requireAnnotation) throw new IllegalStateException("Transformer does not have CTransformer annotation");
            else return Collections.emptySet();
        }
        Set<String> transformedClasses = new HashSet<>();
        for (int i = 0; i < annotation.size(); i += 2) {
            String key = (String) annotation.get(i);
            Object value = annotation.get(i + 1);

            if (key.equals("value")) {
                List<Type> classesList = (List<Type>) value;
                for (Type type : classesList) this.addTransformer(transformedClasses, this.mapper.mapClassName(type.getClassName()), classNode);
            } else if (key.equals("name")) {
                List<String> classesList = (List<String>) value;
                for (String className : classesList) this.addTransformer(transformedClasses, this.mapper.mapClassName(className), classNode);
            }
        }
        this.transformedClasses.addAll(transformedClasses);

        String name = dot(classNode.name);
        this.registeredTransformer.add(name);
        if (this.hotswapClassLoader != null) this.hotswapClassLoader.defineHotswapClass(name);
        return transformedClasses;
    }

    private void addTransformer(final Set<String> transformedClasses, final String className, final ClassNode transformer) {
        List<ClassNode> transformerList = this.transformer.computeIfAbsent(className, n -> new ArrayList<>());
        transformerList.removeIf(cn -> cn.name.equals(transformer.name));
        transformerList.add(transformer);

        transformedClasses.add(className);
    }

    /**
     * Add a post transformer to handle the raw bytecode after all transformer have been applied.<br>
     * Useful for dumping transformed classes to disk.
     *
     * @param postTransformer The {@link BiConsumer} instance
     */
    public void addPostTransformConsumer(final IPostTransformer postTransformer) {
        this.postTransformer.add(postTransformer);
    }

    /**
     * Add a custom annotation handler into the handler chain.
     *
     * @param transformer     The annotation handler to add
     * @param handlerPosition The position where to add the handler
     */
    public void addCustomAnnotationHandler(final AnnotationHandler transformer, final HandlerPosition handlerPosition) {
        handlerPosition.add(this.annotationHandler, transformer);
    }

    /**
     * Add a new injection target for use with the {@link CTarget} annotation.
     *
     * @param name   The name of the injection target
     * @param target The injection target
     */
    public void addInjectionTarget(final String name, final IInjectionTarget target) {
        this.injectionTargets.put(name, target);
    }

    /**
     * Transform the bytecode of the given class.<br>
     * The name must be in the class format (e.g. {@code java.lang.String}).
     *
     * @param name     The name of the class
     * @param bytecode The bytecode of the class
     * @return The modified bytecode of the class or null if not changed
     */
    public byte[] transform(final String name, byte[] bytecode) {
        boolean transformed = false;
        ClassNode clazz = null;

        for (IBytecodeTransformer transformer : this.bytecodeTransformer) {
            byte[] transformedBytecode = transformer.transform(name, bytecode);
            if (transformedBytecode != null) {
                transformed = true;
                bytecode = transformedBytecode;
            }
        }

        List<IRawTransformer> rawTransformer = this.rawTransformer.get(name);
        if (rawTransformer != null) {
            clazz = ASMUtils.fromBytes(bytecode);
            for (IRawTransformer transformer : rawTransformer) clazz = transformer.transformer(this, clazz);
        }

        List<ClassNode> transformer = this.transformer.get(name);
        if (transformer != null) {
            if (clazz == null) clazz = ASMUtils.fromBytes(bytecode);
            for (ClassNode classNode : transformer) {
                try {
                    classNode = ASMUtils.cloneClass(classNode);
                    classNode = this.mapper.mapClass(this.classProvider, this.logger, clazz, classNode);
                } catch (Throwable t) {
                    this.logger.error("Failed to remap and fill annotation details of transformer '%s'", classNode.name, t);
                    if (FailStrategy.CANCEL.equals(this.failStrategy)) return null;
                    else if (FailStrategy.EXIT.equals(this.failStrategy)) System.exit(-1);
                }

                for (AnnotationHandler annotationHandler : this.annotationHandler) {
                    try {
                        annotationHandler.transform(this, this.classProvider, this.injectionTargets, clazz, classNode);
                    } catch (Throwable t) {
                        this.logger.error("Transformer '%s' failed to transform class '%s'", annotationHandler.getClass().getSimpleName(), clazz.name, t);
                        if (FailStrategy.CANCEL.equals(this.failStrategy)) return null;
                        else if (FailStrategy.EXIT.equals(this.failStrategy)) System.exit(-1);
                    }
                }
            }
        }

        if (clazz == null) {
            if (transformed) return bytecode;
            return null;
        }
        byte[] transformedBytecode = ASMUtils.toBytes(clazz, this.classProvider);
        for (IPostTransformer postTransformer : this.postTransformer) postTransformer.transform(name, transformedBytecode);
        return transformedBytecode;
    }

    /**
     * Hook an {@link Instrumentation} instance to allow for transformation using it.<br>
     * This allows to transform classes already loaded by the JVM.<br>
     * You have to be careful with re-transforming classes since you can't modify the class structure (e.g. adding a new method or modifying the signature of an existing one). You can use the {@link CInline} annotation to prevent adding methods to a loaded class.<br>
     * When using this method all loaded classes will be re-transformed if there is a transformer for them.<br>
     * Hotswapping transformer is disabled by default as it causes a bit more overhead and memory usage.
     *
     * @param instrumentation The instrumentation instance to hook
     */
    public void hookInstrumentation(final Instrumentation instrumentation) {
        this.hookInstrumentation(instrumentation, false);
    }

    /**
     * Hook an {@link Instrumentation} instance to allow for transformation using it.<br>
     * This allows to transform classes already loaded by the JVM.<br>
     * You have to be careful with re-transforming classes since you can't modify the class structure (e.g. adding a new method or modifying the signature of an existing one). You can use the {@link CInline} annotation to prevent adding methods to a loaded class.<br>
     * When using this method all loaded classes will be re-transformed if there is a transformer for them.
     *
     * @param instrumentation The instrumentation instance to hook
     * @param hotswappable    Whether to enable transformer hotswapping
     */
    public void hookInstrumentation(final Instrumentation instrumentation, final boolean hotswappable) {
        this.instrumentation = instrumentation;
        if (hotswappable) {
            this.hotswapClassLoader = new HotswapClassLoader(this.classProvider, this.logger);
            for (String transformerClass : this.registeredTransformer) this.hotswapClassLoader.defineHotswapClass(transformerClass);
        }
        instrumentation.addTransformer(this, instrumentation.isRetransformClassesSupported());

        this.retransformClasses(null);
    }

    private void retransformClasses(final Set<String> classesToRetransform) {
        if (this.instrumentation != null && this.instrumentation.isRetransformClassesSupported()) {
            List<Class<?>> classes = new ArrayList<>();
            Set<String> classSet;
            if (classesToRetransform != null) classSet = classesToRetransform;
            else classSet = this.transformedClasses;
            for (Class<?> loadedClass : this.instrumentation.getAllLoadedClasses()) {
                if (loadedClass != null && classSet.contains(loadedClass.getName())) classes.add(loadedClass);
            }
            if (!classes.isEmpty()) {
                try {
                    this.instrumentation.retransformClasses(classes.toArray(new Class[0]));
                } catch (Throwable t) {
                    this.logger.error("Failed to retransform classes '%s'", classes.stream().map(Class::getName).collect(Collectors.joining(", ")), t);
                    if (FailStrategy.EXIT.equals(this.failStrategy)) System.exit(-1);
                }
            }
        }
    }

    private void redefineClasses(final Set<String> classesToRedefine) throws UnmodifiableClassException, ClassNotFoundException {
        List<ClassDefinition> classDefinitions = new ArrayList<>();
        for (Class<?> loadedClass : this.instrumentation.getAllLoadedClasses()) {
            if (loadedClass != null && classesToRedefine.contains(loadedClass.getName())) {
                byte[] transformedBytecode = this.transform(loadedClass.getName(), this.classProvider.getClass(loadedClass.getName()));
                if (transformedBytecode != null) classDefinitions.add(new ClassDefinition(loadedClass, transformedBytecode));
            }
        }
        if (!classDefinitions.isEmpty()) this.instrumentation.redefineClasses(classDefinitions.toArray(new ClassDefinition[0]));
    }

    /**
     * Support method for hooking an instrumentation instance.<br>
     * You can simply add the TransformerManager as a transformer using {@link Instrumentation#addTransformer(ClassFileTransformer)} or call {@link TransformerManager#hookInstrumentation(Instrumentation)}.
     */
    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) throws IllegalClassFormatException {
        if (className == null) return null;
        try {
            className = dot(className);
            if (this.hotswapClassLoader != null && this.registeredTransformer.contains(className)) {
                try {
                    ClassNode transformer = ASMUtils.fromBytes(classfileBuffer);
                    Set<String> transformedClasses = this.addTransformer(transformer);
                    this.redefineClasses(transformedClasses);

                    return this.hotswapClassLoader.getHotswapClass(transformer.name);
                } catch (Throwable t) {
                    this.logger.error("Failed to hotswap transformer '%s'", className, t);
                    return new byte[]{1}; //Tells the IDE something went wrong
                }
            }

            byte[] newBytes = this.transform(className, classfileBuffer);
            if (newBytes != null) return newBytes;
        } catch (Throwable t) {
            this.logger.error("Failed to transform class '%s'", className, t);
            if (FailStrategy.EXIT.equals(this.failStrategy)) System.exit(-1);
        }
        return null;
    }

}
