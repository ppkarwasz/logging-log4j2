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
package org.apache.logging.log4j.core.appender.internal;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Method;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.appender.ConsoleAppender.Target;
import org.apache.logging.log4j.core.util.CloseShieldOutputStream;
import org.apache.logging.log4j.core.util.Loader;
import org.apache.logging.log4j.core.util.Throwables;
import org.apache.logging.log4j.status.StatusLogger;
import org.apache.logging.log4j.util.PropertiesUtil;
import org.fusesource.jansi.AnsiConsole;
import org.fusesource.jansi.AnsiPrintStream;
import org.fusesource.jansi.AnsiType;
import org.fusesource.jansi.io.AnsiOutputStream;
import org.fusesource.jansi.io.AnsiOutputStream.IoRunnable;
import org.fusesource.jansi.io.AnsiOutputStream.ZeroWidthSupplier;
import org.fusesource.jansi.io.AnsiProcessor;
import org.fusesource.jansi.io.WindowsAnsiProcessor;

import static org.fusesource.jansi.internal.Kernel32.GetConsoleMode;
import static org.fusesource.jansi.internal.Kernel32.GetStdHandle;
import static org.fusesource.jansi.internal.Kernel32.STD_ERROR_HANDLE;
import static org.fusesource.jansi.internal.Kernel32.STD_OUTPUT_HANDLE;
import static org.fusesource.jansi.internal.Kernel32.SetConsoleMode;

public abstract class AnsiSupport {

    private static final String JANSI1_WRAPPER_CLASS = "org.fusesource.jansi.WindowsAnsiOutputStream";
    private static final String JANSI2_WRAPPER_CLASS = "org.fusesource.jansi.io.AnsiOutputStream";

    private static final Logger LOGGER = StatusLogger.getLogger();
    public static AnsiSupport INSTANCE = useJansi2() ? new Jansi2AnsiSupport()
            : useJansi1() ? new Jansi1AnsiSupport() : new NoAnsiSupport();

    public static boolean isAnsiEnabled() {
        return !PropertiesUtil.getProperties().isOsWindows() || useJansi2() || useJansi1();
    }

    private static boolean isJansiEnabled() {
        return !PropertiesUtil.getProperties().getBooleanProperty("log4j.skipJansi", true);
    }

    private static boolean useJansi1() {
        return isJansiEnabled() && Loader.isClassAvailable(JANSI1_WRAPPER_CLASS);
    }

    private static boolean useJansi2() {
        return isJansiEnabled() && Loader.isClassAvailable(JANSI2_WRAPPER_CLASS);
    }

    public abstract OutputStream directStream(final Target target);

    public abstract OutputStream wrapStream(final OutputStream outputStream, final Target target);

    private static final class Jansi2AnsiSupport extends AnsiSupport {

        private static final int ENABLE_VIRTUAL_TERMINAL_PROCESSING = 0x0004;

        public OutputStream directStream(final Target target) {
            return target == Target.SYSTEM_OUT ? AnsiConsole.out() : AnsiConsole.err();
        }

        public OutputStream wrapStream(OutputStream outputStream, Target target) {
            // In order to support Jansi's system properties,
            // we deduce most properties AnsiOutputStream parameters from
            // AnsiConsole.out()/err().
            final AnsiPrintStream ps = target == Target.SYSTEM_OUT ? AnsiConsole.out() : AnsiConsole.err();
            AnsiProcessor processor = null;
            IoRunnable installer = null;
            IoRunnable uninstaller = null;
            final AnsiType type = ps.getType();
            if (PropertiesUtil.getProperties().isOsWindows()) {
                final long console = GetStdHandle(target == Target.SYSTEM_OUT ? STD_OUTPUT_HANDLE : STD_ERROR_HANDLE);
                if (type == AnsiType.VirtualTerminal) {
                    final int[] mode = new int[1];
                    GetConsoleMode(console, mode);
                    if ((mode[0] & ENABLE_VIRTUAL_TERMINAL_PROCESSING) != 0) {
                        installer = () -> SetConsoleMode(console, mode[0] | ENABLE_VIRTUAL_TERMINAL_PROCESSING);
                        uninstaller = () -> SetConsoleMode(console, mode[0]);
                    }
                }
                if (type == AnsiType.Emulation) {
                    try {
                        processor = new WindowsAnsiProcessor(outputStream, console);
                    } catch (final IOException e) {
                        // this happens when the stdout is being redirected to a file.
                        // Use the AnsiProcessor to strip out the ANSI escape sequences.
                        LOGGER.debug("Failed to create Jansi processor. Disabling ANSI escape sequences.", e);
                        processor = new AnsiProcessor(outputStream);
                    }
                }
            }
            return new CloseShieldOutputStream(new AnsiOutputStream(outputStream, new ZeroWidthSupplier(), ps.getMode(),
                    processor, ps.getType(), ps.getColors(), target.getDefaultCharset(), installer, uninstaller,
                    ps.isResetAtUninstall()));
        }

    }

    private static final class Jansi1AnsiSupport extends AnsiSupport {

        public OutputStream directStream(final Target target) {
            return target == Target.SYSTEM_OUT ? AnsiConsole.out() : AnsiConsole.err();
        }

        public OutputStream wrapStream(OutputStream outputStream, Target target) {
            final String methodName = target == Target.SYSTEM_OUT ? "wrapOutputStream" : "wrapErrorOutputStream";
            try {
                final Method method = AnsiConsole.class.getDeclaredMethod(methodName, OutputStream.class);
                return new CloseShieldOutputStream((OutputStream) method.invoke(null, outputStream));
            } catch (final NoSuchMethodException nsme) {
                LOGGER.warn("{} is missing the {} method. ", AnsiConsole.class.getName(), methodName);
            } catch (final Exception ex) {
                LOGGER.warn("Unable to invoke {}#{}.", AnsiConsole.class.getName(), methodName,
                        Throwables.getRootCause(ex));
            }
            return outputStream;
        }

    }

    private static final class NoAnsiSupport extends AnsiSupport {

        @Override
        public OutputStream directStream(Target target) {
            return new FileOutputStream(target == Target.SYSTEM_OUT ? FileDescriptor.out : FileDescriptor.err);
        }

        @Override
        public OutputStream wrapStream(OutputStream outputStream, Target target) {
            return outputStream;
        }

    }
}
