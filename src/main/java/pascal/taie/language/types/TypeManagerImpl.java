/*
 * Tai-e: A Static Analysis Framework for Java
 *
 * Copyright (C) 2020-- Tian Tan <tiantan@nju.edu.cn>
 * Copyright (C) 2020-- Yue Li <yueli@nju.edu.cn>
 * All rights reserved.
 *
 * Tai-e is only for educational and academic purposes,
 * and any form of commercial use is disallowed.
 * Distribution of Tai-e is disallowed without the approval.
 */

package pascal.taie.language.types;

import pascal.taie.language.classes.ClassHierarchy;
import pascal.taie.language.classes.JClassLoader;
import pascal.taie.language.classes.StringReps;
import pascal.taie.util.ArrayMap;

import java.util.Map;
import java.util.concurrent.ConcurrentMap;

import static pascal.taie.util.CollectionUtils.newConcurrentMap;
import static pascal.taie.util.CollectionUtils.newMap;

// TODO: optimize maps (classTypes and arrayTypes)
public class TypeManagerImpl implements TypeManager {

    private final ClassHierarchy hierarchy;

    private final Map<JClassLoader, Map<String, ClassType>> classTypes = new ArrayMap<>();

    /**
     * This map may be concurrently written during IR construction,
     * thus we use concurrent map to ensure its thread-safety.
     */
    private final ConcurrentMap<Integer, ConcurrentMap<Type, ArrayType>> arrayTypes
            = newConcurrentMap(8);

    private final ClassType JavaLangObject;

    private final ClassType JavaLangSerializable;

    private final ClassType JavaLangCloneable;

    public TypeManagerImpl(ClassHierarchy hierarchy) {
        this.hierarchy = hierarchy;
        // Initialize special types
        JClassLoader loader = hierarchy.getBootstrapClassLoader();
        JavaLangObject = getClassType(loader, StringReps.OBJECT);
        JavaLangSerializable = getClassType(loader, StringReps.SERIALIZABLE);
        JavaLangCloneable = getClassType(loader, StringReps.CLONEABLE);
    }

    @Override
    public ClassType getClassType(JClassLoader loader, String className) {
        return classTypes.computeIfAbsent(loader, l -> newMap())
                .computeIfAbsent(className, name -> new ClassType(loader, name));
    }

    @Override
    public ClassType getClassType(String className) {
        // TODO: add warning for missing class loader
        return getClassType(hierarchy.getDefaultClassLoader(), className);
    }

    @Override
    public ArrayType getArrayType(Type baseType, int dim) {
        assert !(baseType instanceof VoidType)
                && !(baseType instanceof NullType);
        assert dim >= 1;
        return arrayTypes.computeIfAbsent(dim, d -> newConcurrentMap())
                .computeIfAbsent(baseType, t ->
                        new ArrayType(t, dim
                                , dim == 1 ? t : getArrayType(t, dim - 1)));
    }

    @Override
    public boolean isSubtype(Type supertype, Type subtype) {
        if (subtype.equals(supertype)) {
            return true;
        } else if (subtype instanceof NullType) {
            return supertype instanceof ReferenceType;
        } else if (subtype instanceof ClassType) {
           if (supertype instanceof ClassType) {
                return hierarchy.isSubclass(
                        ((ClassType) supertype).getJClass(),
                        ((ClassType) subtype).getJClass());
            }
        } else if (subtype instanceof ArrayType) {
            if (supertype instanceof ClassType) {
                // JLS (11 Ed.), Chapter 10, Arrays
                return supertype == JavaLangObject ||
                        supertype == JavaLangCloneable ||
                        supertype == JavaLangSerializable;
            } else if (supertype instanceof ArrayType) {
                ArrayType superArray = (ArrayType) supertype;
                ArrayType subArray = (ArrayType) subtype;
                Type superBase = superArray.getBaseType();
                Type subBase = subArray.getBaseType();
                if (superArray.getDimensions() == subArray.getDimensions()) {
                    if (subBase.equals(superBase)) {
                        return true;
                    } else if (superBase instanceof ClassType &&
                            subBase instanceof ClassType) {
                        return hierarchy.isSubclass(
                                ((ClassType) superBase).getJClass(),
                                ((ClassType) subBase).getJClass());
                    }
                } else if (superArray.getDimensions() < subArray.getDimensions()) {
                    return superBase == JavaLangObject ||
                            superBase == JavaLangCloneable ||
                            superBase == JavaLangSerializable;
                }
            }
        }
        return false;
    }
}