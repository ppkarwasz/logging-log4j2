/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
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
package org.apache.logging.log4j.osgi.tests;

import java.io.PrintStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.logging.log4j.osgi.tests.junit.OsgiRule;
import org.apache.logging.log4j.util.internal.ServiceLoaderUtil;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.osgi.framework.launch.FrameworkFactory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Tests a basic Log4J 'setup' in an OSGi container.
 */
public abstract class AbstractLoadBundleTest {

    private BundleContext bundleContext;

    @Rule
    public OsgiRule osgi = new OsgiRule(getFactory());

    /**
     * Called before each @Test.
     */
    @Before
    public void before() throws BundleException {
        bundleContext = osgi.getFramework().getBundleContext();
    }

    private Bundle installBundle(final String symbolicName) throws BundleException {
        // The links are generated by 'exam-maven-plugin'
        final String url = String.format("link:classpath:%s.link", symbolicName);
        return bundleContext.installBundle(url);
    }

    private Bundle getApiBundle() throws BundleException {
        return installBundle("org.apache.logging.log4j.api");
    }


    private Bundle getCoreBundle() throws BundleException {
        return installBundle("org.apache.logging.log4j.core");
    }

    private Bundle get12ApiBundle() throws BundleException {
        return installBundle("org.apache.logging.log4j.1.2-api");
    }

    private Bundle getApiTestsBundle() throws BundleException {
        return installBundle("org.apache.logging.log4j.api-test");
    }

    protected abstract FrameworkFactory getFactory();

    private void log(final Bundle dummy) throws ReflectiveOperationException {
        // use reflection to log in the context of the dummy bundle

        final Class<?> logManagerClass = dummy.loadClass("org.apache.logging.log4j.LogManager");
        final Method getLoggerMethod = logManagerClass.getMethod("getLogger", Class.class);

        final Class<?> loggerClass = dummy.loadClass("org.apache.logging.log4j.configuration.CustomConfiguration");

        final Object logger = getLoggerMethod.invoke(null, loggerClass);
        final Method errorMethod = logger.getClass().getMethod("error", Object.class);

        errorMethod.invoke(logger, "Test OK");
    }

    private PrintStream setupStream(final Bundle api, final PrintStream newStream) throws ReflectiveOperationException {
        // use reflection to access the classes internals and in the context of the api bundle

        final Class<?> statusLoggerClass = api.loadClass("org.apache.logging.log4j.status.StatusLogger");

        final Field statusLoggerField = statusLoggerClass.getDeclaredField("STATUS_LOGGER");
        statusLoggerField.setAccessible(true);
        final Object statusLoggerFieldValue = statusLoggerField.get(null);

        final Field loggerField = statusLoggerClass.getDeclaredField("logger");
        loggerField.setAccessible(true);
        final Object loggerFieldValue = loggerField.get(statusLoggerFieldValue);

        final Class<?> simpleLoggerClass = api.loadClass("org.apache.logging.log4j.simple.SimpleLogger");

        final Field streamField = simpleLoggerClass.getDeclaredField("stream");
        streamField.setAccessible(true);

        final PrintStream oldStream = (PrintStream) streamField.get(loggerFieldValue);

        streamField.set(loggerFieldValue, newStream);

        return oldStream;
    }

    private void start(final Bundle api, final Bundle core, final Bundle dummy) throws BundleException {
        api.start();
        core.start();
        dummy.start();
    }

    private void stop(final Bundle api, final Bundle core, final Bundle dummy) throws BundleException {
        dummy.stop();
        core.stop();
        api.stop();
    }

    private void uninstall(final Bundle api, final Bundle core, final Bundle dummy) throws BundleException {
        dummy.uninstall();
        core.uninstall();
        api.uninstall();
    }

    /**
     * Tests starting, then stopping, then restarting, then stopping, and finally uninstalling the API and Core bundles
     */
    @Test
    public void testApiCoreStartStopStartStop() throws BundleException, ReflectiveOperationException {

        final Bundle api = getApiBundle();
        final Bundle core = getCoreBundle();

        Assert.assertEquals("api is not in INSTALLED state", Bundle.INSTALLED, api.getState());
        Assert.assertEquals("core is not in INSTALLED state", Bundle.INSTALLED, core.getState());

        api.start();
        core.start();

        Assert.assertEquals("api is not in ACTIVE state", Bundle.ACTIVE, api.getState());
        Assert.assertEquals("core is not in ACTIVE state", Bundle.ACTIVE, core.getState());

        core.stop();
        api.stop();

        Assert.assertEquals("api is not in RESOLVED state", Bundle.RESOLVED, api.getState());
        Assert.assertEquals("core is not in RESOLVED state", Bundle.RESOLVED, core.getState());

        api.start();
        core.start();

        Assert.assertEquals("api is not in ACTIVE state", Bundle.ACTIVE, api.getState());
        Assert.assertEquals("core is not in ACTIVE state", Bundle.ACTIVE, core.getState());

        core.stop();
        api.stop();

        Assert.assertEquals("api is not in RESOLVED state", Bundle.RESOLVED, api.getState());
        Assert.assertEquals("core is not in RESOLVED state", Bundle.RESOLVED, core.getState());

        core.uninstall();
        api.uninstall();

        Assert.assertEquals("api is not in UNINSTALLED state", Bundle.UNINSTALLED, api.getState());
        Assert.assertEquals("core is not in UNINSTALLED state", Bundle.UNINSTALLED, core.getState());
    }

    /**
     * Tests LOG4J2-1637.
     */
    @Test
    public void testClassNotFoundErrorLogger() throws BundleException {

        final Bundle api = getApiBundle();
        final Bundle core = getCoreBundle();

        api.start();
        // fails if LOG4J2-1637 is not fixed
        try {
            core.start();
        }
        catch (final BundleException ex) {
            boolean shouldRethrow = true;
            final Throwable t = ex.getCause();
            if (t != null) {
                final Throwable t2 = t.getCause();
                if (t2 != null) {
                    final String cause = t2.toString();
                    final boolean result = cause.equals("java.lang.ClassNotFoundException: org.apache.logging.log4j.Logger") // Equinox
                                  || cause.equals("java.lang.ClassNotFoundException: org.apache.logging.log4j.Logger not found by org.apache.logging.log4j.core [2]"); // Felix
                    Assert.assertFalse("org.apache.logging.log4j package is not properly imported in org.apache.logging.log4j.core bundle, check that the package is exported from api and is not split between api and core", result);
                    shouldRethrow = !result;
                }
            }
            if (shouldRethrow) {
                throw ex; // rethrow if the cause of the exception is something else
            }
        }

        core.stop();
        api.stop();

        core.uninstall();
        api.uninstall();
    }

    /**
     * Tests the loading of the 1.2 Compatibility API bundle, its classes should be loadable from the Core bundle,
     * and the class loader should be the same between a class from core and a class from compat
     */
    @Test
    public void testLog4J12Fragement() throws BundleException, ReflectiveOperationException {

        final Bundle api = getApiBundle();
        final Bundle core = getCoreBundle();
        final Bundle compat = get12ApiBundle();

        api.start();
        core.start();

        final Class<?> coreClassFromCore = core.loadClass("org.apache.logging.log4j.core.Core");
        final Class<?> levelClassFrom12API = core.loadClass("org.apache.log4j.Level");
        final Class<?> levelClassFromAPI = core.loadClass("org.apache.logging.log4j.Level");

        Assert.assertEquals("expected 1.2 API Level to have the same class loader as Core", levelClassFrom12API.getClassLoader(), coreClassFromCore.getClassLoader());
        Assert.assertNotEquals("expected 1.2 API Level NOT to have the same class loader as API Level", levelClassFrom12API.getClassLoader(), levelClassFromAPI.getClassLoader());

        core.stop();
        api.stop();

        uninstall(api, core, compat);
    }

    /**
     * Tests whether the {@link ServiceLoaderUtil} finds services in other bundles.
     *
     * @throws BundleException
     * @throws ReflectiveOperationException
     */
    @Test
    public void testServiceLoader() throws BundleException, ReflectiveOperationException {
        final Bundle api = getApiBundle();
        final Bundle core = getCoreBundle();
        final Bundle apiTests = getApiTestsBundle();

        final Class<?> osgiServiceLocator = api.loadClass("org.apache.logging.log4j.util.OsgiServiceLocator");
        assertTrue("OsgiServiceLocator is active", (boolean) osgiServiceLocator.getMethod("isAvailable").invoke(null));

        api.start();
        core.start();
        assertEquals("api-tests is not in RESOLVED state", Bundle.RESOLVED, apiTests.getState());

        final Class<?> osgiServiceLocatorTest = api.loadClass("org.apache.logging.log4j.test.util.OsgiServiceLocatorTest");

        final Method loadProviders = osgiServiceLocatorTest.getDeclaredMethod("loadProviders");
        final Object obj = loadProviders.invoke(null);
        assertTrue(obj instanceof Stream);
        @SuppressWarnings("unchecked")
        final
                List<Object> services = ((Stream<Object>) obj).collect(Collectors.toList());
        assertEquals(1, services.size());
        assertEquals("org.apache.logging.log4j.core.impl.Log4jProvider", services.get(0).getClass().getName());

        core.stop();
        api.stop();
        assertEquals("api-tests is not in ACTIVE state", Bundle.RESOLVED, apiTests.getState());
        uninstall(apiTests, api, core);
        assertEquals("api-tests is not in ACTIVE state", Bundle.UNINSTALLED, apiTests.getState());
    }
}
