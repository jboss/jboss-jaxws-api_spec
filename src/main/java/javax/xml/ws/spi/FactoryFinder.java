/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2005-2017 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://oss.oracle.com/licenses/CDDL+GPL-1.1
 * or LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */

package javax.xml.ws.spi;

import java.io.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.AccessController;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.xml.ws.WebServiceException;

class FactoryFinder {

	private static final String JBOSS_JAXWS_CLIENT_MODULE = "org.jboss.ws.jaxws-client";
    private static final Logger logger = Logger.getLogger("javax.xml.ws");

    private static final ServiceLoaderUtil.ExceptionHandler<WebServiceException> EXCEPTION_HANDLER =
            new ServiceLoaderUtil.ExceptionHandler<WebServiceException>() {
                @Override
                public WebServiceException createException(Throwable throwable, String message) {
                    return new WebServiceException(message, throwable);
                }
            };

    /**
     * Finds the implementation {@code Class} object for the given
     * factory name, or if that fails, finds the {@code Class} object
     * for the given fallback class name. The arguments supplied MUST be
     * used in order. If using the first argument is successful, the second
     * one will not be used.
     * <P>
     * This method is package private so that this code can be shared.
     *
     * @return the {@code Class} object of the specified message factory;
     *         may not be {@code null}
     *
     * @param factoryClass          the name of the factory to find, which is
     *                              a system property
     * @param fallbackClassName     the implementation class name, which is
     *                              to be used only if nothing else
     *                              is found; {@code null} to indicate that
     *                              there is no fallback class name
     * @exception WebServiceException if there is an error
     */
    @SuppressWarnings("unchecked")
    static <T> T find(Class<T> factoryClass, String fallbackClassName) {
        ClassLoader classLoader = ServiceLoaderUtil.contextClassLoader(EXCEPTION_HANDLER);

        T provider = ServiceLoaderUtil.firstByServiceLoader(factoryClass, logger, EXCEPTION_HANDLER);
        if (provider != null) return provider;

        String factoryId = factoryClass.getName();

        // try to read from $java.home/lib/jaxws.properties
        provider = (T) fromJDKProperties(factoryId, fallbackClassName, classLoader);
        if (provider != null) return provider;

        // Use the system property
        provider = (T) fromSystemProperty(factoryId, fallbackClassName, classLoader);
        if (provider != null) return provider;

        ClassLoader moduleClassLoader = getModuleClassLoader();
        if (moduleClassLoader != null) {
           try {
              String serviceId = "META-INF/services/" + factoryId;
              InputStream is = moduleClassLoader.getResourceAsStream(serviceId);
          
              if( is!=null ) {
                  BufferedReader rd =
                      new BufferedReader(new InputStreamReader(is, "UTF-8"));
          
                  String factoryClassName = rd.readLine();
                  rd.close();

                  if (factoryClassName != null &&
                      ! "".equals(factoryClassName)) {
                      return (T) ServiceLoaderUtil.newInstance(factoryClassName, factoryClassName, moduleClassLoader, EXCEPTION_HANDLER);
                  }
              }
          } catch( Exception ex ) {
          }
        }
        
        // handling Glassfish (platform specific default)
        if (isOsgi()) {
            return (T) lookupUsingOSGiServiceLoader(factoryId);
        }

        if (fallbackClassName == null) {
            throw new WebServiceException(
                "Provider for " + factoryId + " cannot be found", null);
        }

        return (T) ServiceLoaderUtil.newInstance(fallbackClassName,
                fallbackClassName, classLoader, EXCEPTION_HANDLER);
    }
    

    private static ClassLoader getModuleClassLoader() throws WebServiceException {
        try {
            final Class<?> moduleClass = Class.forName("org.jboss.modules.Module");
            final Class<?> moduleIdentifierClass = Class.forName("org.jboss.modules.ModuleIdentifier");
            final Class<?> moduleLoaderClass = Class.forName("org.jboss.modules.ModuleLoader");
            final Object moduleLoader;
            final SecurityManager sm = System.getSecurityManager();
            if (sm == null) {
                moduleLoader = moduleClass.getMethod("getBootModuleLoader").invoke(null);
            } else {
                try {
                    moduleLoader = AccessController.doPrivileged(new PrivilegedExceptionAction<Object>() {
                        public Object run() throws Exception {
                            return moduleClass.getMethod("getBootModuleLoader").invoke(null);
                        }
                    });
                } catch (PrivilegedActionException pae) {
                    throw (WebServiceException) pae.getException();
                }
            }
            final Object moduleIdentifier = moduleIdentifierClass.getMethod("create", String.class).invoke(null, JBOSS_JAXWS_CLIENT_MODULE);
            final Object module = moduleLoaderClass.getMethod("loadModule", moduleIdentifierClass).invoke(moduleLoader, moduleIdentifier);
            if (sm == null) {
                return (ClassLoader)moduleClass.getMethod("getClassLoader").invoke(module);
            }
            try {
                return AccessController.doPrivileged(new PrivilegedExceptionAction<ClassLoader>() {
                    @Override
                    public ClassLoader run() throws Exception {
                        return (ClassLoader) moduleClass.getMethod("getClassLoader").invoke(module);
                    }
                });
            } catch (PrivilegedActionException pae) {
                throw pae.getException();
            }
         } catch (ClassNotFoundException e) {
            //ignore, JBoss Modules might not be available at all
             return null;
         } catch (Exception e) {
            throw new WebServiceException(e);
         }
    }

    private static Object fromSystemProperty(String factoryId,
                                             String fallbackClassName,
                                             ClassLoader classLoader) {
        try {
            String systemProp = System.getProperty(factoryId);
            if (systemProp != null) {
                return ServiceLoaderUtil.newInstance(systemProp,
                        fallbackClassName, classLoader, EXCEPTION_HANDLER);
            }
        } catch (SecurityException ignored) {
        }
        return null;
    }

    private static Object fromJDKProperties(String factoryId,
                                            String fallbackClassName,
                                            ClassLoader classLoader) {
        Path path = null;
        try {
            String JAVA_HOME = System.getProperty("java.home");
            path = Paths.get(JAVA_HOME, "conf", "jaxws.properties");

            // to ensure backwards compatibility
            if (!Files.exists(path)) {
                path = Paths.get(JAVA_HOME, "lib", "jaxws.properties");
            }

            if (Files.exists(path)) {
                Properties props = new Properties();
                try (InputStream inStream = Files.newInputStream(path)) {
                    props.load(inStream);
                }
                String factoryClassName = props.getProperty(factoryId);
                return ServiceLoaderUtil.newInstance(factoryClassName,
                        fallbackClassName, classLoader, EXCEPTION_HANDLER);
            }
        } catch (Exception ignored) {
            logger.log(Level.SEVERE, "Error reading JAX-WS configuration from ["  + path +
                    "] file. Check it is accessible and has correct format.", ignored);
        }
        return null;
    }

    private static final String OSGI_SERVICE_LOADER_CLASS_NAME = "org.glassfish.hk2.osgiresourcelocator.ServiceLoader";

    private static boolean isOsgi() {
        try {
            Class.forName(OSGI_SERVICE_LOADER_CLASS_NAME);
            return true;
        } catch (ClassNotFoundException ignored) {
        }
        return false;
    }

    private static Object lookupUsingOSGiServiceLoader(String factoryId) {
        try {
            // Use reflection to avoid having any dependendcy on ServiceLoader class
            Class serviceClass = Class.forName(factoryId);
            Class[] args = new Class[]{serviceClass};
            Class target = Class.forName(OSGI_SERVICE_LOADER_CLASS_NAME);
            java.lang.reflect.Method m = target.getMethod("lookupProviderInstances", Class.class);
            java.util.Iterator iter = ((Iterable) m.invoke(null, (Object[]) args)).iterator();
            return iter.hasNext() ? iter.next() : null;
        } catch (Exception ignored) {
            // log and continue
            return null;
        }
    }

}
