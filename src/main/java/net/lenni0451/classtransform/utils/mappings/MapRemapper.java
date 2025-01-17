package net.lenni0451.classtransform.utils.mappings;

import org.objectweb.asm.commons.Remapper;

import java.util.*;

/**
 * An asm remapper implementation using a map to store the mappings.
 */
public class MapRemapper extends Remapper {

    private final Map<String, String> mappings;
    private MapRemapper reverse;

    public MapRemapper() {
        this(new HashMap<>());
    }

    public MapRemapper(final String oldName, final String newName) {
        this();
        this.mappings.put(oldName, newName);
    }

    public MapRemapper(final Map<String, String> mappings) {
        this.mappings = mappings;
    }

    public Map<String, String> getMappings() {
        return Collections.unmodifiableMap(this.mappings);
    }

    /**
     * Add a class mapping to the remapper.<br>
     * Class names need to be with '/' instead of '.'.
     *
     * @param from The old name of the class
     * @param to   The new name of the class
     */
    public void addClassMapping(final String from, final String to) {
        this.addClassMapping(from, to, false);
    }

    /**
     * Add a class mapping to the remapper.<br>
     * Class names need to be with '/' instead of '.'.
     *
     * @param from         The old name of the class
     * @param to           The new name of the class
     * @param skipIfExists If the mapping should be skipped if it already exists
     */
    public void addClassMapping(final String from, final String to, final boolean skipIfExists) {
        if (skipIfExists && this.mappings.containsKey(from)) return;
        this.mappings.put(from, to);

        if (this.reverse != null) {
            this.reverse.reverse = null;
            this.reverse = null;
        }
    }

    /**
     * Add a method mapping to the remapper.
     *
     * @param owner  The owner of the method
     * @param name   The old name of the method
     * @param desc   The descriptor of the method
     * @param target The new name of the method
     */
    public void addMethodMapping(final String owner, final String name, final String desc, final String target) {
        this.addMethodMapping(owner, name, desc, target, false);
    }

    /**
     * Add a method mapping to the remapper.
     *
     * @param owner        The owner of the method
     * @param name         The old name of the method
     * @param desc         The descriptor of the method
     * @param target       The new name of the method
     * @param skipIfExists If the mapping should be skipped if it already exists
     */
    public void addMethodMapping(final String owner, final String name, final String desc, final String target, final boolean skipIfExists) {
        String key = owner + "." + name + desc;
        if (skipIfExists && this.mappings.containsKey(key)) return;
        this.mappings.put(key, target);

        if (this.reverse != null) {
            this.reverse.reverse = null;
            this.reverse = null;
        }
    }

    /**
     * Add a field mapping to the remapper without a descriptor.
     *
     * @param owner  The owner of the field
     * @param name   The old name of the field
     * @param target The new name of the field
     */
    public void addFieldMapping(final String owner, final String name, final String target) {
        this.addFieldMapping(owner, name, target, false);
    }

    /**
     * Add a field mapping to the remapper without a descriptor.
     *
     * @param owner        The owner of the field
     * @param name         The old name of the field
     * @param target       The new name of the field
     * @param skipIfExists If the mapping should be skipped if it already exists
     */
    public void addFieldMapping(final String owner, final String name, final String target, final boolean skipIfExists) {
        this.addFieldMapping(owner, name, "", target, skipIfExists);
    }

    /**
     * Add a field mapping to the remapper.
     *
     * @param owner  The owner of the field
     * @param name   The old name of the field
     * @param desc   The descriptor of the field
     * @param target The new name of the field
     */
    public void addFieldMapping(final String owner, final String name, final String desc, final String target) {
        this.addFieldMapping(owner, name, desc, target, false);
    }

    /**
     * Add a field mapping to the remapper.
     *
     * @param owner        The owner of the field
     * @param name         The old name of the field
     * @param desc         The descriptor of the field
     * @param target       The new name of the field
     * @param skipIfExists If the mapping should be skipped if it already exists
     */
    public void addFieldMapping(final String owner, final String name, final String desc, final String target, final boolean skipIfExists) {
        String key = owner + "." + name + ":" + desc;
        if (skipIfExists && this.mappings.containsKey(key)) return;
        this.mappings.put(key, target);

        if (this.reverse != null) {
            this.reverse.reverse = null;
            this.reverse = null;
        }
    }

    /**
     * Get a list of all mapping keys starting with one of the given prefixes.
     *
     * @param prefixes The prefixes
     * @return The list of all keys starting with one of the given prefixes
     */
    public List<String> getStartingMappings(final String... prefixes) {
        List<String> mappings = new ArrayList<>();
        for (String mapping : this.mappings.keySet()) {
            for (String start : prefixes) {
                if (mapping.startsWith(start)) mappings.add(mapping);
            }
        }
        return mappings;
    }

    /**
     * @return If the remapper has no mappings
     */
    public boolean isEmpty() {
        return this.mappings.isEmpty();
    }

    /**
     * Copy all mappings from another remapper to this one.
     *
     * @param remapper The remapper to copy mappings from
     */
    public void copy(final MapRemapper remapper) {
        this.mappings.putAll(remapper.mappings);
    }


    @Override
    public String mapMethodName(final String owner, final String name, final String descriptor) {
        String remappedName = map(owner + '.' + name + descriptor);
        return remappedName == null ? name : remappedName;
    }

    @Override
    public String mapInvokeDynamicMethodName(final String name, final String descriptor) {
        String remappedName = map('.' + name + descriptor);
        return remappedName == null ? name : remappedName;
    }

    @Override
    public String mapAnnotationAttributeName(final String descriptor, final String name) {
        String remappedName = map(descriptor + '.' + name);
        return remappedName == null ? name : remappedName;
    }

    @Override
    public String mapFieldName(final String owner, final String name, final String descriptor) {
        String remappedName = map(owner + '.' + name + ':' + descriptor);
        if (remappedName == null) remappedName = map(owner + '.' + name + ":");
        return remappedName == null ? name : remappedName;
    }

    @Override
    public String map(final String key) {
        return this.mappings.get(key);
    }

    /**
     * Directly get the mapping for a key.<br>
     * This does not return null but the key itself if no mapping is found.
     *
     * @param key The key
     * @return The mapping
     */
    public String mapSafe(final String key) {
        return this.mappings.getOrDefault(key, key);
    }


    /**
     * Reverse the mappings of this remapper.
     *
     * @return The reversed remapper
     */
    public MapRemapper reverse() {
        if (this.reverse != null) return this.reverse;
        MapRemapper reverseRemapper = new MapRemapper();
        for (Map.Entry<String, String> entry : this.mappings.entrySet()) {
            if (entry.getKey().contains(".")) continue;
            reverseRemapper.addClassMapping(entry.getValue(), entry.getKey());
        }
        for (Map.Entry<String, String> entry : this.mappings.entrySet()) {
            if (!entry.getKey().contains(".")) continue;
            if (entry.getKey().contains(":")) {
                String fieldMapping = entry.getKey();
                String owner = fieldMapping.substring(0, fieldMapping.indexOf("."));
                String name = fieldMapping.substring(fieldMapping.indexOf(".") + 1, fieldMapping.indexOf(":"));
                String desc = fieldMapping.substring(fieldMapping.indexOf(":") + 1);
                String mappedName = entry.getValue();

                if (desc.isEmpty()) reverseRemapper.addFieldMapping(this.mapSafe(owner), mappedName, name);
                else reverseRemapper.addFieldMapping(this.mapSafe(owner), mappedName, this.mapDesc(desc), name);
            } else {
                String methodMapping = entry.getKey();
                String owner = methodMapping.substring(0, methodMapping.indexOf("."));
                String name = methodMapping.substring(methodMapping.indexOf(".") + 1, methodMapping.indexOf("("));
                String desc = methodMapping.substring(methodMapping.indexOf("("));
                String mappedName = entry.getValue();

                reverseRemapper.addMethodMapping(this.mapSafe(owner), mappedName, this.mapMethodDesc(desc), name);
            }
        }
        reverseRemapper.reverse = this;
        return this.reverse = reverseRemapper;
    }

}
