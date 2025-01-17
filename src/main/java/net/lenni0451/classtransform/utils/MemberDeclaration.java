package net.lenni0451.classtransform.utils;

import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

/**
 * A wrapper for owner, name and descriptor of a method or field.
 */
public class MemberDeclaration {

    private final String owner;
    private final String name;
    private final String desc;

    public MemberDeclaration(final String owner, final String name, final String desc) {
        this.owner = owner;
        this.name = name;
        this.desc = desc;
    }

    /**
     * @return The owner of the method or field
     */
    public String getOwner() {
        return this.owner;
    }

    /**
     * @return The name of the method or field
     */
    public String getName() {
        return this.name;
    }

    /**
     * @return The descriptor of the method or field
     */
    public String getDesc() {
        return this.desc;
    }


    /**
     * Check if a {@link FieldInsnNode} references the same field as this declaration.
     *
     * @param fieldInsnNode The node to check
     * @return If the node references the same field as this declaration
     */
    public boolean is(final FieldInsnNode fieldInsnNode) {
        return this.owner.equals(fieldInsnNode.owner) && this.name.equals(fieldInsnNode.name) && this.desc.equals(fieldInsnNode.desc);
    }

    /**
     * Check if a {@link MethodInsnNode} references the same method as this declaration.
     *
     * @param methodInsnNode The node to check
     * @return If the node references the same method as this declaration
     */
    public boolean is(final MethodInsnNode methodInsnNode) {
        return this.owner.equals(methodInsnNode.owner) && this.name.equals(methodInsnNode.name) && this.desc.equals(methodInsnNode.desc);
    }

    /**
     * @return If this declaration references a field
     */
    public boolean isFieldMapping() {
        return !this.desc.startsWith("(");
    }

}
