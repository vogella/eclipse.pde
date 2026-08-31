/*******************************************************************************
 * Copyright (c) 2026 Lars Vogel and others.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     Lars Vogel - initial API and implementation
 *******************************************************************************/
package org.eclipse.pde.ui.tests.target;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

import org.eclipse.core.resources.IStorage;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.PlatformObject;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.pde.core.target.ITargetDefinition;
import org.eclipse.pde.core.target.ITargetLocation;
import org.eclipse.pde.internal.core.target.TargetDefinitionPersistenceHelper;
import org.eclipse.pde.internal.ui.editor.targetdefinition.TargetEditor;
import org.eclipse.pde.ui.tests.PDETestCase;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IPersistableElement;
import org.eclipse.ui.IStorageEditorInput;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.IDE;
import org.junit.After;
import org.junit.Test;

/**
 * Tests that the target editor opens a target definition that is only
 * available as storage, for example a revision from the history view.
 */
public class TargetEditorStorageInputTests extends PDETestCase {

	private static final String EDITOR_ID = "org.eclipse.pde.ui.targetEditor";

	private IEditorPart editor;

	@After
	public void closeEditor() {
		if (editor != null) {
			editor.getSite().getPage().closeEditor(editor, false);
		}
	}

	@Test
	public void testOpenFromStorage() throws Exception {
		ITargetDefinition definition = AbstractTargetTest.getTargetService().newTarget();
		definition.setName("from-storage");
		definition.setTargetLocations(new ITargetLocation[] { AbstractTargetTest.getTargetService()
				.newDirectoryLocation(IPath.fromOSString("${eclipse_home}/plugins").toString()) });
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		TargetDefinitionPersistenceHelper.persistXML(definition, out);

		IWorkbenchPage page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
		editor = IDE.openEditor(page, new BytesEditorInput("history.target", out.toByteArray()), EDITOR_ID);

		assertTrue("Expected the target editor, got " + editor.getClass(), editor instanceof TargetEditor);
		ITargetDefinition target = ((TargetEditor) editor).getTarget();
		assertNotNull(target);
		assertEquals("from-storage", target.getName());
		assertEquals(1, target.getTargetLocations().length);
		assertFalse(editor.isDirty());
		assertTrue(editor.isSaveAsAllowed());
	}

	private static final class BytesEditorInput extends PlatformObject implements IStorageEditorInput {

		private final String name;
		private final byte[] content;

		BytesEditorInput(String name, byte[] content) {
			this.name = name;
			this.content = content;
		}

		@Override
		public IStorage getStorage() {
			return new IStorage() {
				@Override
				public InputStream getContents() throws CoreException {
					return new ByteArrayInputStream(content);
				}

				@Override
				public IPath getFullPath() {
					return null;
				}

				@Override
				public String getName() {
					return name;
				}

				@Override
				public boolean isReadOnly() {
					return true;
				}

				@Override
				public <T> T getAdapter(Class<T> adapter) {
					return null;
				}
			};
		}

		@Override
		public boolean exists() {
			return true;
		}

		@Override
		public ImageDescriptor getImageDescriptor() {
			return null;
		}

		@Override
		public String getName() {
			return name;
		}

		@Override
		public IPersistableElement getPersistable() {
			return null;
		}

		@Override
		public String getToolTipText() {
			return name;
		}
	}
}
