// ex: set sts=4 sw=4 expandtab:

/**
 * Yeti code analyzer.
 * Copyright (c) 2007,2008 Madis Janson
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 * 3. The name of the author may not be used to endorse or promote products
 *    derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE AUTHOR "AS IS" AND ANY EXPRESS OR
 * IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 * NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
 * THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package yeti.lang.compiler;

import java.util.List;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.Map;
import java.util.HashMap;
import java.util.Iterator;

public final class YetiAnalyzer extends YetiType {
    static void unusedBinding(Bind bind) {
        throw new CompileException(bind, "Unused binding: " + bind.name);
    }

    static Code analyze(Node node, Scope scope, int depth) {
        if (node instanceof Sym) {
            String sym = ((Sym) node).sym;
            if (Character.isUpperCase(sym.charAt(0))) {
                return variantConstructor(sym, depth);
            }
            return resolve(sym, node, scope, depth);
        }
        if (node instanceof NumLit) {
            return new NumericConstant(((NumLit) node).num);
        }
        if (node instanceof Str) {
            return new StringConstant(((Str) node).str);
        }
        if (node instanceof UnitLiteral) {
            return new UnitConstant();
        }
        if (node instanceof Seq) {
            return analSeq(((Seq) node).st, scope, depth);
        }
        if (node instanceof Bind) {
            Function r = singleBind((Bind) node, scope, depth);
            if (!((BindExpr) r.selfBind).used) {
                unusedBinding((Bind) node);
            }
            return r;
        }
        if (node instanceof BinOp) {
            BinOp op = (BinOp) node;
            if (op.op == "") {
                if (op.left instanceof ThrowSym) {
                    Code throwable = analyze(op.right, scope, depth);
                    JavaType.checkThrowable(op.left, throwable.type);
                    return new Throw(throwable, new Type(depth));
                }
                return apply(node, analyze(op.left, scope, depth),
                             op.right, scope, depth);
            }
            if (op.op == FIELD_OP) {
                if (op.right instanceof NList) {
                    return keyRefExpr(analyze(op.left, scope, depth),
                                      (NList) op.right, scope, depth);
                }
                return selectMember(op, scope, depth);
            }
            if (op.op == ":=") {
                return assignOp(op, scope, depth);
            }
            if (op.op == "\\") {
                return lambda(new Function(null),
                              new Lambda(new Sym("_").pos(op.line, op.col),
                                         op.right, null), scope, depth);
            }
            if (op.op == "is" || op.op == "as" || op.op == "unsafely_as") {
                return isOp(op, ((TypeOp) op).type,
                            analyze(op.right, scope, depth), scope, depth);
            }
            if (op.op == "#") {
                return objectRef((ObjectRefOp) op, scope, depth);
            }
            if (op.op == "loop") {
                return loop(op, scope, depth);
            }
            if (op.op == "-" && op.left == null) {
                return apply(op, resolve("negate", op, scope, depth),
                                 op.right, scope, depth);
            }
            if (op.left == null) {
                throw new CompileException(op,
                    "Internal error (incomplete operator " + op.op + ")");
            }
            return apply(op.right,
                         apply(op, resolve(op.op, op, scope, depth),
                               op.left, scope, depth),
                         op.right, scope, depth);
        }
        if (node instanceof Condition) {
            return cond((Condition) node, scope, depth);
        }
        if (node instanceof Struct) {
            return structType((Struct) node, scope, depth);
        }
        if (node instanceof NList) {
            return list((NList) node, scope, depth);
        }
        if (node instanceof Lambda) {
            return lambda(new Function(null), (Lambda) node, scope, depth);
        }
        if (node instanceof Case) {
            return caseType((Case) node, scope, depth);
        }
        if (node instanceof ConcatStr) {
            return concatStr((ConcatStr) node, scope, depth);
        }
        if (node instanceof Load) {
            String name = ((Load) node).moduleName;
            return new LoadModule(name, YetiTypeVisitor.getType(node, name));
        }
        if (node instanceof RSection) {
            return rsection((RSection) node, scope, depth);
        }
        if (node instanceof NewOp) {
            NewOp op = (NewOp) node;
            Code[] args = mapArgs(op.arguments, scope, depth);
            return new NewExpr(JavaType.resolveConstructor(op,
                                    resolveFullClass(op.name, scope), args)
                                .check(op, scope.packageName), args, op.line);
        }
        if (node instanceof Try) {
            return tryCatch((Try) node, scope, depth);
        }
        if (node instanceof ClassOf) {
            ClassOf co = (ClassOf) node;
            return new ClassOfExpr(
                    resolveFullClass(co.className, scope).javaType.resolve(co));
        }
        throw new CompileException(node,
            "I think that this " + node + " should not be here.");
    }

    static Type nodeToMembers(int type, TypeNode[] param, Map free,
                              Scope scope, int depth) {
        Map members = new HashMap();
        Type[] tp = new Type[param.length];
        for (int i = 0; i < param.length; ++i) {
            tp[i] = nodeToType(param[i].param[0], free, scope, depth);
            if (members.put(param[i].name, tp[i]) != null) {
                throw new CompileException(param[i], "Duplicate field name "
                                    + param[i].name + " in structure type");
            }
        }
        Type result = new Type(type, tp);
        result.partialMembers = members;
        result.finalMembers = new HashMap(members);
        return result;
    }

    static void expectsParam(TypeNode t, int count) {
        if (t.param == null ? count != 0 : t.param.length != count) {
            throw new CompileException(t, "type " + t.name + " expects "
                                          + count + " parameters");
        }
    }

    static final Object[][] PRIMITIVE_TYPE_MAPPING = {
        { "()",      UNIT_TYPE },
        { "boolean", BOOL_TYPE },
        { "char",    CHAR_TYPE },
        { "number",  NUM_TYPE  },
        { "string",  STR_TYPE  }
    };

    static Type typeOfClass(String className, Scope scope) {
        if (className.indexOf('/') > 0)
            return JavaType.typeOfClass(null, className);
        Type t = resolveClass(className, scope, false);
        if (t != null)
            return t;
        return JavaType.typeOfClass(scope.packageName, className);
    }

    static Type nodeToType(TypeNode node, Map free, Scope scope, int depth) {
        String name = node.name;
        for (int i = PRIMITIVE_TYPE_MAPPING.length; --i >= 0;) {
            if (PRIMITIVE_TYPE_MAPPING[i][0] == name) {
                expectsParam(node, 0);
                return (Type) PRIMITIVE_TYPE_MAPPING[i][1];
            }
        }
        if (name == "") {
            return nodeToMembers(STRUCT, node.param, free, scope, depth);
        }
        if (name == "|") {
            return nodeToMembers(VARIANT, node.param, free, scope, depth);
        }
        if (name == "->") {
            expectsParam(node, 2);
            Type[] tp = { nodeToType(node.param[0], free, scope, depth),
                          nodeToType(node.param[1], free, scope, depth) };
            return new Type(FUN, tp);
        }
        if (name.startsWith("~")) {
            int arrays = 0;
            while (name.endsWith("[]")) {
                ++arrays;
                name = name.substring(0, name.length() - 2);
            }
            String cn = name.substring(1).replace('.', '/').intern();
            Type[] tp = new Type[node.param.length];
            for (int i = tp.length; --i >= 0;)
                tp[i] = nodeToType(node.param[i], free, scope, depth);
            Type t = typeOfClass(cn, scope);
            t.param = tp;
            while (--arrays >= 0) {
                t = new Type(JAVA_ARRAY, new Type[] { t });
            }
            return t;
        }
        if (name == "array") {
            expectsParam(node, 1);
            Type[] tp = { nodeToType(node.param[0], free, scope, depth),
                          NUM_TYPE, LIST_TYPE };
            return new Type(MAP, tp);
        }
        if (name == "list") {
            expectsParam(node, 1);
            Type[] tp = { nodeToType(node.param[0], free, scope, depth),
                          NO_TYPE, LIST_TYPE };
            return new Type(MAP, tp);
        }
        if (name == "list?") {
            expectsParam(node, 1);
            Type[] tp = { nodeToType(node.param[0], free, scope, depth),
                          new Type(depth), LIST_TYPE };
            return new Type(MAP, tp);
        }
        if (name == "hash") {
            expectsParam(node, 2);
            Type[] tp = { nodeToType(node.param[1], free, scope, depth),
                          nodeToType(node.param[0], free, scope, depth),
                          MAP_TYPE };
            return new Type(MAP, tp);
        }
        if (Character.isUpperCase(name.charAt(0))) {
            return nodeToMembers(VARIANT, new TypeNode[] { node },
                                 free, scope, depth);
        }
        if (name.startsWith("'")) {
            Type t = (Type) free.get(name);
            if (t == null) {
                free.put(name, t = new Type(depth));
            }
            return t;
        }
        throw new CompileException(node, "Unknown type: " + name);
    }

    static Code isOp(Node is, TypeNode type, Code value,
                     Scope scope, int depth) {
        Type t = nodeToType(type, new HashMap(), scope, depth).deref();
        Type vt = value.type.deref();
        if (is instanceof BinOp) {
            String s = ((BinOp) is).op;
            if (s == "unsafely_as" && (vt.type != VAR || t.type != VAR)) {
                JavaType.checkUnsafeCast(is, value.type, t);
            } else if (s == "as" &&
                       JavaType.isAssignable(is, t, vt, true) < 0) {
                throw new CompileException(is, "impossible cast from " +
                                               vt + " to " + t);
            }
            return new Cast(value, t, s == "as", is.line);
        }
        // () is class is a way for writing null constant
        if ((t.type == JAVA || t.type == JAVA_ARRAY) &&
            value instanceof UnitConstant) {
            return new Cast(value, t, false, is.line);
        }
        try {
            unify(value.type, t);
        } catch (TypeException ex) {
            throw new CompileException(is, ex.getMessage() +
                        " (when checking " + value.type + " is " + t + ")");
        }
        return value;
    }

    static Code[] mapArgs(Node[] args, Scope scope, int depth) {
        if (args == null)
            return null;
        Code[] res = new Code[args.length];
        for (int i = 0; i < args.length; ++i) {
            res[i] = analyze(args[i], scope, depth);
        }
        return res;
    }

    static Code objectRef(ObjectRefOp ref, Scope scope, int depth) {
        Code obj = null;
        Type t = null;
        if (ref.right instanceof Sym) {
            String className = ((Sym) ref.right).sym;
            t = resolveClass(className, scope, true);
            if (t == null && Character.isUpperCase(className.charAt(0))) {
                t = JavaType.typeOfClass(scope.packageName, className);
            }
        }
        if (t == null) {
            obj = analyze(ref.right, scope, depth);
            t = obj.type;
        }
        if (ref.arguments == null) {
            JavaType.Field f = JavaType.resolveField(ref, t, obj == null);
            f.check(ref, scope.packageName);
            return new ClassField(obj, f, ref.line);
        }
        Code[] args = mapArgs(ref.arguments, scope, depth);
        return new MethodCall(obj,
                    JavaType.resolveMethod(ref, t, args, obj == null)
                        .check(ref, scope.packageName), args, ref.line);
    }

    static Code tryCatch(Try t, Scope scope, int depth) {
        TryCatch tc = new TryCatch();
        scope = new Scope(scope, null, null); // closure frame
        scope.closure = tc;
        tc.setBlock(analyze(t.block, scope, depth));
        if (t.cleanup != null) {
            tc.cleanup = analyze(t.cleanup, scope, depth);
            try {
                unify(tc.cleanup.type, UNIT_TYPE);
            } catch (TypeException ex) {
                throw new CompileException(t.cleanup,
                                "finally block must have a unit type, not "
                                + tc.cleanup.type, ex);
            }
        }
        for (int i = 0; i < t.catches.length; ++i) {
            Catch c = t.catches[i];
            Type exception = resolveFullClass(c.exception, scope);
            exception.javaType.resolve(c);
            TryCatch.Catch cc = tc.addCatch(exception);
            cc.handler = analyze(c.handler,
                c.bind == null ? scope : new Scope(scope, c.bind, cc), depth);
            try {
                unify(tc.block.type, cc.handler.type);
            } catch (TypeException ex) {
                throw new CompileException(c.handler,
                            "This catch has " + cc.handler.type +
                            " type, while try block was " + tc.block.type, ex);
            }
        }
        return tc;
    }

    static Code apply(Node where, Code fun, Node arg, Scope scope, int depth) {
        Code argCode = analyze(arg, scope, depth);
        Type[] applyFun = { argCode.type, new Type(depth) };
        try {
            unify(fun.type, new Type(FUN, applyFun));
        } catch (TypeException ex) {
            throw new CompileException(where,
                "Cannot apply " + argCode.type + " to " + fun.type +
                "\n    " + ex.getMessage());
        }
        return fun.apply(argCode, applyFun[1], where.line);
    }

    static Code rsection(RSection section, Scope scope, int depth) {
        if (section.sym == FIELD_OP) {
            LinkedList parts = new LinkedList();
            Node x = section.arg;
            for (BinOp op; x instanceof BinOp; x = op.left) {
                op = (BinOp) x;
                if (op.op != FIELD_OP) {
                    throw new CompileException(op,
                        "Unexpected " + op.op + " in field selector");
                }
                checkSelectorSym(op, op.right);
                parts.addFirst(((Sym) op.right).sym);
            }
            checkSelectorSym(section, x);
            parts.addFirst(((Sym) x).sym);
            String[] fields =
                (String[]) parts.toArray(new String[parts.size()]);
            Type res = new Type(depth), arg = res;
            for (int i = fields.length; --i >= 0;) {
                arg = selectMemberType(arg, fields[i], depth);
            }
            return new SelectMemberFun(new Type(FUN, new Type[] { arg, res }),
                                       fields);
        }
        Code fun = resolve(section.sym, section, scope, depth);
        Code arg = analyze(section.arg, scope, depth);
        Type[] r = { new Type(depth), new Type(depth) };
        Type[] afun = { r[0], new Type(FUN, new Type[] { arg.type, r[1] }) };
        try {
            unify(fun.type, new Type(FUN, afun));
        } catch (TypeException ex) {
            throw new CompileException(section,
                "Cannot apply " + arg.type + " as a 2nd argument to " +
                fun.type + "\n    " + ex.getMessage());
        }
        return fun.apply2nd(arg, new Type(FUN, r), section.line);
    }

    static Code variantConstructor(String name, int depth) {
        Type arg = new Type(depth);
        Type tag = new Type(VARIANT, new Type[] { arg });
        tag.partialMembers = new HashMap();
        tag.partialMembers.put(name, arg);
        Type[] fun = { arg, tag };
        return new VariantConstructor(new Type(FUN, fun), name);
    }

    static void checkSelectorSym(Node op, Node sym) {
        if (!(sym instanceof Sym)) {
            if (sym == null) {
                throw new CompileException(op, "What's that dot doing here?");
            }
            throw new CompileException(sym, "Illegal ." + sym);
        }
    }

    static Type selectMemberType(Type res, String field, int depth) {
        Type arg = new Type(STRUCT, new Type[] { res });
        arg.partialMembers = new HashMap();
        arg.partialMembers.put(field, res);
        return arg;
    }

    static Code selectMember(BinOp op, Scope scope, int depth) {
        final Type res = new Type(depth);
        checkSelectorSym(op, op.right);
        final String field = ((Sym) op.right).sym;
        Type arg = selectMemberType(res, field, depth);
        Code src = analyze(op.left, scope, depth);
        try {
            unify(arg, src.type);
        } catch (TypeException ex) {
            throw new CompileException(op.right,
                src.type + " do not have ." + field + " field\n", ex);
        }
        boolean poly = src.polymorph && src.type.finalMembers != null &&
            ((Type) src.type.finalMembers.get(field)).field == 0;
        return new SelectMember(res, src, field, op.line, poly) {
            boolean mayAssign() {
                Type t = st.type.deref();
                Type given;
                if (t.finalMembers != null &&
                    (given = (Type) t.finalMembers.get(field)) != null &&
                    (given.field != FIELD_MUTABLE)) {
                    return false;
                }
                Type self = (Type) t.partialMembers.get(field);
                if (self.field != FIELD_MUTABLE) {
                    // XXX couldn't we get along with res.field = FIELD_MUTABLE?
                    t.partialMembers.put(field, mutableFieldRef(res));
                }
                return true;
            }
        };
    }

    static Code keyRefExpr(Code val, NList keyList, Scope scope, int depth) {
        if (keyList.items == null || keyList.items.length == 0) {
            throw new CompileException(keyList, ".[] - missing key expression");
        }
        if (keyList.items.length != 1) {
            throw new CompileException(keyList, "Unexpected , inside .[]");
        }
        Code key = analyze(keyList.items[0], scope, depth);
        Type[] param = { new Type(depth), key.type, new Type(depth) };
        try {
            unify(val.type, new Type(MAP, param));
        } catch (TypeException ex) {
            throw new CompileException(keyList, val.type +
                " cannot be referenced by " + key.type + " key", ex);
        }
        return new KeyRefExpr(param[0], val, key, keyList.line);
    }

    static Code assignOp(BinOp op, Scope scope, int depth) {
        Code left = analyze(op.left, scope, depth);
        Code right = analyze(op.right, scope, depth);
        try {
            unify(left.type, right.type);
        } catch (TypeException ex) {
            throw new CompileException(op, ex.getMessage());
        }
        Code assign = left.assign(right);
        if (assign == null) {
            throw new CompileException(op,
                "Non-mutable expression on the left of the assign operator :=");
        }
        assign.type = UNIT_TYPE;
        return assign;
    }

    static Code concatStr(ConcatStr concat, Scope scope, int depth) {
        Code[] parts = new Code[concat.param.length];
        for (int i = 0; i < parts.length; ++i) {
            parts[i] = analyze(concat.param[i], scope, depth);
        }
        return new ConcatStrings(parts);
    }

    static Code cond(Condition condition, Scope scope, int depth) {
        Node[][] choices = condition.choices;
        Code[][] conds = new Code[choices.length][];
        Type result = null;
        boolean poly = true;
        for (int i = 0; i < choices.length; ++i) {
            Node[] choice = choices[i];
            Code val = analyze(choice[0], scope, depth);
            if (choice.length == 1) {
                conds[i] = new Code[] { val };
            } else {
                Code cond = analyze(choice[1], scope, depth);
                try {
                    unify(BOOL_TYPE, cond.type);
                } catch (TypeException ex) {
                    throw new CompileException(choice[1],
                        "if condition must have a boolean type (but here was "
                        + cond.type + ")");
                }
                conds[i] = new Code[] { val, cond };
            }
            poly &= val.polymorph;
            if (result == null) {
                result = val.type;
            } else {
                Type t = JavaType.mergeTypes(result, val.type);
                if (t != null) {
                    result = t;
                    continue;
                }
                try {
                    unify(result, val.type);
                } catch (TypeException ex) {
                    throw new CompileException(choice[0],
                        "This if branch has a " + val.type +
                        " type, while another was a " + result, ex);
                }
            }
        }
        return new ConditionalExpr(result, conds, poly);
    }

    static Code loop(BinOp loop, Scope scope, int depth) {
        Code cond = analyze(loop.left != null ? loop.left : loop.right,
                            scope, depth);
        try {
            unify(BOOL_TYPE, cond.type);
        } catch (TypeException ex) {
            throw new CompileException(loop.left,
                "Loop condition must have a boolean type (but here was "
                + cond.type + ")");
        }
        if (loop.left == null) {
            return new LoopExpr(cond, new UnitConstant());
        }
        Code body = analyze(loop.right, scope, depth);
        try {
            unify(body.type, UNIT_TYPE);
        } catch (TypeException ex) {
            throw new CompileException(loop.right,
                "Loop body must have a unit type, not " + body.type, ex);
        }
        return new LoopExpr(cond, body);
    }

    static void registerVar(BindExpr binder, Scope scope) {
        while (scope != null) {
            if (scope.closure != null) {
                scope.closure.addVar(binder);
                return;
            }
            scope = scope.outer;
        }
    }

    static Function singleBind(Bind bind, Scope scope, int depth) {
        if (!(bind.expr instanceof Lambda)) {
            throw new CompileException(bind,
                "Closed binding must be a function binding");
        }
        // recursive binding
        Function lambda = new Function(new Type(depth + 1));
        BindExpr binder = new BindExpr(lambda, bind.var);
        lambda.selfBind = binder;
        if (!bind.noRec) {
            scope = new Scope(scope, bind.name, binder);
        }
        lambdaBind(lambda, bind, scope, depth + 1);
        return lambda;
    }

    static Scope explodeStruct(Node where, LoadModule m,
                               Scope scope, int depth, boolean noRoot) {
        if (m.type.type == STRUCT) {
            Iterator j = m.type.finalMembers.entrySet().iterator();
        members:
            while (j.hasNext()) {
                Map.Entry e = (Map.Entry) j.next();
                String name = ((String) e.getKey()).intern();
                if (noRoot)
                    for (Scope i = ROOT_SCOPE; i != null; i = i.outer)
                        if (i.name == name)
                            continue members;
                Type t = (Type) e.getValue();
                scope = bindPoly(name, t, m.bindField(name, t), depth, scope);
            }
        } else if (m.type.type != UNIT) {
            throw new CompileException(where,
                "Expected module with struct or unit type here (" +
                m.moduleName.replace('/', '.') + " has type " + m.type +
                ", but only structs can be exploded)");
        }
        return scope;
    }

    static Code analSeq(Node[] nodes, Scope scope, int depth) {
        BindExpr[] bindings = new BindExpr[nodes.length];
        SeqExpr result = null, last = null, cur;
        for (int i = 0; i < nodes.length - 1; ++i) {
            if (nodes[i] instanceof Bind) {
                Bind bind = (Bind) nodes[i];
                BindExpr binder;
                if (bind.expr instanceof Lambda) {
                    binder = (BindExpr) singleBind(bind, scope, depth).selfBind;
                } else {
                    Code code = analyze(bind.expr, scope, depth + 1);
                    binder = new BindExpr(code, bind.var);
                    if (bind.type != null) {
                        isOp(bind, bind.type, binder.st, scope, depth);
                    }
                }
                if (binder.st.polymorph && !bind.var) {
                    scope = bindPoly(bind.name, binder.st.type, binder,
                                     depth, scope);
                } else {
                    scope = new Scope(scope, bind.name, binder);
                }
                if (bind.var) {
                    registerVar(binder, scope.outer);
                }
                bindings[i] = binder;
                cur = binder;
            } else if (nodes[i] instanceof Load) {
                LoadModule m = (LoadModule) analyze(nodes[i], scope, depth);
                scope = explodeStruct(nodes[i], m, scope, depth, false);
                cur = new SeqExpr(m);
            } else if (nodes[i] instanceof Import) {
                String name = ((Import) nodes[i]).className;
                int lastSlash = name.lastIndexOf('/');
                scope = new Scope(scope, (lastSlash < 0 ? name
                              : name.substring(lastSlash + 1)).intern(), null);
                scope.importClass = new Type("L" + name + ';');
                continue;
            } else {
                Code code = analyze(nodes[i], scope, depth);
                try {
                    unify(UNIT_TYPE, code.type);
                } catch (TypeException ex) {
                    throw new CompileException(nodes[i],
                        "Unit type expected here, not a " + code.type);
                }
                code.ignoreValue();
                cur = new SeqExpr(code);
            }
            if (last == null) {
                result = last = cur;
            } else {
                last.result = cur;
                last = cur;
            }
        }
        Code code = analyze(nodes[nodes.length - 1], scope, depth);
        for (int i = bindings.length; --i >= 0;) {
            if (bindings[i] != null && !bindings[i].used) {
                unusedBinding((Bind) nodes[i]);
            }
        }
        if (last == null) {
            return code;
        }
        for (cur = result; cur != null; cur = (SeqExpr) cur.result) {
            cur.type = code.type;
        }
        last.result = code;
        result.polymorph = code.polymorph;
        return result;
    }

    static Code lambdaBind(Function to, Bind bind, Scope scope, int depth) {
        if (bind.type != null) {
            isOp(bind, bind.type, to, scope, depth);
        }
        return lambda(to, (Lambda) bind.expr, scope, depth);
    } 

    static Code lambda(Function to, Lambda lambda, Scope scope, int depth) {
        Type expected = to.type == null ? null : to.type.deref();
        to.polymorph = true;
        Scope bodyScope;
        if (lambda.arg instanceof Sym) {
            if (expected != null && expected.type == FUN) {
                to.arg.type = expected.param[0];
            } else {
                to.arg.type = new Type(depth);
            }
            bodyScope = new Scope(scope, ((Sym) lambda.arg).sym, to);
        } else if (lambda.arg instanceof UnitLiteral) {
            to.arg.type = UNIT_TYPE;
            bodyScope = new Scope(scope, null, to);
        } else {
            throw new CompileException(lambda.arg,
                                       "Bad argument: " + lambda.arg);
        }
        bodyScope.closure = to;
        if (lambda.expr instanceof Lambda) {
            Function f = new Function(expected != null && expected.type == FUN
                                      ? expected.param[1] : null);
            // make f to know about its outer scope before processing it
            to.setBody(f);
            lambda(f, (Lambda) lambda.expr, bodyScope, depth);
        } else {
            to.setBody(analyze(lambda.expr, bodyScope, depth));
        }
        Type fun = new Type(FUN, new Type[] { to.arg.type, to.body.type });
        if (to.type != null) {
            try {
                unify(fun, to.type);
            } catch (TypeException ex) {
                throw new CompileException(lambda,
                        "Function type " + fun + " is not " + to.type
                        + " (self-binding)\n    " + ex.getMessage());
            }
        }
        to.type = fun;
        to.bindName = lambda.bindName;
        to.body.markTail();
        return to;
    }

    static Code structType(Struct st, Scope scope, int depth) {
        Node[] nodes = st.fields;
        if (nodes.length == 0) {
            throw new CompileException(st, "No sense in empty struct");
        }
        Scope local = scope;
        Map fields = new HashMap();
        Map codeFields = new HashMap();
        String[] names = new String[nodes.length];
        Code[] values  = new Code[nodes.length];
        StructConstructor result = new StructConstructor(names, values);
        result.polymorph = true;
        // Functions see struct members in their scope
        for (int i = 0; i < nodes.length; ++i) {
            if (!(nodes[i] instanceof Bind)) {
                throw new CompileException(nodes[i],
                    "Unexpected beast in the structure (" + nodes[i] +
                    "), please give me some field binding.");
            }
            Bind field = (Bind) nodes[i];
            if (fields.containsKey(field.name)) {
                throw new CompileException(field, "Duplicate field "
                    + field.name + " in the structure");
            }
            Code code = values[i] =
                    !field.noRec && field.expr instanceof Lambda
                        ? new Function(new Type(depth))
                        : analyze(field.expr, scope, depth);
            names[i] = field.name;
            fields.put(field.name,
                field.var ? fieldRef(depth, code.type, FIELD_MUTABLE) :
                code.polymorph || field.expr instanceof Lambda ? code.type
                        : fieldRef(depth, code.type, FIELD_NON_POLYMORPHIC));
            if (!field.noRec) {
                local = new Scope(local, field.name,
                                  result.bind(i, code, field.var));
            }
        }
        for (int i = 0; i < nodes.length; ++i) {
            Bind field = (Bind) nodes[i];
            if (field.expr instanceof Lambda) {
                lambdaBind((Function) values[i], field, local, depth);
            }
        }
        result.type = new Type(STRUCT,
            (Type[]) fields.values().toArray(new Type[fields.size()]));
        result.type.finalMembers = fields;
        return result;
    }

    static Scope badPattern(Node pattern) {
        throw new CompileException(pattern, "Bad case pattern: " + pattern);
    }

    // oh holy fucking shit, this code sucks. horrible abuse of BinOps...
    static Code caseType(Case ex, Scope scope, int depth) {
        Node[] choices = ex.choices;
        if (choices.length == 0) {
            throw new CompileException(ex, "case expects some option!");
        }
        Code val = analyze(ex.value, scope, depth);
        Map variants = new HashMap();
        CaseExpr result = new CaseExpr(val);
        result.polymorph = true;
        for (int i = 0; i < choices.length; ++i) {
            Scope local = scope;
            BinOp choice;
            if (!(choices[i] instanceof BinOp) ||
                (choice = (BinOp) choices[i]).op != ":") {
                throw new CompileException(choices[i],
                    "Expecting option, not a " + choices[i]);
            }
            if (!(choice.left instanceof BinOp)) {
                badPattern(choice.left); // TODO
            }

            // binop. so try to extract a damn variant constructor.
            BinOp pat = (BinOp) choice.left;
            String variant = null;
            if (pat.op != "" || !(pat.left instanceof Sym) ||
                !Character.isUpperCase(
                    (variant = ((Sym) pat.left).sym).charAt(0))) {
                badPattern(pat); // binop pat should be a variant
            }
            Choice caseChoice = result.addVariantChoice(variant);
            Type variantArg = null;
            if (!(pat.right instanceof Sym)) {
                if (pat.right instanceof UnitLiteral) {
                    variantArg = UNIT_TYPE;
                } else {
                    badPattern(pat); // TODO
                }
            } else {
                variantArg = new Type(depth);
                // got some constructor, store to map.
                local = new Scope(local, ((Sym) pat.right).sym,
                                  caseChoice.bindParam(variantArg));
            }
            Type old = (Type) variants.put(variant, variantArg);
            if (old != null) { // same constructor already. shall be same type.
                try {
                    unify(old, variantArg);
                } catch (TypeException e) {
                    throw new CompileException(pat.right, e.getMessage());
                }
            }
            
            // nothing intresting, just get option expr and merge to result
            Code opt = analyze(choice.right, local, depth);
            result.polymorph &= opt.polymorph;
            if (result.type == null) {
                result.type = opt.type;
            } else {
                try {
                    unify(result.type, opt.type);
                } catch (TypeException e) {
                    throw new CompileException(choice.right,
                        "This choice has a " + opt.type +
                        " type, while another was a " + result.type, e);
                }
            }
            caseChoice.setExpr(opt);
        }
        Type variantType = new Type(VARIANT,
            (Type[]) variants.values().toArray(new Type[variants.size()]));
        variantType.finalMembers = variants;
        try {
            unify(val.type, variantType);
        } catch (TypeException e) {
            throw new CompileException(ex.value,
                "Inferred type for case argument is " + variantType +
                ", but a " + val.type + " is given\n    (" +
                e.getMessage() + ")");
        }
        return result;
    }

    static Code list(NList list, Scope scope, int depth) {
        Node[] items = list.items == null ? new Node[0] : list.items;
        Code[] keyItems = null;
        Code[] codeItems = new Code[items.length];
        Type type = null;
        Type keyType = NO_TYPE;
        Type kind = null;
        BinOp bin;
        for (int i = 0; i < items.length; ++i) {
            if (items[i] instanceof BinOp &&
                (bin = (BinOp) items[i]).op == ":") {
                Code key = analyze(bin.left, scope, depth);
                if (kind != MAP_TYPE) {
                    if (kind != null) {
                        throw new CompileException(bin,
                            "Unexpected : in list" + (i != 1 ? "" :
                            " (or the key is missing on the first item?)"));
                    }
                    keyType = key.type;
                    kind = MAP_TYPE;
                    keyItems = new Code[items.length];
                } else {
                    try {
                        unify(keyType, key.type);
                    } catch (TypeException ex) {
                        throw new CompileException(items[i],
                            "This map element has " + keyType +
                            "key, but others have had " + keyType, ex);
                    }
                }
                keyItems[i] = key;
                codeItems[i] = analyze(bin.right, scope, depth);
            } else {
                if (kind == MAP_TYPE) {
                    throw new CompileException(items[i],
                                "Map item is missing a key");
                }
                kind = LIST_TYPE;
                if (items[i] instanceof BinOp &&
                    (bin = (BinOp) items[i]).op == "..") {
                    Code from = analyze(bin.left, scope, depth);
                    Code to = analyze(bin.right, scope, depth);
                    Node n = null; Type t = null;
                    try {
                        n = bin.left;
                        unify(t = from.type, NUM_TYPE);
                        n = bin.right;
                        unify(t = to.type, NUM_TYPE);
                    } catch (TypeException ex) {
                        throw new CompileException(n, ".. range expects " +
                                    "limit to be number, not a " + t, ex);
                    }
                    codeItems[i] = new Range(from, to);
                } else {
                    codeItems[i] = analyze(items[i], scope, depth);
                }
            }
            if (type == null) {
                type = codeItems[i].type;
            } else {
                try {
                    unify(type, codeItems[i].type);
                } catch (TypeException ex) {
                    throw new CompileException(items[i], (kind == LIST_TYPE
                         ? "This list element is " : "This map element is ") +
                         codeItems[i].type + ", but others have been " + type,
                        ex);
                }
            }
        }
        if (type == null) {
            type = new Type(depth);
        }
        if (kind == null) {
            kind = LIST_TYPE;
        }
        if (list.items == null) {
            keyType = new Type(depth);
            kind = MAP_TYPE;
        }
        Code res = kind == LIST_TYPE ? (Code) new ListConstructor(codeItems)
                                     : new MapConstructor(keyItems, codeItems);
        res.type = new Type(MAP, new Type[] { type, keyType, kind });
        res.polymorph = kind == LIST_TYPE;
        return res;
    }

    public static RootClosure toCode(String sourceName, String className,
                                     char[] src, int flags, Map classes,
                                     String[] preload) {
        Object oldSrc = currentSrc.get();
        currentSrc.set(src);
        try {
            Parser parser = new Parser(sourceName, src, flags);
            Node n = parser.parse();
            if ((flags & YetiC.CF_PRINT_PARSE_TREE) != 0) {
                System.err.println(n.show());
            }
            if (parser.moduleName != null) {
                className = parser.moduleName;
            }
            classes.put(className, null);
            RootClosure root = new RootClosure();
            Scope scope = new Scope(ROOT_SCOPE, null, null);
            for (int i = 0; i < preload.length; ++i) {
                if (!preload[i].equals(className)) {
                    scope = explodeStruct(null, new LoadModule(preload[i],
                          YetiTypeVisitor.getType(null, preload[i])),
                          scope, 0, "yeti/lang/std".equals(preload[i]));
                }
            }
            root.preload = preload;
            scope.closure = root;
            scope.packageName = JavaType.packageOfClass(className);
            root.code = analyze(n, scope, 0);
            root.type = root.code.type;
            root.moduleName = parser.moduleName;
            root.isModule = parser.isModule;
            if ((flags & YetiC.CF_COMPILE_MODULE) == 0 && !parser.isModule) {
                try {
                    unify(root.type, UNIT_TYPE);
                } catch (TypeException ex) {
                    throw new CompileException(n,
                        "Program body must have a unit type, not "
                        + root.type, ex);
                }
            } else { // MODULE
                List free = new ArrayList(), deny = new ArrayList();
                getFreeVar(free, deny, root.type, -1);
                //System.err.println("checked module type, free are " + free
                //        + ", deny " + deny);
                if (!deny.isEmpty() ||
                    !free.isEmpty() && !root.code.polymorph) {
                    throw new CompileException(n,
                        "Module type is not fully defined");
                }
            }
            return root;
        } catch (CompileException ex) {
            if (ex.fn == null) {
                ex.fn = sourceName;
            }
            throw ex;
        } finally {
            currentSrc.set(oldSrc);
        }
    }
}