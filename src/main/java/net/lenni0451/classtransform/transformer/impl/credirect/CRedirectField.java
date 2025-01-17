package net.lenni0451.classtransform.transformer.impl.credirect;

import net.lenni0451.classtransform.exceptions.TransformerException;
import net.lenni0451.classtransform.utils.ASMUtils;
import net.lenni0451.classtransform.utils.Codifier;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.lang.reflect.Modifier;
import java.util.List;

import static net.lenni0451.classtransform.utils.Types.*;

/**
 * The redirect target transformer for fields.
 */
public class CRedirectField implements IRedirectTarget {

    @Override
    public void inject(ClassNode targetClass, MethodNode targetMethod, ClassNode transformer, MethodNode transformerMethod, List<AbstractInsnNode> targetNodes) {
        for (AbstractInsnNode instruction : targetNodes) {
            if (instruction.getOpcode() == Opcodes.GETSTATIC || instruction.getOpcode() == Opcodes.GETFIELD) {
                this.redirectGetField(targetClass, targetMethod, transformer, transformerMethod, (FieldInsnNode) instruction);
            } else if (instruction.getOpcode() == Opcodes.PUTSTATIC || instruction.getOpcode() == Opcodes.PUTFIELD) {
                this.redirectPutField(targetClass, targetMethod, transformer, transformerMethod, (FieldInsnNode) instruction);
            }
        }
    }

    private void redirectGetField(ClassNode targetClass, MethodNode targetMethod, ClassNode transformer, MethodNode transformerMethod, FieldInsnNode fieldInsnNode) {
        Type returnType = returnType(transformerMethod.desc);
        Type[] argumentTypes = argumentTypes(transformerMethod.desc);
        Type originalType = type(fieldInsnNode.desc);
        Type originalOwnerType = type(fieldInsnNode.owner);
        if (!ASMUtils.compareType(originalType, returnType)) {
            throw new TransformerException(transformerMethod, transformer, "does not have same return type as field")
                    .help(Codifier.of(transformerMethod).returnType(originalType));
        }
        if (fieldInsnNode.getOpcode() != Opcodes.GETSTATIC) {
            if (!ASMUtils.compareTypes(new Type[]{originalOwnerType}, argumentTypes)) {
                throw new TransformerException(transformerMethod, transformer, "does not have first argument type as field owner")
                        .help(Codifier.of(transformerMethod).param(null).param(originalOwnerType));
            }
        } else {
            if (argumentTypes.length != 0) {
                throw new TransformerException(transformerMethod, transformer, "does not have no arguments")
                        .help(Codifier.of(transformerMethod).param(null));
            }
        }

        if (!Modifier.isStatic(transformerMethod.access)) {
            targetMethod.instructions.insertBefore(fieldInsnNode, new VarInsnNode(Opcodes.ALOAD, 0));
        }
        if (fieldInsnNode.getOpcode() != Opcodes.GETSTATIC && !Modifier.isStatic(transformerMethod.access)) {
            targetMethod.instructions.insertBefore(fieldInsnNode, new InsnNode(Opcodes.SWAP));
        }
        if (Modifier.isStatic(transformerMethod.access)) {
            targetMethod.instructions.set(fieldInsnNode, new MethodInsnNode(Opcodes.INVOKESTATIC, targetClass.name, transformerMethod.name, transformerMethod.desc, Modifier.isInterface(targetClass.access)));
        } else {
            targetMethod.instructions.set(fieldInsnNode, new MethodInsnNode(Modifier.isInterface(targetClass.access) ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL, targetClass.name, transformerMethod.name, transformerMethod.desc));
        }
        if (!originalType.equals(returnType)) {
            targetMethod.instructions.insert(fieldInsnNode, new TypeInsnNode(Opcodes.CHECKCAST, originalType.getInternalName()));
        }
    }

    private void redirectPutField(ClassNode targetClass, MethodNode targetMethod, ClassNode transformer, MethodNode transformerMethod, FieldInsnNode fieldInsnNode) {
        Type returnType = returnType(transformerMethod.desc);
        Type[] argumentTypes = argumentTypes(transformerMethod.desc);
        Type originalType = type(fieldInsnNode.desc);
        Type originalOwnerType = type(fieldInsnNode.owner);
        if (!returnType.equals(Type.VOID_TYPE)) {
            throw new TransformerException(transformerMethod, transformer, "must be a void method")
                    .help(Codifier.of(transformerMethod).returnType(Type.VOID_TYPE));
        }
        if (fieldInsnNode.getOpcode() != Opcodes.PUTSTATIC) {
            if (!ASMUtils.compareTypes(new Type[]{originalOwnerType, originalType}, argumentTypes)) {
                throw new TransformerException(transformerMethod, transformer, "does not have owner and value as arguments")
                        .help(Codifier.of(transformerMethod).param(null).params(originalOwnerType, originalType));
            }
        } else {
            if (!ASMUtils.compareTypes(new Type[]{originalType}, argumentTypes)) {
                throw new TransformerException(transformerMethod, transformer, "does not have value as argument")
                        .help(Codifier.of(transformerMethod).param(null).param(originalType));
            }
        }

        int ownerStore = ASMUtils.getFreeVarIndex(targetMethod);
        int valueStore = ownerStore + 1;

        if (fieldInsnNode.getOpcode() == Opcodes.PUTFIELD) {
            targetMethod.instructions.insertBefore(fieldInsnNode, new VarInsnNode(ASMUtils.getStoreOpcode(originalType), valueStore));
            targetMethod.instructions.insertBefore(fieldInsnNode, new VarInsnNode(ASMUtils.getStoreOpcode(originalOwnerType), ownerStore));
        } else {
            targetMethod.instructions.insertBefore(fieldInsnNode, new VarInsnNode(ASMUtils.getStoreOpcode(originalType), valueStore));
        }
        if (!Modifier.isStatic(transformerMethod.access)) {
            targetMethod.instructions.insertBefore(fieldInsnNode, new VarInsnNode(Opcodes.ALOAD, 0));
        }
        if (fieldInsnNode.getOpcode() == Opcodes.PUTFIELD) {
            targetMethod.instructions.insertBefore(fieldInsnNode, new VarInsnNode(ASMUtils.getLoadOpcode(originalOwnerType), ownerStore));
        }
        targetMethod.instructions.insertBefore(fieldInsnNode, new VarInsnNode(ASMUtils.getLoadOpcode(originalType), valueStore));
        if (Modifier.isStatic(transformerMethod.access)) {
            targetMethod.instructions.set(fieldInsnNode, new MethodInsnNode(Opcodes.INVOKESTATIC, targetClass.name, transformerMethod.name, transformerMethod.desc, Modifier.isInterface(targetClass.access)));
        } else {
            targetMethod.instructions.set(fieldInsnNode, new MethodInsnNode(Modifier.isInterface(targetClass.access) ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL, targetClass.name, transformerMethod.name, transformerMethod.desc));
        }
    }

}
