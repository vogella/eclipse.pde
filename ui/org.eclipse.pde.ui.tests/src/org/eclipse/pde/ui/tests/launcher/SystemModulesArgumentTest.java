/*******************************************************************************
 * Copyright (c) 2026 vogella GmbH and others.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     vogella GmbH - initial API and implementation
 *******************************************************************************/
package org.eclipse.pde.ui.tests.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import org.eclipse.jdt.launching.IVMInstall;
import org.eclipse.pde.internal.launching.launcher.VMHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class SystemModulesArgumentTest {

	private Path javaHome;

	@BeforeEach
	public void createJavaHome() throws IOException {
		javaHome = Files.createTempDirectory("java-home");
	}

	@AfterEach
	public void deleteJavaHome() throws IOException {
		try (Stream<Path> paths = Files.walk(javaHome)) {
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
				Files.delete(path);
			}
		}
	}

	@Test
	public void incubatorModulesAreLeftOut() throws IOException {
		Files.writeString(javaHome.resolve("release"), """
				JAVA_VERSION="25"
				MODULES="java.base java.desktop jdk.incubator.vector jdk.unsupported"
				""");
		assertEquals("java.base,java.desktop,jdk.unsupported", VMHelper.getSystemModules(vm(javaHome)));
	}

	@Test
	public void allSystemWithoutModuleList() throws IOException {
		assertEquals("ALL-SYSTEM", VMHelper.getSystemModules(vm(javaHome)));
		Files.writeString(javaHome.resolve("release"), "JAVA_VERSION=\"25\"\n");
		assertEquals("ALL-SYSTEM", VMHelper.getSystemModules(vm(javaHome)));
		assertEquals("ALL-SYSTEM", VMHelper.getSystemModules(vm(null)));
	}

	private static IVMInstall vm(Path location) {
		IVMInstall vm = mock(IVMInstall.class);
		when(vm.getInstallLocation()).thenReturn(location == null ? null : location.toFile());
		return vm;
	}
}
