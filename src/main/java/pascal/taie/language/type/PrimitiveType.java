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

package pascal.taie.language.type;

import pascal.taie.util.AnalysisException;

public enum PrimitiveType implements Type {

    BYTE("byte"),
    SHORT("short"),
    INT("int"),
    LONG("long"),
    FLOAT("float"),
    DOUBLE("double"),
    CHAR("char"),
    BOOLEAN("boolean");

    private final String name;

    PrimitiveType(String name) {
        this.name = name;
    }

    /**
     * @return the primitive type specified by specific name.
     * @throws AnalysisException if given name is irrelevant to any primitive type.
     */
    public static PrimitiveType get(String name) {
        for (PrimitiveType t : values()) {
            if (t.name.equals(name)) {
                return t;
            }
        }
        throw new AnalysisException(name + " is not primitive type");
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String toString() {
        return getName();
    }
}