package net.lenni0451.classtransform.transformer.impl;

import net.lenni0451.classtransform.InjectionCallback;
import net.lenni0451.classtransform.TransformerManager;
import net.lenni0451.classtransform.annotations.CLocalVariable;
import net.lenni0451.classtransform.annotations.CTarget;
import net.lenni0451.classtransform.annotations.injection.CInject;
import net.lenni0451.classtransform.exceptions.InvalidTargetException;
import net.lenni0451.classtransform.exceptions.TransformerException;
import net.lenni0451.classtransform.targets.IInjectionTarget;
import net.lenni0451.classtransform.transformer.types.RemovingTargetAnnotationHandler;
import net.lenni0451.classtransform.utils.ASMUtils;
import net.lenni0451.classtransform.utils.Codifier;
import net.lenni0451.classtransform.utils.Types;
import net.lenni0451.classtransform.utils.annotations.AnnotationParser;
import net.lenni0451.classtransform.utils.annotations.IParsedAnnotation;
import net.lenni0451.classtransform.utils.tree.IClassProvider;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.lang.reflect.Modifier;
import java.util.*;

import static net.lenni0451.classtransform.utils.Types.*;

/**
 * The annotation handler for the {@link CInject} annotation.
 */
public class CInjectAnnotationHandler extends RemovingTargetAnnotationHandler<CInject> {

    private final List<String> captureTargets = new ArrayList<>();

    public CInjectAnnotationHandler() {
        super(CInject.class, CInject::method);

        this.captureTargets.add("RETURN");
        this.captureTargets.add("TAIL");
        this.captureTargets.add("THROW");
    }

    @Override
    public void transform(CInject annotation, TransformerManager transformerManager, IClassProvider classProvider, Map<String, IInjectionTarget> injectionTargets, ClassNode transformedClass, ClassNode transformer, MethodNode transformerMethod, MethodNode target) {
        boolean hasArgs;
        boolean hasCallback;
        List<CLocalVariable> localVariables = new ArrayList<>();
        if (Modifier.isStatic(target.access) != Modifier.isStatic(transformerMethod.access)) {
            boolean isStatic = Modifier.isStatic(target.access);
            throw new TransformerException(transformerMethod, transformer, "must " + (isStatic ? "" : "not ") + "be static")
                    .help(Codifier.of(transformerMethod).access(isStatic ? transformerMethod.access | Modifier.STATIC : transformerMethod.access & ~Modifier.STATIC));
        }
        {
            Type[] arguments = argumentTypes(transformerMethod.desc);
            Type[] targetArguments = argumentTypes(target.desc);

            if (transformerMethod.invisibleParameterAnnotations != null) {
                List<AnnotationNode>[] annotations = transformerMethod.invisibleParameterAnnotations;
                if (annotations.length == arguments.length) {
                    for (int i = annotations.length - 1; i >= 0; i--) {
                        List<AnnotationNode> paramAnnotations = annotations[i];
                        if (paramAnnotations != null) {
                            for (AnnotationNode paramAnnotation : paramAnnotations) {
                                if (paramAnnotation.desc.equals(typeDescriptor(CLocalVariable.class))) {
                                    CLocalVariable localVariable = AnnotationParser.parse(CLocalVariable.class, classProvider, AnnotationParser.listToMap(paramAnnotation.values));
                                    localVariables.add(localVariable);
                                }
                            }
                        }
                    }
                    Collections.reverse(localVariables);
                    arguments = Arrays.copyOfRange(arguments, 0, arguments.length - localVariables.size());
                }
            }

            if (arguments.length == 0) {
                hasArgs = false;
                hasCallback = false;
            } else if (arguments.length == 1 && ASMUtils.compareType(arguments[0], type(InjectionCallback.class))) {
                hasArgs = false;
                hasCallback = true;
            } else if (ASMUtils.compareTypes(targetArguments, arguments)) {
                hasArgs = true;
                hasCallback = false;
            } else if (ASMUtils.compareTypes(targetArguments, arguments, false, type(InjectionCallback.class))) {
                hasArgs = true;
                hasCallback = true;
            } else {
                throw new TransformerException(transformerMethod, transformer, "must have the same arguments as target method or no arguments with optional InjectionCallback")
                        .help(Codifier.of(target).param(type(InjectionCallback.class)));
            }
        }
        if (!returnType(transformerMethod.desc).equals(Type.VOID_TYPE)) {
            throw new TransformerException(transformerMethod, transformer, "must have void return type")
                    .help(Codifier.of(target).returnType(Type.VOID_TYPE));
        }

        this.copyBackLocalVars(transformerMethod, localVariables);
        this.renameAndCopy(transformerMethod, target, transformer, transformedClass, "CInject");
        for (CTarget injectTarget : annotation.target()) {
            IInjectionTarget injectionTarget = injectionTargets.get(injectTarget.value().toUpperCase(Locale.ROOT));
            if (injectionTarget == null) throw new InvalidTargetException(transformerMethod, transformer, injectTarget.target(), injectionTargets.keySet());

            List<AbstractInsnNode> targetInstructions = injectionTarget.getTargets(injectionTargets, target, injectTarget, annotation.slice());
            CTarget.Shift shift = injectionTarget.getShift(injectTarget);
            if (targetInstructions == null) {
                throw new TransformerException(transformerMethod, transformer, "has invalid " + injectTarget.value() + " member declaration")
                        .help("e.g. Ljava/lang/String;toString()V, Ljava/lang/Integer;MAX_VALUE:I");
            }
            if (targetInstructions.isEmpty() && !injectTarget.optional()) {
                throw new TransformerException(transformerMethod, transformer, "target '" + injectTarget.value() + "' could not be found")
                        .help("e.g. Ljava/lang/String;toString()V, Ljava/lang/Integer;MAX_VALUE:I");
            }
            for (AbstractInsnNode instruction : targetInstructions) {
                InsnList instructions;

                if (this.captureTargets.contains(injectTarget.value().toUpperCase(Locale.ROOT))) {
                    instructions = this.getReturnInstructions(transformedClass, target, transformerMethod, localVariables, annotation.cancellable(), hasArgs, hasCallback);
                } else {
                    instructions = this.getCallInstructions(transformedClass, target, transformerMethod, localVariables, annotation.cancellable(), hasArgs, hasCallback);
                }

                if (shift == CTarget.Shift.BEFORE) target.instructions.insertBefore(instruction, instructions);
                else target.instructions.insert(instruction, instructions);
            }
        }
    }

    private void copyBackLocalVars(final MethodNode source, final List<CLocalVariable> localVariables) {
        int modifiableVariableCount = this.getModifiableVariableCount(localVariables);
        if (modifiableVariableCount <= 0) return;

        //Add Object[] to the args which is used to store the new value of the local variables
        Type returnType = returnType(source.desc);
        Type[] parameterTypes = argumentTypes(source.desc);
        Type[] newParameterTypes = new Type[parameterTypes.length + 1];
        System.arraycopy(parameterTypes, 0, newParameterTypes, 0, parameterTypes.length);
        newParameterTypes[newParameterTypes.length - 1] = type(Object[].class);
        source.desc = methodDescriptor(returnType, (Object[]) newParameterTypes);

        //Calculate the indices of the local variables
        List<Integer> localVariableIndices = new ArrayList<>();
        int varIndex = Modifier.isStatic(source.access) ? 0 : 1;
        for (Type parameterType : newParameterTypes) {
            localVariableIndices.add(varIndex);
            varIndex += parameterType.getSize();
        }
        //Set the unmodifiable local variables to -1 to filter them later
        for (int i = 0; i < localVariables.size(); i++) {
            if (!localVariables.get(i).modifiable()) localVariableIndices.set(newParameterTypes.length - localVariables.size() - 1 + i, -1);
        }
        //Make room for the Object[] in the local variable table
        for (AbstractInsnNode insn : source.instructions) {
            if (insn instanceof VarInsnNode) {
                VarInsnNode varInsn = (VarInsnNode) insn;
                if (varInsn.var >= localVariableIndices.get(localVariableIndices.size() - 1)) varInsn.var++;
            }
        }

        //Insert the values into the array
        InsnList createAndStore = new InsnList();
        int arrayIndex = 0;
        for (int i = localVariableIndices.size() - 1 - localVariables.size(); i < localVariableIndices.size() - 1; i++) {
            Type localVariableType = newParameterTypes[i];
            int localVariableIndex = localVariableIndices.get(i);
            if (localVariableIndex == -1) continue;

            createAndStore.add(new VarInsnNode(Opcodes.ALOAD, localVariableIndices.get(localVariableIndices.size() - 1)));
            createAndStore.add(ASMUtils.intPush(arrayIndex++));
            createAndStore.add(new VarInsnNode(ASMUtils.getLoadOpcode(localVariableType), localVariableIndex));
            if (Types.isPrimitive(localVariableType)) createAndStore.add(ASMUtils.getPrimitiveToObject(localVariableType));
            createAndStore.add(new InsnNode(Opcodes.AASTORE));
        }

        for (AbstractInsnNode insn : source.instructions.toArray()) {
            if ((insn.getOpcode() >= Opcodes.IRETURN && insn.getOpcode() <= Opcodes.RETURN) || insn.getOpcode() == Opcodes.ATHROW) {
                source.instructions.insertBefore(insn, ASMUtils.cloneInsnList(createAndStore));
            }
        }
    }

    private InsnList getCallInstructions(final ClassNode classNode, final MethodNode target, final MethodNode source, final List<CLocalVariable> localVariables, final boolean cancellable, final boolean hasArgs, final boolean hasCallback) {
        Type returnType = returnType(target.desc);
        int callbackVar = ASMUtils.getFreeVarIndex(target);

        InsnList instructions = this.getLoadInstructions(target, hasArgs);
        this.createCallback(instructions, cancellable, hasCallback, callbackVar, Type.VOID_TYPE, 0);
        this.callInjectionMethod(instructions, classNode, target, source, localVariables);
        this.getCancelInstructions(instructions, cancellable, hasCallback, callbackVar, returnType);
        return instructions;
    }

    private InsnList getReturnInstructions(final ClassNode classNode, final MethodNode target, final MethodNode source, final List<CLocalVariable> localVariables, final boolean cancellable, final boolean hasArgs, final boolean hasCallback) {
        Type returnType = returnType(target.desc);
        boolean isVoid = returnType.equals(Type.VOID_TYPE);
        int callbackVar = ASMUtils.getFreeVarIndex(target);
        //The return value has to be stored locally. We just take the callbackVar + 1 since callbackVar is the last element on the variable table
        int returnVar = callbackVar + 1;

        InsnList instructions = this.getLoadInstructions(target, hasArgs);
        this.createCallback(instructions, cancellable, hasCallback, callbackVar, returnType, returnVar);
        this.callInjectionMethod(instructions, classNode, target, source, localVariables);
        this.getCancelInstructions(instructions, cancellable, hasCallback, callbackVar, returnType);
        if (!isVoid && hasCallback) {
            instructions.insert(new VarInsnNode(ASMUtils.getStoreOpcode(returnType), returnVar)); //If the method is not a void, store the return value
            instructions.add(new VarInsnNode(ASMUtils.getLoadOpcode(returnType), returnVar));
        }
        return instructions;
    }

    private InsnList getLoadInstructions(final MethodNode methodNode, final boolean hasArgs) {
        if (!hasArgs) return new InsnList();
        InsnList instructions = new InsnList();
        Type[] parameter = argumentTypes(methodNode.desc);
        int index = Modifier.isStatic(methodNode.access) ? 0 : 1;
        for (Type type : parameter) {
            if (type.equals(Type.BOOLEAN_TYPE)) {
                instructions.add(new VarInsnNode(Opcodes.ILOAD, index));
                index++;
            } else if (type.equals(Type.BYTE_TYPE)) {
                instructions.add(new VarInsnNode(Opcodes.ILOAD, index));
                index++;
            } else if (type.equals(Type.SHORT_TYPE)) {
                instructions.add(new VarInsnNode(Opcodes.ILOAD, index));
                index++;
            } else if (type.equals(Type.CHAR_TYPE)) {
                instructions.add(new VarInsnNode(Opcodes.ILOAD, index));
                index++;
            } else if (type.equals(Type.INT_TYPE)) {
                instructions.add(new VarInsnNode(Opcodes.ILOAD, index));
                index++;
            } else if (type.equals(Type.LONG_TYPE)) {
                instructions.add(new VarInsnNode(Opcodes.LLOAD, index));
                index += 2;
            } else if (type.equals(Type.FLOAT_TYPE)) {
                instructions.add(new VarInsnNode(Opcodes.FLOAD, index));
                index++;
            } else if (type.equals(Type.DOUBLE_TYPE)) {
                instructions.add(new VarInsnNode(Opcodes.DLOAD, index));
                index += 2;
            } else {
                instructions.add(new VarInsnNode(Opcodes.ALOAD, index));
                index++;
            }
        }
        return instructions;
    }

    private void createCallback(final InsnList instructions, final boolean cancellable, final boolean hasCallback, final int callbackVar, final Type returnType, final int returnVar) {
        if (hasCallback) { //Create the callback instance
            instructions.add(new TypeInsnNode(Opcodes.NEW, internalName(InjectionCallback.class)));
            instructions.add(new InsnNode(Opcodes.DUP));
            instructions.add(new InsnNode(cancellable ? Opcodes.ICONST_1 : Opcodes.ICONST_0));
            if (!Type.VOID_TYPE.equals(returnType)) {
                instructions.add(new VarInsnNode(ASMUtils.getLoadOpcode(returnType), returnVar));
                AbstractInsnNode convertOpcode = ASMUtils.getPrimitiveToObject(returnType);
                if (convertOpcode != null) instructions.add(convertOpcode);
                instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, internalName(InjectionCallback.class), MN_Init, methodDescriptor(void.class, boolean.class, Object.class)));
            } else {
                instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, internalName(InjectionCallback.class), MN_Init, methodDescriptor(void.class, boolean.class)));
            }
            instructions.add(new VarInsnNode(Opcodes.ASTORE, callbackVar));
            instructions.add(new VarInsnNode(Opcodes.ALOAD, callbackVar));
        }
    }

    private void callInjectionMethod(final InsnList instructions, final ClassNode classNode, final MethodNode target, final MethodNode source, final List<CLocalVariable> localVariables) {
        boolean isInterface = Modifier.isInterface(classNode.access);

        InsnList loadLocals = new InsnList();
        InsnList postExecuteInstructions = new InsnList();
        if (!localVariables.isEmpty()) {
            List<Integer> storeBackVariableIndices = new ArrayList<>();
            int modifiableVariableCount = this.getModifiableVariableCount(localVariables);
            int parameterOffset = modifiableVariableCount > 0 ? 1 : 0;
            Type[] parameter = argumentTypes(source.desc);
            for (int i = 0; i < localVariables.size(); i++) {
                InsnList loadInsns = this.resolveLocalVariable(target, source, localVariables.get(i), parameter[parameter.length - parameterOffset - localVariables.size() + i], storeBackVariableIndices);
                loadLocals.add(loadInsns);
            }

            if (modifiableVariableCount > 0) {
                int arrayVar = ASMUtils.getFreeVarIndex(target) + 2;
                loadLocals.add(ASMUtils.intPush(modifiableVariableCount));
                loadLocals.add(new TypeInsnNode(Opcodes.ANEWARRAY, internalName(Object.class)));
                loadLocals.add(new InsnNode(Opcodes.DUP));
                loadLocals.add(new VarInsnNode(Opcodes.ASTORE, arrayVar));

                int arrayIndex = 0;
                for (int i = 0; i < localVariables.size(); i++) {
                    CLocalVariable localVariable = localVariables.get(i);
                    Type variable = parameter[parameter.length - 1 - localVariables.size() + i];
                    if (!localVariable.modifiable()) continue;

                    postExecuteInstructions.add(new VarInsnNode(Opcodes.ALOAD, arrayVar));
                    postExecuteInstructions.add(ASMUtils.intPush(arrayIndex++));
                    postExecuteInstructions.add(new InsnNode(Opcodes.AALOAD));
                    postExecuteInstructions.add(ASMUtils.getCast(variable));
                    postExecuteInstructions.add(new VarInsnNode(ASMUtils.getStoreOpcode(variable), storeBackVariableIndices.get(i)));
                }
            }
        }

        if (Modifier.isStatic(target.access)) {
            instructions.add(loadLocals);
            instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, classNode.name, source.name, source.desc, isInterface));
            instructions.add(postExecuteInstructions);
        } else {
            instructions.insert(new VarInsnNode(Opcodes.ALOAD, 0));
            instructions.add(loadLocals);
            instructions.add(new MethodInsnNode(isInterface ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL, classNode.name, source.name, source.desc, isInterface));
            instructions.add(postExecuteInstructions);
        }
    }

    private void getCancelInstructions(final InsnList instructions, final boolean cancellable, final boolean hasCallback, final int callbackVar, final Type returnType) {
        if (cancellable && hasCallback) { //If the method is cancellable, check if the callback has been cancelled
            //Get if the callback is cancelled
            LabelNode jump = new LabelNode();
            instructions.add(new VarInsnNode(Opcodes.ALOAD, callbackVar));
            instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, internalName(InjectionCallback.class), "isCancelled", methodDescriptor(boolean.class)));
            instructions.add(new JumpInsnNode(Opcodes.IFEQ, jump));
            if (!Type.VOID_TYPE.equals(returnType)) { //If the method has a return value, take the value from the callback
                instructions.add(new VarInsnNode(Opcodes.ALOAD, callbackVar));
                instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, internalName(InjectionCallback.class), "getReturnValue", methodDescriptor(Object.class)));
                instructions.add(ASMUtils.getCast(returnType));
                instructions.add(new InsnNode(ASMUtils.getReturnOpcode(returnType)));
            } else { //If the method is void, simply return
                instructions.add(new InsnNode(Opcodes.RETURN));
            }
            instructions.add(jump);
        }
    }

    private InsnList resolveLocalVariable(final MethodNode target, final MethodNode source, final CLocalVariable localVariable, final Type parameter, final List<Integer> storeBackVariableIndices) {
        IParsedAnnotation parsedAnnotation = (IParsedAnnotation) localVariable;
        if (parsedAnnotation.wasSet("name") == parsedAnnotation.wasSet("index")) throw new IllegalStateException("Local variable needs name or index");
        if (parsedAnnotation.wasSet("name") && target.localVariables == null) {
            throw new IllegalArgumentException("The target method does not have a local variable table. The variable name cannot be used to identify the variable index");
        }
        Integer varIndex = null;
        Integer loadOpcode = parsedAnnotation.wasSet("loadOpcode") ? localVariable.loadOpcode() : null;
        if (parsedAnnotation.wasSet("name")) {
            for (LocalVariableNode localVar : target.localVariables) {
                if (localVar.name.equals(localVariable.name())) {
                    varIndex = localVar.index;
                    break;
                }
            }
        } else {
            varIndex = localVariable.index();
        }
        if (varIndex == null) throw new IllegalStateException("Local variable could not be found by name");
        if (loadOpcode == null) {
            for (AbstractInsnNode instruction : target.instructions) {
                if (instruction instanceof VarInsnNode && ((VarInsnNode) instruction).var == varIndex) {
                    int newLoadOpcode = this.getLoadOpcode(instruction.getOpcode());
                    if (loadOpcode == null) loadOpcode = newLoadOpcode;
                    else if (loadOpcode != newLoadOpcode) throw new IllegalStateException("Local variable index conflicts with another variable type. Please define a load opcode");
                }
            }
        }
        if (loadOpcode == null) throw new IllegalStateException("Local variable type could not be found by index. Please define a load opcode");

        InsnList instructions = new InsnList();
        instructions.add(new VarInsnNode(loadOpcode, varIndex));
        if (parameter.equals(Type.BYTE_TYPE) && loadOpcode == Opcodes.ILOAD) instructions.add(new InsnNode(Opcodes.I2B));
        else if (parameter.equals(Type.SHORT_TYPE) && loadOpcode == Opcodes.ILOAD) instructions.add(new InsnNode(Opcodes.I2S));
        else if (parameter.equals(Type.CHAR_TYPE) && loadOpcode == Opcodes.ILOAD) instructions.add(new InsnNode(Opcodes.I2C));
        else if (loadOpcode == Opcodes.ALOAD) instructions.add(ASMUtils.getCast(parameter));
        storeBackVariableIndices.add(varIndex);
        return instructions;
    }

    private int getLoadOpcode(final int opcode) {
        switch (opcode) {
            case Opcodes.ISTORE:
                return Opcodes.ILOAD;
            case Opcodes.LSTORE:
                return Opcodes.LLOAD;
            case Opcodes.FSTORE:
                return Opcodes.FLOAD;
            case Opcodes.DSTORE:
                return Opcodes.DLOAD;
            case Opcodes.ASTORE:
                return Opcodes.ALOAD;
            case Opcodes.ILOAD:
            case Opcodes.LLOAD:
            case Opcodes.FLOAD:
            case Opcodes.DLOAD:
            case Opcodes.ALOAD:
                return opcode;
        }
        throw new IllegalStateException("Unknown opcode " + opcode);
    }

    private int getModifiableVariableCount(final List<CLocalVariable> localVariables) {
        int i = 0;
        for (CLocalVariable localVariable : localVariables) {
            if (localVariable.modifiable()) i++;
        }
        return i;
    }

}
