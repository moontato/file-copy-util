package com.fileverifier;

import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.nio.file.Path;
import java.util.*;

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

    private boolean scanning = false;
    private boolean jobRunning = false;
    private JButton runButton;
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
                    entry.setSelected(!entry.selected());
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
                    entry.setSelected(!entry.selected());
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
        runButton = runBtn;

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

        Path source = sourceDir.toPath();
        Path dest = destDir.toPath();

        scanning = true;
        statusLabel.setText("Scanning files...");
        logArea.setText("");

        sourceListModel.clear();
        destListModel.clear();

        SwingWorker<ScanPair, Void> worker = new SwingWorker<>() {
            @Override
            protected ScanPair doInBackground() {
                return new ScanPair(DirectoryScanner.scanFiles(source),
                    DirectoryScanner.scanFiles(dest));
            }

            @Override
            protected void done() {
                try {
                    ScanPair result = get();
                    for (FileEntry entry : result.source().files()) {
                        sourceListModel.addElement(new DirectoryEntry(entry, true));
                    }
                    for (FileEntry entry : result.dest().files()) {
                        destListModel.addElement(new DirectoryEntry(entry, true));
                    }
                    statusLabel.setText(String.format("Scanned: %d source files, %d destination files",
                        result.source().files().size(), result.dest().files().size()));
                    log(String.format("Scanned %d source files and %d destination files.",
                        result.source().files().size(), result.dest().files().size()));
                    if (result.source().ignoredDirs() > 0 || result.dest().ignoredDirs() > 0) {
                        log("Ignored directories skipped: "
                            + result.source().ignoredDirs() + " in source, "
                            + result.dest().ignoredDirs() + " in destination.");
                    }
                    if (result.source().skippedLinks() > 0 || result.dest().skippedLinks() > 0) {
                        log("Symlinks skipped (not hashed, not offered for copying): "
                            + result.source().skippedLinks() + " in source, "
                            + result.dest().skippedLinks() + " in destination.");
                    }
                    for (String warning : result.source().warnings()) {
                        log("  warning: " + warning);
                    }
                } catch (Exception e) {
                    String message = rootCauseMessage(e);
                    statusLabel.setText("Scan failed: " + message);
                    log("Scan failed: " + message);
                } finally {
                    scanning = false;
                }
            }
        };
        worker.execute();
    }

    private record ScanPair(DirectoryScanner.ScanResult source, DirectoryScanner.ScanResult dest) {
    }

    private static String rootCauseMessage(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
    }

    private void selectAll(DefaultListModel<DirectoryEntry> model, JList<DirectoryEntry> list, boolean select) {
        for (int i = 0; i < model.size(); i++) {
            model.getElementAt(i).setSelected(select);
        }
        SwingUtilities.invokeLater(() -> list.repaint());
    }

    private void runJob() {
        if (jobRunning) {
            return;
        }

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

        if (!sourceDir.isDirectory()) {
            JOptionPane.showMessageDialog(this, "Source directory does not exist: " + sourcePath,
                "Invalid Source", JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (!destDir.isDirectory()) {
            JOptionPane.showMessageDialog(this, "Destination directory does not exist: " + destPath,
                "Invalid Destination", JOptionPane.ERROR_MESSAGE);
            return;
        }

        // Selection is a set of relative paths straight from the scanner's FileEntry - an empty
        // set means "no files", so it must never reach the engine.
        Set<String> selectedSource = new LinkedHashSet<>();
        for (int i = 0; i < sourceListModel.size(); i++) {
            DirectoryEntry entry = sourceListModel.getElementAt(i);
            if (entry.selected()) {
                selectedSource.add(entry.relPath());
            }
        }

        Set<String> selectedDest = new LinkedHashSet<>();
        for (int i = 0; i < destListModel.size(); i++) {
            DirectoryEntry entry = destListModel.getElementAt(i);
            if (entry.selected()) {
                selectedDest.add(entry.relPath());
            }
        }

        FileSyncEngine.JobType jobType = currentJobType;

        if (selectedSource.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                "No source files are selected, so nothing would be copied or verified.\n"
                    + "Tick at least one file, or use Select All.",
                "Empty Selection", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (jobType == FileSyncEngine.JobType.VERIFY_ONLY) {
            // Mirror the engine's work-set rule so the user gets a dialog instead of a failed job.
            Set<String> destinationRows = new HashSet<>();
            for (int i = 0; i < destListModel.size(); i++) {
                destinationRows.add(destListModel.getElementAt(i).relPath());
            }
            int planned = 0;
            for (String rel : selectedSource) {
                if (selectedDest.contains(rel) || !destinationRows.contains(rel)) {
                    planned++;
                }
            }
            if (planned == 0) {
                JOptionPane.showMessageDialog(this,
                    "Verify Only would check 0 files: every selected source file exists in the "
                        + "destination but is unticked in the destination list. Tick the destination "
                        + "rows you want checked.",
                    "Empty Selection", JOptionPane.WARNING_MESSAGE);
                return;
            }
        }

        log("Starting " + (jobType == FileSyncEngine.JobType.COPY_AND_VERIFY ? "Copy & Verify" : "Verify Only") + " job...");
        log("Source: " + sourcePath);
        log("Destination: " + destPath);
        log("Selected source files: " + selectedSource.size());
        if (jobType == FileSyncEngine.JobType.VERIFY_ONLY) {
            log("Selected destination files: " + selectedDest.size());
        }

        jobRunning = true;
        runButton.setEnabled(false);
        progressBar.setValue(0);
        progressBar.setString("Running...");

        FileSyncEngine.runJob(sourceDir, destDir, jobType, selectedSource, selectedDest,
            new FileSyncEngine.ProgressListener() {
                @Override
                public void onPhase(String message) {
                    SwingUtilities.invokeLater(() -> log(message));
                }

                @Override
                public void onProgress(int percent, String statusText) {
                    SwingUtilities.invokeLater(() -> {
                        statusLabel.setText(statusText);
                        progressBar.setValue(Math.max(0, Math.min(100, percent)));
                    });
                }

                @Override
                public void onFileProcessed(String relPath, String status, String srcHash, String dstHash) {
                    SwingUtilities.invokeLater(() -> {
                        String icon = switch (status) {
                            case "match" -> "[OK]       ";
                            case "mismatch" -> "[MISMATCH] ";
                            case "missing" -> "[MISSING]  ";
                            case "copied" -> "[COPIED]   ";
                            case "error" -> "[ERROR]    ";
                            default -> "[INFO]     ";
                        };
                        StringBuilder line = new StringBuilder("  ").append(icon).append(relPath);
                        if (!srcHash.isEmpty()) {
                            line.append("  src=").append(srcHash);
                        }
                        if (!dstHash.isEmpty()) {
                            line.append("  dst=").append(dstHash);
                        }
                        log(line.toString());
                    });
                }

                @Override
                public void onCompletion(FileSyncEngine.JobResult result) {
                    SwingUtilities.invokeLater(() -> {
                        jobRunning = false;
                        runButton.setEnabled(true);

                        if (result.isSuccess()) {
                            progressBar.setString("Complete");
                            progressBar.setValue(100);
                            statusLabel.setText("Job completed.");
                            log("");
                            log("=== JOB COMPLETED ===");
                            log(result.message());
                        } else {
                            progressBar.setString("Failed");
                            statusLabel.setText("Job failed.");
                            log("");
                            log("=== JOB FAILED ===");
                            log("Reason: " + result.message());
                            if (result.rsyncExitCode() != 0) {
                                log("rsync exit code: " + result.rsyncExitCode());
                            }
                        }

                        log(String.format("Totals: checked=%d copied=%d matched=%d mismatched=%d missing=%d",
                            result.filesChecked(), result.filesCopied(), result.filesMatched(),
                            result.filesMismatched(), result.filesMissing()));
                        for (String warning : result.warnings()) {
                            log("  warning: " + warning);
                        }
                        for (String error : result.errors()) {
                            log("  error: " + error);
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
            setSelected(value.selected());
            setText(value.relPath());

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

    /** List row: a scanned file plus its include/exclude state. */
    public static final class DirectoryEntry {
        private final FileEntry entry;
        private boolean selected;

        public DirectoryEntry(FileEntry entry, boolean selected) {
            this.entry = entry;
            this.selected = selected;
        }

        public FileEntry entry() {
            return entry;
        }

        public String relPath() {
            return entry.relPath();
        }

        public boolean selected() {
            return selected;
        }

        public void setSelected(boolean selected) {
            this.selected = selected;
        }

        @Override
        public String toString() {
            return entry.relPath();
        }
    }
}
