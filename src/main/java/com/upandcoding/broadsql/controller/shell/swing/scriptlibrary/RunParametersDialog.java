package com.upandcoding.broadsql.controller.shell.swing.scriptlibrary;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

import com.upandcoding.broadsql.controller.shell.scripts.ScriptArguments;

/**
 * Collects the arguments of a Script before {@code Run} - modeled on {@code JBrunoImportDialog}'s modal
 * {@code GridBagLayout} shape, the established convention for a small data-entry dialog in this codebase. Canceling
 * cancels execution.
 *
 * <p>SPRINT 0110A (spec section 17.7): one field per name declared by the Script's {@code -- @params:} lines, in
 * declaration order (the former {@code %N}-based fields are gone). Each field takes an argument value: a number, a
 * single-quoted string, {@code TRUE}, {@code FALSE}, {@code NULL} or one {@code ${variable}}. Run is enabled only
 * when every field is non-empty and syntactically valid ({@link #problems()}); the values are then passed as
 * {@code name=value} arguments, exactly as {@code @} receives them.
 */
public final class RunParametersDialog extends JDialog {

	private final List<String> names;
	private final List<JTextField> fields = new ArrayList<>();
	private final JLabel message = new JLabel(" ");
	private JButton runButton;
	private boolean confirmed;

	public RunParametersDialog(JFrame owner, List<String> parameterNames) {
		super(owner, "Run Parameters", true);
		this.names = List.copyOf(parameterNames);
		buildUi();
	}

	private void buildUi() {
		setLayout(new BorderLayout());
		JPanel form = new JPanel(new GridBagLayout());
		GridBagConstraints c = new GridBagConstraints();
		c.insets = new Insets(6, 6, 6, 6);
		c.fill = GridBagConstraints.HORIZONTAL;

		DocumentListener revalidate = new DocumentListener() {
			@Override
			public void insertUpdate(DocumentEvent e) {
				refreshState();
			}

			@Override
			public void removeUpdate(DocumentEvent e) {
				refreshState();
			}

			@Override
			public void changedUpdate(DocumentEvent e) {
				refreshState();
			}
		};
		for (int i = 0; i < names.size(); i++) {
			JTextField field = new JTextField(20);
			field.setName(names.get(i));
			field.getDocument().addDocumentListener(revalidate);
			fields.add(field);
			c.gridx = 0;
			c.gridy = i;
			c.weightx = 0;
			form.add(new JLabel(names.get(i)), c);
			c.gridx = 1;
			c.weightx = 1;
			form.add(field, c);
		}
		add(form, BorderLayout.CENTER);

		JPanel south = new JPanel(new BorderLayout());
		south.add(message, BorderLayout.CENTER);
		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		runButton = new JButton("Run");
		runButton.addActionListener(e -> {
			if (problems().isEmpty()) {
				confirmed = true;
				setVisible(false);
			}
		});
		JButton cancelButton = new JButton("Cancel");
		cancelButton.addActionListener(e -> {
			confirmed = false;
			setVisible(false);
		});
		buttons.add(runButton);
		buttons.add(cancelButton);
		south.add(buttons, BorderLayout.EAST);
		add(south, BorderLayout.SOUTH);
		refreshState();
		pack();
	}

	private void refreshState() {
		List<String> problems = problems();
		runButton.setEnabled(problems.isEmpty());
		message.setText(problems.isEmpty() ? " " : problems.get(0));
	}

	/** One message per field that is empty or not a valid argument value; empty when Run is allowed. */
	public List<String> problems() {
		List<String> problems = new ArrayList<>();
		for (int i = 0; i < names.size(); i++) {
			String problem = ScriptArguments.valueSyntaxProblem(fields.get(i).getText());
			if (problem != null) {
				problems.add(names.get(i) + ": " + problem);
			}
		}
		return problems;
	}

	/** Whether Run may be clicked (every field non-empty and valid). */
	public boolean isRunEnabled() {
		return runButton.isEnabled();
	}

	/** {@code false} means Cancel was chosen (or the dialog was closed) - the caller must not run. */
	public boolean isConfirmed() {
		return confirmed;
	}

	/** The fields, in declaration order (tests fill them). */
	List<JTextField> fields() {
		return fields;
	}

	/** The declared names, in order. */
	public List<String> names() {
		return names;
	}

	/** The arguments as {@code name=value ...}, in declaration order. */
	public String argumentText() {
		LinkedHashMap<String, String> values = new LinkedHashMap<>();
		for (int i = 0; i < names.size(); i++) {
			values.put(names.get(i), fields.get(i).getText());
		}
		return ScriptArguments.format(values);
	}
}
