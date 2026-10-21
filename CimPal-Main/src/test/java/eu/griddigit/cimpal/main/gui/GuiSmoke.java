/*
 * Copyright (c) 2020-2026 gridDigIt Kft.
 * Licensed under the EUPL-1.2-or-later.
 * SPDX-License-Identifier: EUPL-1.2+
 */
package eu.griddigit.cimpal.main.gui;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.TimeUnit;

/**
 * Marks a GUI smoke test class: tagged {@code gui} (it needs a display), run by {@link FxTestSupport}
 * one test at a time, and failed after 60 s rather than left hanging on a dialog.
 * <p>
 * The {@code gui} tests run in full in the nightly workflow. A test that also carries
 * {@code @Tag("smoke")} runs on every build, CI included (the Main pom excludes {@code gui & !smoke}
 * by default). Exclude all of them on a machine without a display with {@code -DexcludedGroups=gui}.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Tag("gui")
@ExtendWith(FxTestSupport.class)
@Execution(ExecutionMode.SAME_THREAD)
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public @interface GuiSmoke {
}
