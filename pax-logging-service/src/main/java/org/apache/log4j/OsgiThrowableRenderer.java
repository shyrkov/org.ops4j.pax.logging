/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.log4j;

import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.log4j.spi.ThrowableRenderer;
import org.ops4j.pax.logging.util.OsgiUtil;
import org.osgi.framework.Bundle;

/**
 * Enhanced implementation of ThrowableRenderer.
 * It displays bundle information if available next to the class name in the stack trace.
 */
public final class OsgiThrowableRenderer implements ThrowableRenderer {

    private SecurityManagerEx sm = new SecurityManagerEx();

    /**
     * {@inheritDoc}
     */
    public String[] doRender(final Throwable throwable) {
        try {
            List<String> lines = new ArrayList<>();
            doRender(throwable, null, lines);
           return lines.toArray(new String[lines.size()]);
        } catch(Exception ex) {
            // Ignore
        }
        return DefaultThrowableRenderer.render(throwable);
    }

    private static final Method classContextMethod;
    static {
        Method method;
        try {
            // Karaf 4.1
            method = Exception.class.getDeclaredMethod("classContext");
            method.setAccessible(true);
        } catch (NoSuchMethodException e1) {
            try {
                // Karaf < 4.1
                method = Exception.class.getMethod("getClassContext");
            } catch (NoSuchMethodException e2) {
                method = null;
            }
        }
        classContextMethod = method;
    }

    private void doRender(final Throwable throwable, StackTraceElement[]  causedTrace, List<String> lines) {
        StackTraceElement[] elements = throwable.getStackTrace();
        Map<String, Object> classMap = new HashMap<>();
        Class[] classCtx;
        try {
            classCtx = (Class[]) classContextMethod.invoke(throwable);
        } catch (Throwable e) {
            classCtx = sm.getClassContext();
        }
        Class lastClass = null;
        for (int i = 0; i < elements.length && i < classCtx.length; i++) {
            Class clazz = classCtx[classCtx.length - 1 - i];
            if (elements[elements.length - 1 - i].getClassName().equals(clazz.getName())) {
                String classDetails = getClassDetail(clazz);
                classMap.put(clazz.getName(), classDetails);
                lastClass = clazz;
            } else if (lastClass != null) {
                try {
                    ClassLoader cl = lastClass.getClassLoader();
                    if (cl != null) {
                        clazz = OsgiUtil.loadClass(cl, elements[elements.length - 1 - i].getClassName());
                        String classDetails = getClassDetail(clazz);
                        classMap.put(clazz.getName(), classDetails);
                        lastClass = clazz;
                    }
                } catch (Exception e) {
                    break;
                }
            } else {
                break;
            }
        }
        if (causedTrace != null) {
            int m = elements.length-1, n = causedTrace.length-1;
            while (m >= 0 && n >=0 && elements[m].equals(causedTrace[n])) {
                m--; n--;
            }
            int framesInCommon = elements.length - 1 - m;
            lines.add("Caused by: " + throwable.toString());
            for (int i=0; i <= m; i++) {
                lines.add(formatElement(elements[i], classMap));
            }
            if (framesInCommon != 0) {
                lines.add("\t... " + framesInCommon + " more");
            }
        } else {
            lines.add(throwable.toString());
            for (StackTraceElement element : elements) {
                lines.add(formatElement(element, classMap));
            }
        }
        try {
            Throwable[] causes = (Throwable[]) throwable.getClass().getMethod("getCauses").invoke(throwable);
            for (Throwable cause : causes) {
                doRender(cause, elements, lines);
            }
        } catch (Exception e) {
            Throwable cause = throwable.getCause();
            if (cause != null) {
                doRender(cause, elements, lines);
            }
        }
    }

    /**
     * Format one element from stack trace.
     * @param element element, may not be null.
     * @param classMap map of class name to location.
     * @return string representation of element.
     */
    private String formatElement(final StackTraceElement element, final Map<String, Object> classMap) {
        StringBuilder buf = new StringBuilder("\tat ");
        buf.append(element);
        String className = element.getClassName();
        Object classDetails = classMap.get(className);
        if (classDetails == null) {
            try {
                Class<?> cls = findClass(className);
                classDetails = getClassDetail(cls);
                classMap.put(className, classDetails);
            } catch (Throwable th) {
                // Ignore
            }
        }
        if (classDetails != null) {
            buf.append(classDetails);
        }
        return buf.toString();
    }

    private String getClassDetail(Class cls) {
        try {
            Bundle bundle = OsgiUtil.getBundleOrNull(cls);
            if (bundle != null) {
                StringBuilder buf = new StringBuilder();
                buf.append('[');
                buf.append(bundle.getBundleId());
                buf.append(":");
                buf.append(bundle.getSymbolicName());
                buf.append(":");
                buf.append(bundle.getVersion().toString());
                buf.append(']');
                return buf.toString();
            }
        } catch (Exception e) {
            // Ignore
        }

        StringBuilder buf = new StringBuilder();
        buf.append('[');
        try {
            CodeSource source = cls.getProtectionDomain().getCodeSource();
            if (source != null) {
                URL locationURL = source.getLocation();
                if (locationURL != null) {
                    //
                    //   if a file: URL
                    //
                    if ("file".equals(locationURL.getProtocol())) {
                        String path = locationURL.getPath();
                        if (path != null) {
                            //
                            //  find the last file separator character
                            //
                            int lastSlash = path.lastIndexOf('/');
                            int lastBack = path.lastIndexOf(File.separatorChar);
                            if (lastBack > lastSlash) {
                                lastSlash = lastBack;
                            }
                            //
                            //  if no separator or ends with separator (a directory)
                            //     then output the URL, otherwise just the file name.
                            //
                            if (lastSlash <= 0 || lastSlash == path.length() - 1) {
                                buf.append(locationURL);
                            } else {
                                buf.append(path.substring(lastSlash + 1));
                            }
                        }
                    } else {
                        buf.append(locationURL);
                    }
                }
            }
        } catch(SecurityException ex) {
            // Ignore
        }
        buf.append(':');
        Package pkg = cls.getPackage();
        if (pkg != null) {
            String implVersion = pkg.getImplementationVersion();
            if (implVersion != null) {
                buf.append(implVersion);
            }
        }
        buf.append(']');
        return buf.toString();
    }

    /**
     * Find class given class name.
     * @param className class name, may not be null.
     * @return class, will not be null.
     * @throws ClassNotFoundException thrown if class can not be found.
     */
    private Class<?> findClass(final String className) throws ClassNotFoundException {
        try {
            return OsgiUtil.loadClass(Thread.currentThread().getContextClassLoader(), className);
        } catch (ClassNotFoundException e) {
            try {
                return Class.forName(className);
            } catch (ClassNotFoundException e1) {
                return OsgiUtil.loadClass(getClass().getClassLoader(), className);
            }
        }
    }

    private static class SecurityManagerEx extends SecurityManager
    {
        public Class[] getClassContext()
        {
            return super.getClassContext();
        }
    }
}
