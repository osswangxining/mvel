/**
 * MVEL (The MVFLEX Expression Language)
 *
 * Copyright (C) 2007 Christopher Brock, MVFLEX/Valhalla Project and the Codehaus
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package org.mvel.compiler;

import org.mvel.CompileException;
import org.mvel.ParserContext;
import org.mvel.PropertyAccessException;
import org.mvel.optimizers.AbstractOptimizer;
import static org.mvel.util.ParseTools.*;
import static org.mvel.util.PropertyTools.getFieldOrAccessor;
import static org.mvel.util.PropertyTools.getSubComponentType;
import org.mvel.util.StringAppender;

import java.lang.reflect.*;
import java.util.*;

public class PropertyVerifier extends AbstractOptimizer {
    private static final int DONE = -1;
    private static final int NORM = 0;
    private static final int METH = 1;
    private static final int COL = 2;

    private List<String> inputs = new LinkedList<String>();
    private boolean first = true;
    private boolean resolvedExternally;
    private Map<String, Class> paramTypes;

    public PropertyVerifier(char[] property, ParserContext parserContext) {
        this.length = (this.expr = property).length;
        this.pCtx = parserContext;
    }

    public PropertyVerifier(String property, ParserContext parserContext) {
        this.length = (this.expr = property.toCharArray()).length;
        this.pCtx = parserContext;
    }
    
    public List<String> getInputs() {
        return inputs;
    }

    public void setInputs(List<String> inputs) {
        this.inputs = inputs;
    }

    /**
     * Analyze the statement and return the known egress type.
     *
     * @return known engress type
     */
    public Class analyze() {
        Class ctx = Object.class;
        resolvedExternally = true;

        first = true;
        while (cursor < length) {
            switch (nextSubToken()) {
                case NORM:
                    ctx = getBeanProperty(ctx, capture());
                    break;
                case METH:
                    ctx = getMethod(ctx, capture());
                    break;
                case COL:
                    ctx = getCollectionProperty(ctx, capture());
                    break;
                case DONE:
                    break;
            }

            first = false;
        }
        return ctx;
    }

    /**
     * Process bean property
     *
     * @param ctx      - the ingress type
     * @param property - the property component
     * @return known egress type.
     */
    private Class getBeanProperty(Class ctx, String property) {
        if (first) {
            if (pCtx.hasVarOrInput(property)) {
                if (pCtx.isStrictTypeEnforcement()) {
                    paramTypes = pCtx.getTypeParameters(property);
                    pCtx.setLastTypeParameters(pCtx.getTypeParametersAsArray(property));
                }

                return pCtx.getVarOrInputType(property);
            }
            else if (pCtx.hasImport(property)) {
                resolvedExternally = false;
                return pCtx.getImport(property);
            }
            if (!pCtx.isStrongTyping()) {
                return Object.class;
            }
        }

        start = cursor;

        Member member = ctx != null ? getFieldOrAccessor(ctx, property) : null;

        if (member instanceof Field) {
            if (pCtx.isStrictTypeEnforcement()) {
                Field f = ((Field) member);

                if (f.getGenericType() != null && f.getGenericType() instanceof ParameterizedType) {
                    ParameterizedType pt = (ParameterizedType) f.getGenericType();
                    pCtx.setLastTypeParameters(pt.getActualTypeArguments());

                    Type[] gpt = pt.getActualTypeArguments();
                    Type[] classArgs = ((Class) pt.getRawType()).getTypeParameters();

                    if (gpt.length > 0 && paramTypes == null) paramTypes = new HashMap<String, Class>();
                    for (int i = 0; i < gpt.length; i++) {
                        paramTypes.put(classArgs[i].toString(), (Class) gpt[i]);
                    }

                }

                return f.getType();
            }
            else {
                return ((Field) member).getType();
            }
        }
        else if (member != null) {
            Method method = (Method) member;

            if (pCtx.isStrictTypeEnforcement()) {
                //if not a field, then this is a property getter
                Type parametricReturnType = method.getGenericReturnType();

                //push return type parameters onto parser context, only if this is a parametric type
                if (parametricReturnType instanceof ParameterizedType) {
                    pCtx.setLastTypeParameters(((ParameterizedType) parametricReturnType).getActualTypeArguments());
                }

            }
            return method.getReturnType();
        }
        else if (pCtx.hasImport(property)) {
            return pCtx.getImport(property);
        }
        else {
            Object tryStaticMethodRef = tryStaticAccess();

            if (tryStaticMethodRef != null) {
                if (tryStaticMethodRef instanceof Class) {
                    return (Class) tryStaticMethodRef;
                }
                else if (tryStaticMethodRef instanceof Field) {
                    try {
                        return ((Field) tryStaticMethodRef).get(null).getClass();
                    }
                    catch (Exception e) {
                        throw new CompileException("in verifier: ", e);
                    }
                }
                else {
                    try {
                        return ((Method) tryStaticMethodRef).getReturnType();
                    }
                    catch (Exception e) {
                        throw new CompileException("in verifier: ", e);
                    }
                }

            }
            else if (ctx != null && ctx.getClass() == Class.class) {
                for (Method m : ctx.getMethods()) {
                    if (property.equals(m.getName())) {
                        return m.getReturnType();
                    }
                }
            }

            if (pCtx.isStrictTypeEnforcement()) {
                addFatalError("unqualified type in strict mode for: " + property);
            }
            return Object.class;
        }
    }

    /**
     * Process collection property
     *
     * @param ctx      - the ingress type
     * @param property - the property component
     * @return known egress type
     */
    private Class getCollectionProperty(Class ctx, String property) {
        if (pCtx.hasVarOrInput(property)) {
            ctx = getSubComponentType(pCtx.getVarOrInputType(property));
        }
        else if (pCtx.hasImport(property)) {
            resolvedExternally = false;
            ctx = getSubComponentType(pCtx.getImport(property));
        }
        else {
            ctx = Object.class;
        }

        ++cursor;

        whiteSpaceSkip();

        if (cursor == length)
            throw new PropertyAccessException("unterminated '['");

        if (scanTo(']')) {
            addFatalError("unterminated [ in token");
        }

        ++cursor;

        return ctx;
    }


    /**
     * Process method
     *
     * @param ctx  - the ingress type
     * @param name - the property component
     * @return known egress type.
     */
    private Class getMethod(Class ctx, String name) {
        /**
         * Check to see if this is the first element in the statement.
         */
        if (first) {
            first = false;

            /**
             * It's the first element in the statement, therefore we check to see if there is a static import of a
             * native Java method or an MVEL function.
             */
            if (pCtx.hasImport(name)) {
                Method m = pCtx.getStaticImport(name).getMethod();

                /**
                 * Replace the method parameters.
                 */
                ctx = m.getDeclaringClass();
                name = m.getName();
            }
            else if (pCtx.hasFunction(name)) {
                resolvedExternally = false;
                return pCtx.getFunction(name).getEgressType();
            }
        }

        int st = cursor;

        /**
         * Get the arguments for the method.
         */
        String tk = ((cursor = balancedCapture(expr, cursor, '(')) - st) > 1 ? new String(expr, st + 1, cursor - st - 1) : "";

        cursor++;

        /**
         * Parse out the arguments list.
         */
        Class[] args;
        String[] subtokens = parseParameterList(tk.toCharArray(), 0, -1);

        if (subtokens.length == 0) {
            args = new Class[0];
            subtokens = new String[0];
        }
        else {
            args = new Class[subtokens.length];

            /**
             *  Subcompile all the arguments to determine their known types.
             */
            ExpressionCompiler compiler;
            for (int i = 0; i < subtokens.length; i++) {
                (compiler = new ExpressionCompiler(subtokens[i], true))._compile();
                args[i] = compiler.getReturnType() != null ? compiler.getReturnType() : Object.class;
            }
        }

        /**
         * If the target object is an instance of java.lang.Class itself then do not
         * adjust the Class scope target.
         */

        Method m;

        /**
         * If we have not cached the method then we need to go ahead and try to resolve it.
         */

        if ((m = getBestCandidate(args, name, ctx, ctx.getMethods(), pCtx.isStrongTyping())) == null) {
            if ((m = getBestCandidate(args, name, ctx, ctx.getDeclaredMethods(), pCtx.isStrongTyping())) == null) {
                StringAppender errorBuild = new StringAppender();
                for (int i = 0; i < args.length; i++) {
                    errorBuild.append(args[i] != null ? args[i].getClass().getName() : null);
                    if (i < args.length - 1) errorBuild.append(", ");
                }

                if ("size".equals(name) && args.length == 0 && ctx.isArray()) {
                    return Integer.class;
                }

                if (pCtx.isStrictTypeEnforcement()) {
                    addFatalError("unable to resolve method using strict-mode: " + ctx.getName() + "." + name + "(...)");
                }
                return Object.class;
            }
        }

        /**
         * If we're in strict mode, we look for generic type information.
         */
        if (pCtx.isStrictTypeEnforcement() && m.getGenericReturnType() != null) {
            Map<String, Class> typeArgs = new LinkedHashMap<String, Class>();

            Type[] gpt = m.getGenericParameterTypes();
            Class z;
            ParameterizedType pt;

            for (int i = 0; i < gpt.length; i++) {
                if (gpt[i] instanceof ParameterizedType) {
                    pt = (ParameterizedType) gpt[i];
                    if ((z = pCtx.getImport(subtokens[i])) != null) {
                        /**
                         * We record the value of the type parameter to our typeArgs Map.
                         */
                        if (pt.getRawType().equals(Class.class)) {
                            /**
                             * If this is an instance of Class, we deal with the special parameterization case.
                             */
                            typeArgs.put(pt.getActualTypeArguments()[0].toString(), z);
                        }
                        else {
                            typeArgs.put(gpt[i].toString(), z);
                        }
                    }
                }
            }

            /**
             * Get the return type argument
             */
            Type parametricReturnType = m.getGenericReturnType();
            String returnTypeArg = parametricReturnType.toString();

            //push return type parameters onto parser context, only if this is a parametric type
            if (parametricReturnType instanceof ParameterizedType) {
                pCtx.setLastTypeParameters(((ParameterizedType) parametricReturnType).getActualTypeArguments());
            }

            if (paramTypes != null && paramTypes.containsKey(returnTypeArg)) {
                /**
                 * If the paramTypes Map contains the known type, return that type.
                 */
                return paramTypes.get(returnTypeArg);
            }
            else if (typeArgs.containsKey(returnTypeArg)) {
                /**
                 * If the generic type was declared as part of the method, it will be in this
                 * Map.
                 */
                return typeArgs.get(returnTypeArg);
            }
        }

        return m.getReturnType();
    }

    public boolean isResolvedExternally() {
        return resolvedExternally;
    }
}
