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
package org.apache.logging.log4j.cli.generator;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedWriter;
import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.cli.generator.Generate.LevelInfo;
import org.apache.logging.log4j.cli.generator.Generate.Type;
import org.apache.logging.log4j.message.Message;
import org.apache.logging.log4j.message.MessageFactory;
import org.apache.logging.log4j.spi.ExtendedLogger;
import org.apache.logging.log4j.spi.LoggingSystemProperty;
import org.apache.logging.log4j.test.TestLogger;
import org.apache.logging.log4j.util.MessageSupplier;
import org.apache.logging.log4j.util.Strings;
import org.apache.logging.log4j.util.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("functional")
public class GenerateExtendedLoggerTest {

    private static final String TEST_SOURCE = "target/test-classes/org/apache/logging/log4j/core/MyExtendedLogger.java";

    @BeforeAll
    static void beforeClass() {
        System.setProperty(
                LoggingSystemProperty.Constant.LOGGER_CONTEXT_FACTORY_CLASS,
                "org.apache.logging.log4j.test.TestLoggerContextFactory");
    }

    @AfterAll
    public static void afterClass() {
        File file = new File(TEST_SOURCE);
        final File parent = file.getParentFile();
        if (file.exists()) {
            file.delete();
        }
        file = new File(parent, "MyExtendedLogger.class");
        if (file.exists()) {
            file.delete();
        }
    }

    @Test
    @SuppressWarnings("ReturnValueIgnored")
    public void testGenerateSource() throws Exception {
        final String CLASSNAME = "org.apache.logging.log4j.core.MyExtendedLogger";

        // generate custom logger source
        final List<LevelInfo> levels =
                Arrays.asList(new LevelInfo("DIAG", 350), new LevelInfo("NOTICE", 450), new LevelInfo("VERBOSE", 550));
        final Path testSource = Paths.get(TEST_SOURCE);
        Files.createDirectories(testSource.getParent());
        try (final BufferedWriter writer = Files.newBufferedWriter(testSource, UTF_8)) {
            Generate.generateSource(Type.EXTEND, CLASSNAME, levels, writer);
        }

        final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        final DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        final List<String> errors = new ArrayList<>();
        try (final StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, null, null)) {
            final Iterable<? extends JavaFileObject> compilationUnits =
                    fileManager.getJavaFileObjectsFromFiles(Collections.singletonList(testSource.toFile()));
            final String classPath = System.getProperty("jdk.module.path");
            final List<String> optionList = new ArrayList<>();
            if (Strings.isNotBlank(classPath)) {
                optionList.add("-classpath");
                optionList.add(classPath);
            }
            // compile generated source
            compiler.getTask(null, fileManager, diagnostics, optionList, null, compilationUnits)
                    .call();

            // check we don't have any compilation errors
            for (final Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
                if (diagnostic.getKind() == Diagnostic.Kind.ERROR) {
                    errors.add(String.format("Compile error: %s%n", diagnostic.getMessage(Locale.getDefault())));
                }
            }
        }
        assertTrue(errors.isEmpty(), errors.toString());

        // load the compiled class
        final Class<?> cls = Class.forName(CLASSNAME);

        // check that all factory methods exist and are static
        assertTrue(Modifier.isStatic(cls.getMethod("create").getModifiers()));
        assertTrue(Modifier.isStatic(cls.getMethod("create", Class.class).getModifiers()));
        assertTrue(Modifier.isStatic(cls.getMethod("create", Object.class).getModifiers()));
        assertTrue(Modifier.isStatic(cls.getMethod("create", String.class).getModifiers()));
        assertTrue(Modifier.isStatic(
                cls.getMethod("create", Class.class, MessageFactory.class).getModifiers()));
        assertTrue(Modifier.isStatic(
                cls.getMethod("create", Object.class, MessageFactory.class).getModifiers()));
        assertTrue(Modifier.isStatic(
                cls.getMethod("create", String.class, MessageFactory.class).getModifiers()));

        // check that the extended log methods exist
        final String[] extendedMethods = {"diag", "notice", "verbose"};
        for (final String name : extendedMethods) {
            assertDoesNotThrow(() -> {
                cls.getMethod(name, Marker.class, Message.class, Throwable.class);
                cls.getMethod(name, Marker.class, Object.class, Throwable.class);
                cls.getMethod(name, Marker.class, String.class, Throwable.class);
                cls.getMethod(name, Marker.class, Message.class);
                cls.getMethod(name, Marker.class, Object.class);
                cls.getMethod(name, Marker.class, String.class);
                cls.getMethod(name, Message.class);
                cls.getMethod(name, Object.class);
                cls.getMethod(name, String.class);
                cls.getMethod(name, Message.class, Throwable.class);
                cls.getMethod(name, Object.class, Throwable.class);
                cls.getMethod(name, String.class, Throwable.class);
                cls.getMethod(name, String.class, Object[].class);
                cls.getMethod(name, Marker.class, String.class, Object[].class);

                // 2.4 lambda support
                cls.getMethod(name, Marker.class, MessageSupplier.class);
                cls.getMethod(name, Marker.class, MessageSupplier.class, Throwable.class);
                cls.getMethod(name, Marker.class, String.class, Supplier[].class);
                cls.getMethod(name, Marker.class, Supplier.class);
                cls.getMethod(name, Marker.class, Supplier.class, Throwable.class);
                cls.getMethod(name, MessageSupplier.class);
                cls.getMethod(name, MessageSupplier.class, Throwable.class);
                cls.getMethod(name, String.class, Supplier[].class);
                cls.getMethod(name, Supplier.class);
                cls.getMethod(name, Supplier.class, Throwable.class);
            });
        }

        final List<String> allLevels = new ArrayList<>();
        // now see if it actually works...
        final Method create = cls.getMethod("create", String.class);
        final Object extendedLogger = create.invoke(null, "X.Y.Z");
        int n = 0;
        for (final String name : extendedMethods) {
            final Method method = cls.getMethod(name, String.class);
            method.invoke(extendedLogger, "This is message " + n++);
            allLevels.add(name.toUpperCase(Locale.ROOT));
        }

        // This logger extends o.a.l.log4j.spi.ExtendedLogger,
        // so all the standard logging methods can be used as well
        final String[] standardMethods = {"trace", "debug", "info", "warn", "error", "fatal"};
        final ExtendedLogger logger = (ExtendedLogger) extendedLogger;
        for (final String name : standardMethods) {
            final Method method = cls.getMethod(name, String.class);
            method.invoke(extendedLogger, "This is message " + n++);
            allLevels.add(name.toUpperCase(Locale.ROOT));
        }

        final TestLogger underlying = (TestLogger) LogManager.getLogger("X.Y.Z");
        final List<String> lines = underlying.getEntries();
        for (int i = 0; i < lines.size(); i++) {
            assertEquals(" " + allLevels.get(i) + " This is message " + i, lines.get(i));
        }
    }
}
