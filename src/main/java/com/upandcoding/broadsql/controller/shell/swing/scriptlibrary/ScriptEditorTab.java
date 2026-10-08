package com.upandcoding.broadsql.controller.shell.swing.scriptlibrary;

import java.awt.BorderLayout;

import javax.swing.JPanel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.undo.CannotRedoException;
import javax.swing.undo.CannotUndoException;
import javax.swing.undo.UndoManager;

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rtextarea.RTextScrollPane;

import com.upandcoding.broadsql.controller.shell.scriptlibrary.ScriptAsset;

/**
 * One open editor tab (SPRINT 0917-01): an {@link RSyntaxTextArea} bound to one {@link ScriptAsset},
 * tracking its own dirty state and the asset's last-known-saved snapshot. Tabs are keyed by
 * {@link ScriptAsset#assetId()}, never by path - see {@link ScriptEditorTabbedPane}.
 *
 * <p>Real editor logic (dirty tracking) lives here in a thin, directly-inspectable form, but this
 * class still constructs a real Swing component tree and is therefore not itself constructible in
 * this project's headless test environment - see
 * {@code docs/plans/SPRINT_0917-01_IMPLEMENTATION_PLAN.md}, "Headless vs. real GUI verification."
 */
public final class ScriptEditorTab extends JPanel {

	private final RSyntaxTextArea textArea;
	private final UndoManager undoManager = new UndoManager();
	private ScriptAsset asset;
	private boolean dirty;
	private Runnable dirtyListener;
	/** For a new, unsaved Script: the folder its first Save proposes ({@code null}: the library root). */
	private String suggestedFolder;

	String suggestedFolder() {
		return suggestedFolder;
	}

	void setSuggestedFolder(String suggestedFolder) {
		this.suggestedFolder = suggestedFolder;
	}

	public ScriptEditorTab(ScriptAsset asset) {
		super(new BorderLayout());
		this.asset = asset;

		textArea = new RSyntaxTextArea();
		textArea.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_SQL);
		textArea.setCodeFoldingEnabled(true);
		textArea.setAntiAliasingEnabled(true);
		textArea.setText(asset.content());
		textArea.setCaretPosition(0);

		RTextScrollPane scrollPane = new RTextScrollPane(textArea);
		scrollPane.setLineNumbersEnabled(true);
		add(scrollPane, BorderLayout.CENTER);

		// A standard javax.swing.undo.UndoManager, not RSyntaxTextArea's own convenience undo/redo
		// methods - keeps Undo/Redo on guaranteed-stable JDK/Swing API only.
		textArea.getDocument().addUndoableEditListener(undoManager);
		textArea.getDocument().addDocumentListener(new DocumentListener() {
			@Override
			public void insertUpdate(DocumentEvent e) {
				markDirty();
			}

			@Override
			public void removeUpdate(DocumentEvent e) {
				markDirty();
			}

			@Override
			public void changedUpdate(DocumentEvent e) {
				markDirty();
			}
		});
	}

	private void markDirty() {
		if (!dirty) {
			dirty = true;
			notifyListener();
		}
	}

	private void notifyListener() {
		if (dirtyListener != null) {
			dirtyListener.run();
		}
	}

	/** Invoked whenever this tab's dirty state changes - the tabbed pane uses this to refresh the tab title's {@code *} suffix. */
	public void setDirtyListener(Runnable dirtyListener) {
		this.dirtyListener = dirtyListener;
	}

	public boolean isDirty() {
		return dirty;
	}

	public String currentText() {
		return textArea.getText();
	}

	public RSyntaxTextArea textArea() {
		return textArea;
	}

	public boolean canUndo() {
		return undoManager.canUndo();
	}

	public boolean canRedo() {
		return undoManager.canRedo();
	}

	public void undo() {
		try {
			undoManager.undo();
		} catch (CannotUndoException ignored) {
			// Nothing to undo - the menu/button is expected to already be checking canUndo() first.
		}
	}

	public void redo() {
		try {
			undoManager.redo();
		} catch (CannotRedoException ignored) {
			// Nothing to redo - see undo() above.
		}
	}

	/** The asset as of the last successful load/save - not the current unsaved buffer (see {@link #currentText()}). */
	public ScriptAsset asset() {
		return asset;
	}

	/** Call after a successful Save or an accepted external reload - updates the tracked baseline and clears dirty. */
	public void markSaved(ScriptAsset savedAsset) {
		this.asset = savedAsset;
		dirty = false;
		notifyListener();
	}

	/**
	 * Updates only the tracked identity (path/display name after a rename made elsewhere) without
	 * touching the editor buffer or the dirty flag - a rename must never silently discard, or silently
	 * mark clean, unsaved in-progress edits (see the "false-dirty" precedent {@code JApiSettingsFrame}
	 * documents avoiding for its own tabs).
	 */
	public void retarget(ScriptAsset renamedIdentity) {
		this.asset = renamedIdentity;
		notifyListener();
	}

	/** Replaces the buffer with {@code freshAsset}'s content (external reload / Restore) and clears dirty. */
	public void reloadContent(ScriptAsset freshAsset) {
		textArea.setText(freshAsset.content());
		textArea.setCaretPosition(0);
		undoManager.discardAllEdits();
		this.asset = freshAsset;
		dirty = false;
		notifyListener();
	}
}
