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
 *     Lars Vogel <Lars.Vogel@vogella.com> - initial API and implementation
 *******************************************************************************/
package org.eclipse.pde.internal.ui.editor.text;

import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.swt.graphics.Color;

/**
 * The syntax colors of the PDE XML source viewers, taken from the JFace color
 * registry, which the workbench keeps in sync with the current theme.
 */
public final class XMLSyntaxColors {

	/** Color of {@code %key} values that refer to a localization file. */
	public static final String EXTERNALIZED_STRING_COLOR = "org.eclipse.pde.ui.externalizedStringColor"; //$NON-NLS-1$

	private XMLSyntaxColors() {
	}

	/**
	 * Returns the color with the given id, or {@code null} to draw in the
	 * viewer's foreground when the registry does not define it.
	 */
	public static Color get(String colorId) {
		return JFaceResources.getColorRegistry().get(colorId);
	}
}
