package com.fileverifier;

import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.util.*;
import java.util.List;

public class FileVerifierFrame extends JFrame {

    private JTextField sourcePathField;
    private JTextField destPathField;
    private JProgressBar progressBar;
    private JTextArea logArea;
    private JScrollPane logScrollPane;
    private DefaultListModel<DirectoryEntry> sourceListModel;
    private DefaultListModel<DirectoryEntry> destListModel;
    private JList<DirectoryEntry> sourceList;
    private JList<DirectoryEntry> destList;
    private JLabel statusLabel;

    private File currentSourceDir = null;
    private File currentDestDir = null;
    private boolean scanning = false;
    private FileSyncEngine.JobType currentJobType = FileSyncEngine.JobType.COPY_AND_VERIFY;

    public FileVerifierFrame() {
        setTitle("File Verifier & Copier");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1100, 800);
        setLocationRelativeTo(null);
        initComponents();
    }

    private void initComponents() {
        setLayout(new BorderLayout(5, 5));
        add(createTopPanel(), BorderLayout.NORTH);
        add(createCenterPanel(), BorderLayout.CENTER);
        add(createBottomPanel(), BorderLayout.SOUTH);
    }

    private JPanel createTopPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(new EmptyBorder(10, 10, 10, 10));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 4, 4, 4);
        gbc.anchor = GridBagConstraints.WEST;

        // Source path
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weightx = 0;
        panel.add(new JLabel("Source Directory:"), gbc);

        gbc.gridx = 1;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        sourcePathField = new JTextField(30);
        sourcePathField.setEditable(false);
        sourcePathField.setBorder(BorderFactory.createCompoundBorder(
            sourcePathField.getBorder(),
            new EmptyBorder(3, 3, 3, 3)));
        panel.add(sourcePathField, gbc);

        gbc.gridx = 2;
        gbc.weightx = 0;
        gbc.fill = GridBagConstraints.NONE;
        JButton sourceBrowseBtn = new JButton("Browse...");
        sourceBrowseBtn.addActionListener(e -> browseDirectory(true));
        panel.add(sourceBrowseBtn, gbc);

        // Destination path
        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.weightx = 0;
        panel.add(new JLabel("Destination Directory:"), gbc);

        gbc.gridx = 1;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        destPathField = new JTextField(30);
        destPathField.setEditable(false);
        destPathField.setBorder(BorderFactory.createCompoundBorder(
            destPathField.getBorder(),
            new EmptyBorder(3, 3, 3, 3)));
        panel.add(destPathField, gbc);

        gbc.gridx = 2;
        gbc.weightx = 0;
        gbc.fill = GridBagConstraints.NONE;
        JButton destBrowseBtn = new JButton("Browse...");
        destBrowseBtn.addActionListener(e -> browseDirectory(false));
        panel.add(destBrowseBtn, gbc);

        // Job type toggle
        gbc.gridx = 0;
        gbc.gridy = 2;
        gbc.gridwidth = 1;
        gbc.weightx = 0;
        panel.add(new JLabel("Job Type:"), gbc);

        gbc.gridx = 1;
        gbc.gridwidth = 2;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.NONE;
        JPanel togglePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        togglePanel.setBorder(BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(Color.GRAY), "Operation Mode"));

        JButton copyVerifyBtn = new JButton("Copy & Verify");
        copyVerifyBtn.setFocusPainted(false);
        copyVerifyBtn.setBackground(new Color(59, 130, 246));
        copyVerifyBtn.setForeground(Color.WHITE);
        copyVerifyBtn.setPreferredSize(new Dimension(150, 28));
        copyVerifyBtn.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(30, 100, 200), 2),
            new EmptyBorder(3, 8, 3, 8)));

        JButton verifyOnlyBtn = new JButton("Verify Only");
        verifyOnlyBtn.setFocusPainted(false);
        verifyOnlyBtn.setBackground(Color.WHITE);
        verifyOnlyBtn.setForeground(Color.GRAY);
        verifyOnlyBtn.setPreferredSize(new Dimension(120, 28));
        verifyOnlyBtn.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(Color.GRAY, 1),
            new EmptyBorder(3, 8, 3, 8)));

        copyVerifyBtn.addActionListener(e -> {
            currentJobType = FileSyncEngine.JobType.COPY_AND_VERIFY;
            copyVerifyBtn.setBackground(new Color(59, 130, 246));
            copyVerifyBtn.setForeground(Color.WHITE);
            copyVerifyBtn.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(30, 100, 200), 2),
                new EmptyBorder(3, 8, 3, 8)));
            verifyOnlyBtn.setBackground(Color.WHITE);
            verifyOnlyBtn.setForeground(Color.GRAY);
            verifyOnlyBtn.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Color.GRAY, 1),
                new EmptyBorder(3, 8, 3, 8)));
        });

        verifyOnlyBtn.addActionListener(e -> {
            currentJobType = FileSyncEngine.JobType.VERIFY_ONLY;
            verifyOnlyBtn.setBackground(new Color(59, 130, 246));
            verifyOnlyBtn.setForeground(Color.WHITE);
            verifyOnlyBtn.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(30, 100, 200), 2),
                new EmptyBorder(3, 8, 3, 8)));
            copyVerifyBtn.setBackground(Color.WHITE);
            copyVerifyBtn.setForeground(Color.GRAY);
            copyVerifyBtn.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Color.GRAY, 1),
                new EmptyBorder(3, 8, 3, 8)));
        });

        togglePanel.add(copyVerifyBtn);
        togglePanel.add(verifyOnlyBtn);
        panel.add(togglePanel, gbc);

        // Scan button
        gbc.gridx = 0;
        gbc.gridy = 3;
        gbc.gridwidth = 3;
        gbc.weightx = 0;
        gbc.fill = GridBagConstraints.NONE;
        gbc.anchor = GridBagConstraints.CENTER;
        JButton scanBtn = new JButton("Scan Files");
        scanBtn.setPreferredSize(new Dimension(200, 32));
        scanBtn.addActionListener(e -> scanDirectories());
        panel.add(scanBtn, gbc);

        return panel;
    }

    private JPanel createCenterPanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 0));
        panel.setBorder(new EmptyBorder(5, 10, 5, 10));

        // Source directory list
        JPanel sourcePanel = new JPanel(new BorderLayout());
        sourcePanel.setBorder(BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(Color.GRAY), "Source Files"));
        sourceListModel = new DefaultListModel<>();
        sourceList = new JList<>(sourceListModel);
        sourceList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        sourceList.setVisibleRowCount(15);
        sourceList.setCellRenderer(new DirectoryCellRenderer());
        JScrollPane sourceScrollPane = new JScrollPane(sourceList);
        sourceScrollPane.setBorder(null);
        sourcePanel.add(sourceScrollPane, BorderLayout.CENTER);

        // Source list actions
        JPanel sourceActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 2));
        JButton selectAllSourceBtn = new JButton("Select All");
        selectAllSourceBtn.addActionListener(e -> selectAll(sourceListModel, sourceList, true));
        JButton deselectAllSourceBtn = new JButton("Deselect All");
        deselectAllSourceBtn.addActionListener(e -> selectAll(sourceListModel, sourceList, false));
        sourceActions.add(selectAllSourceBtn);
        sourceActions.add(deselectAllSourceBtn);
        sourcePanel.add(sourceActions, BorderLayout.SOUTH);

        // Source list checkbox toggle
        sourceList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int index = sourceList.locationToIndex(e.getPoint());
                if (index >= 0 && SwingUtilities.isLeftMouseButton(e)) {
                    DirectoryEntry entry = sourceListModel.getElementAt(index);
                    entry.selected = !entry.selected;
                    SwingUtilities.invokeLater(() -> {
                        Rectangle bounds = sourceList.getCellBounds(index, index);
                        if (bounds != null) {
                            sourceList.repaint(bounds);
                        }
                    });
                }
            }
        });

        // Dest directory list
        JPanel destPanel = new JPanel(new BorderLayout());
        destPanel.setBorder(BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(Color.GRAY), "Destination Files"));
        destListModel = new DefaultListModel<>();
        destList = new JList<>(destListModel);
        destList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        destList.setVisibleRowCount(15);
        destList.setCellRenderer(new DirectoryCellRenderer());
        JScrollPane destScrollPane = new JScrollPane(destList);
        destScrollPane.setBorder(null);
        destPanel.add(destScrollPane, BorderLayout.CENTER);

        // Dest list actions
        JPanel destActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 2));
        JButton selectAllDestBtn = new JButton("Select All");
        selectAllDestBtn.addActionListener(e -> selectAll(destListModel, destList, true));
        JButton deselectAllDestBtn = new JButton("Deselect All");
        deselectAllDestBtn.addActionListener(e -> selectAll(destListModel, destList, false));
        destActions.add(selectAllDestBtn);
        destActions.add(deselectAllDestBtn);
        destPanel.add(destActions, BorderLayout.SOUTH);

        // Dest list checkbox toggle
        destList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int index = destList.locationToIndex(e.getPoint());
                if (index >= 0 && SwingUtilities.isLeftMouseButton(e)) {
                    DirectoryEntry entry = destListModel.getElementAt(index);
                    entry.selected = !entry.selected;
                    SwingUtilities.invokeLater(() -> {
                        Rectangle bounds = destList.getCellBounds(index, index);
                        if (bounds != null) {
                            destList.repaint(bounds);
                        }
                    });
                }
            }
        });

        // Split pane
        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, sourcePanel, destPanel);
        splitPane.setResizeWeight(0.5);
        splitPane.setDividerLocation(0.5);
        splitPane.setDividerSize(8);

        panel.add(splitPane, BorderLayout.CENTER);
        return panel;
    }

    private JPanel createBottomPanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(new EmptyBorder(5, 10, 10, 10));

        // Progress bar
        progressBar = new JProgressBar(0, 100);
        progressBar.setStringPainted(true);
        progressBar.setPreferredSize(new Dimension(0, 25));
        progressBar.setValue(0);
        panel.add(progressBar, BorderLayout.NORTH);

        // Status label
        statusLabel = new JLabel("Ready. Select source and destination directories, then click Scan Files.");
        statusLabel.setBorder(new EmptyBorder(2, 0, 2, 0));
        panel.add(statusLabel, BorderLayout.CENTER);

        // Log area
        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setFont(new Font("Monospaced", Font.PLAIN, 11));
        logArea.setBackground(new Color(30, 30, 30));
        logArea.setForeground(new Color(180, 180, 180));
        logArea.setLineWrap(true);
        logArea.setWrapStyleWord(true);
        logScrollPane = new JScrollPane(logArea);
        logScrollPane.setPreferredSize(new Dimension(0, 150));
        panel.add(logScrollPane, BorderLayout.SOUTH);

        // Run button
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 5));
        JButton runBtn = new JButton("Run Job");
        runBtn.setPreferredSize(new Dimension(130, 32));
        runBtn.setBackground(new Color(34, 197, 94));
        runBtn.setForeground(Color.WHITE);
        runBtn.setFocusPainted(false);
        runBtn.addActionListener(e -> runJob());
        buttonPanel.add(runBtn);

        JButton clearLogBtn = new JButton("Clear Log");
        clearLogBtn.addActionListener(e -> logArea.setText(""));
        buttonPanel.add(clearLogBtn);

        panel.add(buttonPanel, BorderLayout.EAST);

        return panel;
    }

    private void browseDirectory(boolean isSource) {
        JTextField field = isSource ? sourcePathField : destPathField;

        // Determine initial directory: prefer the other field's path
        File initialDir = findValidStartDir(isSource);

        JFileChooser fileChooser = new JFileChooser(initialDir);
        fileChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        fileChooser.setDialogTitle(isSource ? "Select Source Directory" : "Select Destination Directory");

        // Capture path at the exact moment the approve button is clicked
        final String[] capturedPath = new String[1];
        ActionListener approveListener = e -> {
            if (JFileChooser.APPROVE_SELECTION.equals(e.getActionCommand())) {
                File dir = fileChooser.getCurrentDirectory();
                capturedPath[0] = dir.getAbsolutePath();
            }
        };
        fileChooser.addActionListener(approveListener);

        int result = fileChooser.showDialog(this, "Select Folder");
        fileChooser.removeActionListener(approveListener);

        if (result == JFileChooser.APPROVE_OPTION) {
            String chosenPath;
            if (capturedPath[0] != null) {
                chosenPath = capturedPath[0];
            } else {
                chosenPath = fileChooser.getCurrentDirectory().getAbsolutePath();
            }

            field.setText(chosenPath);
            File resolved = new File(chosenPath);
            if (isSource) {
                currentSourceDir = resolved;
            } else {
                currentDestDir = resolved;
            }
        }
    }

    private File findValidStartDir(boolean isSource) {
        // The "other" field text (if source is being browsed, look at dest, and vice versa)
        String otherText = (isSource ? destPathField : sourcePathField).getText().trim();
        String ownText = (isSource ? sourcePathField : destPathField).getText().trim();

        // Try the other directory first
        if (!otherText.isEmpty()) {
            File dir = new File(otherText);
            if (dir.exists() && dir.isDirectory()) {
                return dir.getParentFile() != null ? dir.getParentFile() : dir;
            }
            // Walk up parent chain to find a valid directory
            File parent = dir.getParentFile();
            while (parent != null) {
                if (parent.exists() && parent.isDirectory()) {
                    return parent;
                }
                parent = parent.getParentFile();
            }
        }

        // Fall back to own directory
        if (!ownText.isEmpty()) {
            File dir = new File(ownText);
            if (dir.exists() && dir.isDirectory()) {
                return dir.getParentFile() != null ? dir.getParentFile() : dir;
            }
            File parent = dir.getParentFile();
            while (parent != null) {
                if (parent.exists() && parent.isDirectory()) {
                    return parent;
                }
                parent = parent.getParentFile();
            }
        }

        return null;
    }

    private void scanDirectories() {
        String sourcePath = sourcePathField.getText().trim();
        String destPath = destPathField.getText().trim();

        if (sourcePath.isEmpty() || destPath.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                "Please select both source and destination directories.",
                "Missing Paths", JOptionPane.WARNING_MESSAGE);
            return;
        }

        File sourceDir = new File(sourcePath);
        File destDir = new File(destPath);

        if (!sourceDir.exists() || !sourceDir.isDirectory()) {
            JOptionPane.showMessageDialog(this,
                "Source directory does not exist: " + sourcePath,
                "Invalid Source", JOptionPane.ERROR_MESSAGE);
            return;
        }

        if (!destDir.exists() || !destDir.isDirectory()) {
            JOptionPane.showMessageDialog(this,
                "Destination directory does not exist: " + destPath,
                "Invalid Destination", JOptionPane.ERROR_MESSAGE);
            return;
        }

        if (scanning) {
            return;
        }

        scanning = true;
        statusLabel.setText("Scanning files...");
        logArea.setText("");

        sourceListModel.clear();
        destListModel.clear();

        SwingWorker<Void, Void> worker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() {
                List<String> sourceFiles = DirectoryScanner.scanFiles(sourceDir);
                List<String> destFiles = DirectoryScanner.scanFiles(destDir);

                SwingUtilities.invokeLater(() -> {
                    for (String file : sourceFiles) {
                        sourceListModel.addElement(new DirectoryEntry(file, true));
                    }
                    for (String file : destFiles) {
                        destListModel.addElement(new DirectoryEntry(file, true));
                    }
                    scanning = false;
                    statusLabel.setText(String.format("Scanned: %d source files, %d destination files",
                        sourceFiles.size(), destFiles.size()));
                    log("Scanned " + sourceFiles.size() + " source files and " +
                        destFiles.size() + " destination files.");
                });
                return null;
            }
        };
        worker.execute();
    }

    private void selectAll(DefaultListModel<DirectoryEntry> model, JList<DirectoryEntry> list, boolean select) {
        for (int i = 0; i < model.size(); i++) {
            model.getElementAt(i).selected = select;
        }
        SwingUtilities.invokeLater(() -> list.repaint());
    }

    private void runJob() {
        String sourcePath = sourcePathField.getText().trim();
        String destPath = destPathField.getText().trim();

        if (sourcePath.isEmpty() || destPath.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                "Please select both source and destination directories.",
                "Missing Paths", JOptionPane.WARNING_MESSAGE);
            return;
        }

        if (sourceListModel.isEmpty() && destListModel.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                "Please scan directories first.",
                "No Directories", JOptionPane.WARNING_MESSAGE);
            return;
        }

        File sourceDir = new File(sourcePath);
        File destDir = new File(destPath);

        // Collect selected files as relative paths
        Set<String> selectedSource = new LinkedHashSet<>();
        String sourceAbs = sourceDir.getAbsolutePath();
        for (int i = 0; i < sourceListModel.size(); i++) {
            DirectoryEntry entry = sourceListModel.getElementAt(i);
            if (entry.selected) {
                String rel = entry.path.startsWith(sourceAbs)
                    ? entry.path.substring(sourceAbs.length())
                    : entry.path;
                if (rel.startsWith(File.separator)) rel = rel.substring(1);
                rel = rel.replace(File.separator, "/");
                selectedSource.add(rel);
            }
        }

        Set<String> selectedDest = new LinkedHashSet<>();
        String destAbs = destDir.getAbsolutePath();
        for (int i = 0; i < destListModel.size(); i++) {
            DirectoryEntry entry = destListModel.getElementAt(i);
            if (entry.selected) {
                String rel = entry.path.startsWith(destAbs)
                    ? entry.path.substring(destAbs.length())
                    : entry.path;
                if (rel.startsWith(File.separator)) rel = rel.substring(1);
                rel = rel.replace(File.separator, "/");
                selectedDest.add(rel);
            }
        }

        FileSyncEngine.JobType jobType = currentJobType;

        log("Starting " + (jobType == FileSyncEngine.JobType.COPY_AND_VERIFY ? "Copy & Verify" : "Verify Only") + " job...");
        log("Source: " + sourcePath);
        log("Destination: " + destPath);
        log("Selected source files: " + selectedSource.size());
        log("Selected destination files: " + selectedDest.size());

        progressBar.setValue(0);
        progressBar.setString("Running...");

        FileSyncEngine.runJob(sourceDir, destDir, jobType, selectedSource, selectedDest,
            new FileSyncEngine.ProgressListener() {
                @Override
                public void onProgressUpdate(String message, int percent) {
                    SwingUtilities.invokeLater(() -> {
                        statusLabel.setText(message);
                        log(message);
                        if (percent >= 0) {
                            progressBar.setValue(percent);
                        }
                    });
                }

                @Override
                public void onFileProcessed(String fileName, String status) {
                    SwingUtilities.invokeLater(() -> {
                        String icon = switch (status) {
                            case "match" -> "[OK]";
                            case "mismatch" -> "[MISMATCH]";
                            case "missing" -> "[MISSING]";
                            case "error" -> "[ERROR]";
                            default -> "[INFO]";
                        };
                        log("  " + icon + " " + fileName);
                    });
                }

                @Override
                public void onCompletion(FileSyncEngine.JobResult result) {
                    SwingUtilities.invokeLater(() -> {
                        progressBar.setString(result.getStatus() == FileSyncEngine.JobStatus.COMPLETED ?
                            "Complete" : "Failed");
                        progressBar.setValue(result.getStatus() == FileSyncEngine.JobStatus.COMPLETED ? 100 : 0);

                        if (result.getStatus() == FileSyncEngine.JobStatus.COMPLETED) {
                            statusLabel.setText("Job completed successfully.");
                            log("\n=== JOB COMPLETED ===");
                            log("Files checked: " + result.getFilesChecked());
                            log("Files matched: " + result.getFilesMatched());
                            log("Files mismatched: " + result.getFilesMismatched());
                            log("Files missing: " + result.getFilesMissing());
                            log("Files copied: " + result.getFilesCopied());
                        } else {
                            statusLabel.setText("Job failed.");
                            log("\n=== JOB FAILED ===");
                            log("Error: " + result.getMessage());
                            for (String error : result.getErrors()) {
                                log("  " + error);
                            }
                        }
                    });
                }
            });
    }

    private void log(String message) {
        logArea.append(message + "\n");
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    // Custom cell renderer for directory entries with checkboxes
    private class DirectoryCellRenderer extends JCheckBox implements ListCellRenderer<DirectoryEntry> {

        private final Border selectedBorder = BorderFactory.createLineBorder(new Color(59, 130, 246), 1);
        private final Border unselectedBorder = BorderFactory.createEmptyBorder(1, 1, 1, 1);

        public DirectoryCellRenderer() {
            setOpaque(true);
            setFont(new Font("SansSerif", Font.PLAIN, 12));
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends DirectoryEntry> list,
                                                        DirectoryEntry value,
                                                        int index,
                                                        boolean isSelected,
                                                        boolean cellHasFocus) {
            setSelected(value.selected);
            setText(value.path);

            if (isSelected) {
                setBackground(new Color(219, 234, 254));
                setForeground(new Color(30, 64, 175));
            } else {
                setBackground(Color.WHITE);
                setForeground(index % 2 == 0 ? Color.BLACK : new Color(60, 60, 60));
            }

            return this;
        }
    }

    // Data class for directory entries
    public static class DirectoryEntry {
        public final String path;
        public boolean selected;

        public DirectoryEntry(String path, boolean selected) {
            this.path = path;
            this.selected = selected;
        }

        @Override
        public String toString() {
            return path;
        }
    }
}
