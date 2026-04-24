/*******************************************************************************
 * Copyright (c) 2026 Contributors to the Eclipse Foundation
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.pde.internal.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.eclipse.osgi.service.resolver.BundleDescription;
import org.junit.Test;
import org.osgi.framework.Version;

/**
 * Verifies that the transitive-dependency sort in
 * {@link RequiredPluginsClasspathContainer} is deterministic when multiple
 * bundles share a symbolic name. Without the version tiebreaker, the
 * stable-sort source order — which comes from a {@code HashSet} iteration —
 * leaks through and causes classpath containers to flip between IDE restarts
 * (e.g. {@code jakarta.annotation-api_1.3.5} vs {@code _2.1.1}).
 */
public class RequiredPluginsClasspathContainerComparatorTest {

	@Test
	public void higherVersionFirstForSameSymbolicName() {
		BundleDescription low = mockBundle("jakarta.annotation-api", "1.3.5");
		BundleDescription high = mockBundle("jakarta.annotation-api", "2.1.1");

		// Source list in ascending version order. Stream.sorted is stable, so
		// a comparator that only compares symbolic names would preserve this
		// order — that is the bug.
		List<BundleDescription> sortedAscendingInput = List.of(low, high).stream()
				.sorted(RequiredPluginsClasspathContainer.SYMBOLIC_NAME_THEN_HIGHER_VERSION)
				.toList();
		assertThat(sortedAscendingInput).containsExactly(high, low);

		// Source list in descending version order — result must be identical.
		List<BundleDescription> sortedDescendingInput = List.of(high, low).stream()
				.sorted(RequiredPluginsClasspathContainer.SYMBOLIC_NAME_THEN_HIGHER_VERSION)
				.toList();
		assertThat(sortedDescendingInput).containsExactly(high, low);
	}

	@Test
	public void differentSymbolicNamesSortedAlphabetically() {
		BundleDescription a = mockBundle("bundle.a", "1.0.0");
		BundleDescription b = mockBundle("bundle.b", "1.0.0");

		assertThat(List.of(b, a).stream().sorted(RequiredPluginsClasspathContainer.SYMBOLIC_NAME_THEN_HIGHER_VERSION)
				.toList()).containsExactly(a, b);
	}

	private static BundleDescription mockBundle(String symbolicName, String version) {
		BundleDescription bd = mock(BundleDescription.class);
		when(bd.getSymbolicName()).thenReturn(symbolicName);
		when(bd.getVersion()).thenReturn(Version.parseVersion(version));
		return bd;
	}
}
