package com.fileverifier;

import javax.swing.*;
import javax.swing.border.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.prefs.Preferences;

/**
 * Swing front end. All copy/verify logic lives in {@link FileSyncEngine}; this class owns the
 * selection, the scan options, the progress display, and the results table, and never touches
 * rsync or hashing directly.
 */
public final class FileVerifierFrame extends JFrame {

    private static final long serialVersionUID = 1L;

    /** Log tail buffer: keeps the newest lines and drops the rest (3.6). */
    private final TailLog tailLog = new TailLog();

    private static final String PREF_SOURCE = "sourceDir";
    private static final String PREF_DEST = "destDir";
    private static final String PREF_IGNORES = "ignorePatterns";
    private static final String PREF_HIDDEN = "includeHidden";
    private static final String PREF_SYMLINKS = "symlinkPolicy";
    private static final String PREF_TIMEOUT = "timeoutSeconds";
    private static final String PREF_DRY_RUN = "dryRun";
    private static final String PREF_EXTRA = "detectExtra";
    private static final String PREF_JOB_TYPE = "jobType";

    private final Preferences prefs = Preferences.userRoot().node("com/fileverifier");

    private JTextField sourcePathField;
    private JTextField destPathField;
    private JTextField ignoreField;
    private JCheckBox hiddenCheck;
    private JCheckBox extraCheck;
    private JComboBox<ScanOptions.SymlinkPolicy> symlinkCombo;
    private JProgressBar progressBar;
    private JLabel statusLabel;
    private JTextArea logArea;
    private JScrollPane logScrollPane;

    private JTabbedPane outputTabs;
    private JTable resultsTable;
    private JLabel resultsTabTitle;
    private JCheckBox onlyProblemsCheck;

    private final FileListModel sourceModel = new FileListModel();
    private final FileListModel destModel = new FileListModel();
    private JList<DirectoryEntry> sourceList;
    private JList<DirectoryEntry> destList;
    private JTextField sourceFilterField;
    private JTextField destFilterField;

    private JButton sourceBrowseButton;
    private JButton destBrowseButton;
    private JButton scanButton;
    private JButton copyVerifyButton;
    private JButton verifyOnlyButton;
    private JButton runButton;
    private JButton cancelButton;
    private JButton exportCsvButton;
    private JButton exportJsonButton;
    private JTextField jobTimeoutField;
    private JCheckBox dryRunCheck;
    private Timer livenessTimer;

    private boolean scanning = false;
    private boolean scanOptionsStale = false;
    private FileSyncEngine.JobType currentJobType = FileSyncEngine.JobType.COPY_AND_VERIFY;
    private JLabel workSetLabel;

    /** One engine per window: it owns at most one job at a time. */
    private final FileSyncEngine engine = new FileSyncEngine();

    /** Kept so the Results tab can be re-filtered or exported after the job ends. */
    private FileSyncEngine.JobResult lastJobResult;

    public FileVerifierFrame() {
        setTitle("File Verifier & Copier");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1150, 820);
        setLocationRelativeTo(null);
        initComponents();
        restorePreferences();

        livenessTimer = new Timer(1000, e -> updateLiveness());
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                savePreferences();
                engine.close();
            }
        });
    }

    // ------------------------------------------------------------------ layout

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

        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weightx = 0;
        panel.add(new JLabel("Source Directory:"), gbc);

        gbc.gridx = 1;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        sourcePathField = new JTextField(30);
        sourcePathField.setEditable(false);
        panel.add(sourcePathField, gbc);

        gbc.gridx = 2;
        gbc.weightx = 0;
        gbc.fill = GridBagConstraints.NONE;
        sourceBrowseButton = new JButton("Browse...");
        sourceBrowseButton.addActionListener(e -> browseDirectory(true));
        panel.add(sourceBrowseButton, gbc);

        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.weightx = 0;
        panel.add(new JLabel("Destination Directory:"), gbc);

        gbc.gridx = 1;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        destPathField = new JTextField(30);
        destPathField.setEditable(false);
        panel.add(destPathField, gbc);

        gbc.gridx = 2;
        gbc.weightx = 0;
        gbc.fill = GridBagConstraints.NONE;
        destBrowseButton = new JButton("Browse...");
        destBrowseButton.addActionListener(e -> browseDirectory(false));
        panel.add(destBrowseButton, gbc);

        // Scan options (3.5): what the walk skips, and what it does with symlinks.
        gbc.gridx = 0;
        gbc.gridy = 2;
        gbc.weightx = 0;
        panel.add(new JLabel("Ignore (globs, comma separated):"), gbc);

        gbc.gridx = 1;
        gbc.gridy = 2;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        ignoreField = new JTextField(String.join(", ", ScanOptions.DEFAULT_IGNORED_DIRS));
        ignoreField.setToolTipText("Glob patterns matched against relative paths and names, e.g. "
            + "\"*.tmp\", \"node_modules\", \"sub/*.log\"");
        ignoreField.getDocument().addDocumentListener(new SimpleDocumentFilter(
            this::noteScanOptionsChanged, null));
        panel.add(ignoreField, gbc);

        gbc.gridx = 2;
        gbc.gridy = 2;
        gbc.weightx = 0;
        gbc.fill = GridBagConstraints.NONE;
        hiddenCheck = new JCheckBox("Include hidden files", true);
        // A ChangeListener, not an ActionListener: JCheckBox fires ActionEvent only for a click, so a
        // programmatically changed box (or a restored preference) would otherwise go unnoticed.
        hiddenCheck.addChangeListener(e -> noteScanOptionsChanged());
        panel.add(hiddenCheck, gbc);

        gbc.gridx = 0;
        gbc.gridy = 3;
        gbc.weightx = 0;
        panel.add(new JLabel("Symbolic links:"), gbc);

        gbc.gridx = 1;
        gbc.gridy = 3;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.NONE;
        symlinkCombo = new JComboBox<>(new ScanOptions.SymlinkPolicy[]{
            ScanOptions.SymlinkPolicy.SKIP, ScanOptions.SymlinkPolicy.LINK, ScanOptions.SymlinkPolicy.FOLLOW
        });
        symlinkCombo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                setText(symlinkLabel((ScanOptions.SymlinkPolicy) value));
                return this;
            }
        });
        symlinkCombo.addActionListener(e -> noteScanOptionsChanged());
        panel.add(symlinkCombo, gbc);

        gbc.gridx = 2;
        gbc.gridy = 3;
        gbc.weightx = 0;
        gbc.fill = GridBagConstraints.NONE;
        extraCheck = new JCheckBox("Report destination-only files", true);
        extraCheck.setToolTipText("Verify Only lists files that exist at the destination but not in the "
            + "selected source as EXTRA.");
        panel.add(extraCheck, gbc);

        gbc.gridx = 0;
        gbc.gridy = 4;
        gbc.gridwidth = 3;
        gbc.weightx = 0;
        gbc.fill = GridBagConstraints.NONE;
        gbc.anchor = GridBagConstraints.CENTER;

        JPanel modeAndScan = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 0));
        JPanel togglePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        togglePanel.setBorder(BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(Color.GRAY), "Operation Mode"));
        copyVerifyButton = new JButton("Copy & Verify");
        copyVerifyButton.setFocusPainted(false);
        copyVerifyButton.setPreferredSize(new Dimension(150, 28));
        copyVerifyButton.addActionListener(e -> selectJobType(FileSyncEngine.JobType.COPY_AND_VERIFY));
        verifyOnlyButton = new JButton("Verify Only");
        verifyOnlyButton.setFocusPainted(false);
        verifyOnlyButton.setPreferredSize(new Dimension(120, 28));
        verifyOnlyButton.addActionListener(e -> selectJobType(FileSyncEngine.JobType.VERIFY_ONLY));
        selectJobType(FileSyncEngine.JobType.COPY_AND_VERIFY);
        togglePanel.add(copyVerifyButton);
        togglePanel.add(verifyOnlyButton);

        scanButton = new JButton("Scan Files");
        scanButton.setPreferredSize(new Dimension(160, 32));
        scanButton.addActionListener(e -> scanDirectories());

        modeAndScan.add(togglePanel);
        modeAndScan.add(scanButton);
        panel.add(modeAndScan, gbc);

        // 5.6: what the two checkbox lists add up to, in the engine's own rule, shown before Run is
        // pressed. A second list of "effective" files would have been another thing to keep in sync
        // (and another thing to get wrong); a line that says which rule applied is enough.
        gbc.gridy++;
        gbc.gridwidth = 2;
        gbc.weightx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        workSetLabel = new JLabel("Work set: nothing scanned yet");
        workSetLabel.setFont(workSetLabel.getFont().deriveFont(Font.ITALIC));
        workSetLabel.setToolTipText("How many files the selected operation would process, using the same "
            + "rule the job uses: Copy & Verify follows the source ticks; Verify Only follows the source "
            + "ticks minus destination rows that were shown and left unticked.");
        panel.add(workSetLabel, gbc);

        return panel;
    }

    private JPanel createCenterPanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 0));
        panel.setBorder(new EmptyBorder(5, 10, 5, 10));

        JPanel sourcePanel = createListPanel("Source Files", sourceList = new JList<>(sourceModel.view()),
            sourceModel, sourceFilterField = new JTextField(), true);
        JPanel destPanel = createListPanel("Destination Files", destList = new JList<>(destModel.view()),
            destModel, destFilterField = new JTextField(), false);

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, sourcePanel, destPanel);
        splitPane.setResizeWeight(0.5);
        splitPane.setDividerLocation(0.5);
        splitPane.setDividerSize(8);
        panel.add(splitPane, BorderLayout.CENTER);
        return panel;
    }

    /** A titled list with a filter box (3.8), Select All / Deselect All, and click-to-toggle rows. */
    private JPanel createListPanel(String title, JList<DirectoryEntry> list, FileListModel model,
                                   JTextField filterField, boolean isSource) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(Color.GRAY), title));

        list.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        list.setVisibleRowCount(15);
        list.setCellRenderer(new DirectoryCellRenderer());
        // Fixed row height keeps layout O(1) for the thousands of rows a big tree produces.
        list.setFixedCellHeight(18);

        JPanel filterRow = new JPanel(new BorderLayout(4, 0));
        filterRow.add(new JLabel("Filter:"), BorderLayout.WEST);
        filterField.setToolTipText("Case-insensitive substring match on the relative path");
        filterField.getDocument().addDocumentListener(new SimpleDocumentFilter(
            () -> model.setFilter(filterField.getText()), list));
        filterRow.add(filterField, BorderLayout.CENTER);
        JLabel countLabel = new JLabel("0 files, 0 selected");
        filterRow.add(countLabel, BorderLayout.EAST);
        model.setCountLabel(countLabel);
        model.setOnChange(this::updateWorkSetSummary);
        panel.add(filterRow, BorderLayout.NORTH);

        panel.add(new JScrollPane(list), BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 2));
        JButton selectAllBtn = new JButton("Select All");
        selectAllBtn.addActionListener(e -> {
            model.selectAll(true);
            list.repaint();
        });
        JButton deselectAllBtn = new JButton("Deselect All");
        deselectAllBtn.addActionListener(e -> {
            model.selectAll(false);
            list.repaint();
        });
        actions.add(selectAllBtn);
        actions.add(deselectAllBtn);
        actions.add(new JLabel(isSource
            ? "unticked = never copied or verified"
            : "unticked = excluded from Verify Only"));
        panel.add(actions, BorderLayout.SOUTH);

        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int index = list.locationToIndex(e.getPoint());
                if (index >= 0 && SwingUtilities.isLeftMouseButton(e)) {
                    model.toggle(index);
                    Rectangle bounds = list.getCellBounds(index, index);
                    if (bounds != null) {
                        list.repaint(bounds);
                    }
                }
            }
        });
        return panel;
    }

    private JPanel createBottomPanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(new EmptyBorder(5, 10, 10, 10));

        JPanel progressRow = new JPanel(new BorderLayout(8, 0));
        progressBar = new JProgressBar(0, 100);
        progressBar.setStringPainted(true);
        progressBar.setPreferredSize(new Dimension(0, 25));
        progressBar.setValue(0);
        progressRow.add(progressBar, BorderLayout.WEST);
        statusLabel = new JLabel("Ready. Select source and destination directories, then click Scan Files.");
        progressRow.add(statusLabel, BorderLayout.CENTER);
        panel.add(progressRow, BorderLayout.NORTH);

        logArea = tailLog.component();
        logArea.setEditable(false);
        logArea.setFont(new Font("Monospaced", Font.PLAIN, 11));
        logArea.setBackground(new Color(30, 30, 30));
        logArea.setForeground(new Color(180, 180, 180));
        logArea.setLineWrap(true);
        logArea.setWrapStyleWord(true);
        logScrollPane = new JScrollPane(logArea);

        resultsTable = new JTable(new DefaultTableModel(
            new Object[]{"Status", "Path", "Source SHA-256", "Destination SHA-256"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        });
        resultsTable.setFont(new Font("Monospaced", Font.PLAIN, 11));
        resultsTable.setRowHeight(18);
        resultsTable.getTableHeader().setReorderingAllowed(false);
        resultsTable.setAutoCreateRowSorter(true);
        resultsTable.setFillsViewportHeight(true);

        JPanel resultsToolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 3));
        onlyProblemsCheck = new JCheckBox("Only problems");
        // ChangeListener rather than ActionListener: a JCheckBox fires ActionEvent only on a click, so a
        // programmatically changed box would leave the table showing the unfiltered rows.
        onlyProblemsCheck.addChangeListener(e -> showResults(lastJobResult));
        resultsToolbar.add(onlyProblemsCheck);
        exportCsvButton = new JButton("Export CSV");
        exportCsvButton.addActionListener(e -> exportReport("csv"));
        resultsToolbar.add(exportCsvButton);
        exportJsonButton = new JButton("Export JSON");
        exportJsonButton.addActionListener(e -> exportReport("json"));
        resultsToolbar.add(exportJsonButton);
        resultsToolbar.add(new JLabel("hashes are shown truncated; exports contain the full digests"));

        JPanel resultsPanel = new JPanel(new BorderLayout());
        resultsPanel.add(resultsToolbar, BorderLayout.NORTH);
        resultsPanel.add(new JScrollPane(resultsTable), BorderLayout.CENTER);

        outputTabs = new JTabbedPane();
        outputTabs.addTab("Log", logScrollPane);
        resultsTabTitle = new JLabel();
        outputTabs.addTab("Results", resultsPanel);
        outputTabs.setPreferredSize(new Dimension(0, 200));
        panel.add(outputTabs, BorderLayout.CENTER);

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 5));
        dryRunCheck = new JCheckBox("Dry run (write nothing)");
        dryRunCheck.setToolTipText("Run rsync with --dry-run and still compare hashes: shows what the job "
            + "would do without writing or changing anything.");
        controls.add(dryRunCheck);
        controls.add(new JLabel("Timeout (s, 0 = none):"));
        jobTimeoutField = new JTextField("0");
        jobTimeoutField.setPreferredSize(new Dimension(50, 28));
        jobTimeoutField.setHorizontalAlignment(JTextField.RIGHT);
        jobTimeoutField.setToolTipText("Kill rsync if the whole job takes longer than this.");
        controls.add(jobTimeoutField);

        runButton = new JButton("Run Job");
        runButton.setPreferredSize(new Dimension(130, 32));
        runButton.setBackground(new Color(34, 197, 94));
        runButton.setForeground(Color.WHITE);
        runButton.setFocusPainted(false);
        runButton.addActionListener(e -> runJob());
        controls.add(runButton);

        cancelButton = new JButton("Cancel");
        cancelButton.setPreferredSize(new Dimension(110, 32));
        cancelButton.setBackground(new Color(239, 68, 68));
        cancelButton.setForeground(Color.WHITE);
        cancelButton.setFocusPainted(false);
        cancelButton.setEnabled(false);
        cancelButton.addActionListener(e -> requestCancel());
        controls.add(cancelButton);

        JButton clearLogBtn = new JButton("Clear Log");
        clearLogBtn.addActionListener(e -> clearLog());
        controls.add(clearLogBtn);

        panel.add(controls, BorderLayout.SOUTH);
        return panel;
    }

    // ------------------------------------------------------------------ preferences

    private void restorePreferences() {
        sourcePathField.setText(prefs.get(PREF_SOURCE, ""));
        destPathField.setText(prefs.get(PREF_DEST, ""));
        ignoreField.setText(prefs.get(PREF_IGNORES, String.join(", ", ScanOptions.DEFAULT_IGNORED_DIRS)));
        hiddenCheck.setSelected(prefs.getBoolean(PREF_HIDDEN, true));
        extraCheck.setSelected(prefs.getBoolean(PREF_EXTRA, true));
        symlinkCombo.setSelectedItem(policyFromName(prefs.get(PREF_SYMLINKS, "SKIP")));
        jobTimeoutField.setText(prefs.get(PREF_TIMEOUT, "0"));
        dryRunCheck.setSelected(prefs.getBoolean(PREF_DRY_RUN, false));
        selectJobType(prefs.get(PREF_JOB_TYPE, "COPY_AND_VERIFY").equals("VERIFY_ONLY")
            ? FileSyncEngine.JobType.VERIFY_ONLY : FileSyncEngine.JobType.COPY_AND_VERIFY);
    }

    private void savePreferences() {
        prefs.put(PREF_SOURCE, sourcePathField.getText().trim());
        prefs.put(PREF_DEST, destPathField.getText().trim());
        prefs.put(PREF_IGNORES, ignoreField.getText().trim());
        prefs.putBoolean(PREF_HIDDEN, hiddenCheck.isSelected());
        prefs.putBoolean(PREF_EXTRA, extraCheck.isSelected());
        prefs.put(PREF_SYMLINKS, selectedPolicy().name());
        prefs.put(PREF_TIMEOUT, jobTimeoutField.getText().trim());
        prefs.putBoolean(PREF_DRY_RUN, dryRunCheck.isSelected());
        prefs.put(PREF_JOB_TYPE, currentJobType.name());
    }

    private static ScanOptions.SymlinkPolicy policyFromName(String name) {
        try {
            return ScanOptions.SymlinkPolicy.valueOf(name);
        } catch (IllegalArgumentException e) {
            return ScanOptions.SymlinkPolicy.SKIP;
        }
    }

    private static String symlinkLabel(ScanOptions.SymlinkPolicy policy) {
        return switch (policy) {
            case SKIP -> "Skip them (default)";
            case LINK -> "Copy and verify as links";
            case FOLLOW -> "Follow to their targets";
        };
    }

    // ------------------------------------------------------------------ scanning

    private ScanOptions currentScanOptions() {
        return ScanOptions.parse(ignoreField.getText(), hiddenCheck.isSelected(), selectedPolicy());
    }

    private ScanOptions.SymlinkPolicy selectedPolicy() {
        Object selected = symlinkCombo.getSelectedItem();
        return selected instanceof ScanOptions.SymlinkPolicy policy
            ? policy : ScanOptions.SymlinkPolicy.SKIP;
    }

    /**
     * Scan options only take effect on the next scan - nothing is re-walked behind the user's back,
     * and a long ignore field must not trigger a rescan per keystroke. So the frame says it out loud
     * once, when a completed scan has become stale.
     */
    private void noteScanOptionsChanged() {
        if (scanOptionsStale || sourceModel.total() == 0) {
            return;
        }
        scanOptionsStale = true;
        log("Scan options changed - press Scan Files to apply them.");
    }

    private void scanDirectories() {
        if (scanning) {
            return;
        }
        if (engine.isRunning()) {
            log("A job is already running - wait for it or press Cancel.");
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

        Path source = Path.of(sourcePath);
        Path dest = Path.of(destPath);
        if (!Files.isDirectory(source)) {
            JOptionPane.showMessageDialog(this, "Source directory does not exist: " + sourcePath,
                "Invalid Source", JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (!Files.isDirectory(dest)) {
            JOptionPane.showMessageDialog(this, "Destination directory does not exist: " + destPath,
                "Invalid Destination", JOptionPane.ERROR_MESSAGE);
            return;
        }

        ScanOptions options = currentScanOptions();
        Set<String> untickedSource = untickedOf(sourceModel);
        Set<String> untickedDest = untickedOf(destModel);

        scanning = true;
        statusLabel.setText("Scanning files...");
        savePreferences();

        SwingWorker<ScanPair, Void> worker = new SwingWorker<>() {
            @Override
            protected ScanPair doInBackground() {
                return new ScanPair(DirectoryScanner.scanFiles(source, options),
                    DirectoryScanner.scanFiles(dest, options));
            }

            @Override
            protected void done() {
                try {
                    ScanPair result = get();
                    // A re-scan keeps the user's ticks by relative path; new rows start ticked.
                    // A re-scan keeps the ticks the user actually removed; every other row -
                    // including one that disappeared under a filter and has now come back - is ticked.
                    applyScanResult(sourceModel, result.source(), untickedSource);
                    applyScanResult(destModel, result.dest(), untickedDest);
                    statusLabel.setText(String.format("Scanned: %d source files, %d destination files",
                        result.source().files().size(), result.dest().files().size()));
                    log(String.format("Scanned %d source files and %d destination files.",
                        result.source().files().size(), result.dest().files().size()));
                    logScanDetails(result.source(), "source");
                    logScanDetails(result.dest(), "destination");
                    scanOptionsStale = false;
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

    private void applyScanResult(FileListModel model, DirectoryScanner.ScanResult scan, Set<String> unticked) {
        model.clear();
        for (FileEntry entry : scan.files()) {
            model.add(new DirectoryEntry(entry, !unticked.contains(entry.relPath())));
        }
        model.refresh();
    }

    private void logScanDetails(DirectoryScanner.ScanResult scan, String side) {
        if (scan.ignoredDirs() > 0) {
            log("  " + side + ": " + scan.ignoredDirs() + " ignored director(ies)/link(s) skipped");
        }
        if (scan.skippedLinks() > 0) {
            log("  " + side + ": " + scan.skippedLinks() + " symlink(s) handled by policy "
                + selectedPolicy());
        }
        for (String warning : scan.warnings()) {
            log("  " + side + " warning: " + warning);
        }
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

    // ------------------------------------------------------------------ job control

    private void selectJobType(FileSyncEngine.JobType jobType) {
        if (engine.isRunning()) {
            return;
        }
        currentJobType = jobType;
        boolean copyMode = jobType == FileSyncEngine.JobType.COPY_AND_VERIFY;
        styleModeButton(copyVerifyButton, copyMode);
        styleModeButton(verifyOnlyButton, !copyMode);
        updateWorkSetSummary();
    }

    /**
     * Recomputes the work-set line. Uses {@link FileSyncEngine#planWorkSet}, i.e. the engine's own
     * exclusion rule, so the preview cannot disagree with the job it previews.
     */
    private void updateWorkSetSummary() {
        if (workSetLabel == null) {
            return;
        }
        int sourceRows = sourceModel.total();
        if (sourceRows == 0) {
            workSetLabel.setText("Work set: nothing scanned yet");
            return;
        }
        // A copy ignores the destination ticks, so for one the unticked set is empty by definition. The
        // lookups keep this proportional to how many rows the user actually unticked, not to list size.
        Iterable<String> untickedAtDestination = currentJobType == FileSyncEngine.JobType.COPY_AND_VERIFY
            ? List.of()
            : destModel.untickedPaths();
        FileSyncEngine.WorkSetPlan plan = FileSyncEngine.planWorkSet(sourceModel.selectedCount(),
            untickedAtDestination, rel -> {
                DirectoryEntry row = sourceModel.find(rel);
                return row != null && row.selected();
            });
        StringBuilder text = new StringBuilder("Work set: ").append(plan.willProcess()).append(" file(s)");
        if (currentJobType == FileSyncEngine.JobType.COPY_AND_VERIFY) {
            text.append(" - source ticks only; a copy ignores the destination list");
        } else if (plan.untickedAtDestination() > 0) {
            text.append(" - ").append(plan.selectedAtSource()).append(" ticked at source, ")
                .append(plan.untickedAtDestination()).append(" unticked at the destination");
            String named = plan.describeUnticked();
            if (named != null) {
                text.append(" (").append(named).append(")");
            }
        } else {
            text.append(" - all ").append(plan.selectedAtSource())
                .append(" ticked source files, nothing excluded at the destination");
        }
        workSetLabel.setText(text.toString());
    }

    private static void styleModeButton(JButton button, boolean active) {
        button.setBackground(active ? new Color(59, 130, 246) : Color.WHITE);
        button.setForeground(active ? Color.WHITE : Color.GRAY);
        button.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(active ? new Color(30, 100, 200) : Color.GRAY, active ? 2 : 1),
            new EmptyBorder(3, 8, 3, 8)));
    }

    /** Job timeout in millis; 0 = none. Invalid input falls back to no timeout. */
    private long jobTimeoutMillis() {
        try {
            long seconds = Long.parseLong(jobTimeoutField.getText().trim());
            return seconds <= 0 ? FileSyncEngine.NO_TIMEOUT : seconds * 1000L;
        } catch (NumberFormatException e) {
            return FileSyncEngine.NO_TIMEOUT;
        }
    }

    /** Single place that decides what the user may touch while a job runs. */
    private void setJobControlsEnabled(boolean idle) {
        runButton.setEnabled(idle);
        scanButton.setEnabled(idle);
        sourceBrowseButton.setEnabled(idle);
        destBrowseButton.setEnabled(idle);
        copyVerifyButton.setEnabled(idle);
        verifyOnlyButton.setEnabled(idle);
        jobTimeoutField.setEnabled(idle);
        dryRunCheck.setEnabled(idle);
        ignoreField.setEnabled(idle);
        hiddenCheck.setEnabled(idle);
        extraCheck.setEnabled(idle);
        symlinkCombo.setEnabled(idle);
        cancelButton.setEnabled(!idle);
    }

    private void requestCancel() {
        if (!engine.isRunning()) {
            return;
        }
        cancelButton.setEnabled(false);
        log("Cancellation requested - terminating rsync...");
        statusLabel.setText("Cancelling...");
        engine.cancel();
    }

    /** Liveness display driven by a timer, not by rsync output (a long transfer is silent). */
    private void updateLiveness() {
        if (!engine.isRunning()) {
            return;
        }
        long seconds = engine.millisSinceLastActivity() / 1000;
        if (seconds >= 3) {
            statusLabel.setText(String.format("Still running - last activity %ds ago (%s)",
                seconds, engine.lastActivityDescription()));
        }
    }

    // ------------------------------------------------------------------ job execution

    private void runJob() {
        if (engine.isRunning()) {
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
        if (sourceModel.total() == 0 && destModel.total() == 0) {
            JOptionPane.showMessageDialog(this, "Please scan directories first.",
                "No Directories", JOptionPane.WARNING_MESSAGE);
            return;
        }

        FileSyncEngine.Selection sourceSelection = selectionModelOf(sourceModel);
        FileSyncEngine.Selection destSelection = selectionModelOf(destModel);
        FileSyncEngine.JobRequest request = new FileSyncEngine.JobRequest(
            Path.of(sourcePath), Path.of(destPath), currentJobType,
            sourceSelection, destSelection, currentScanOptions(), extraCheck.isSelected(),
            dryRunCheck.isSelected());

        // Preflight before touching anything, so the user gets one clear dialog instead of a
        // failed job in the log. The engine repeats every check itself.
        List<String> problems = FileSyncEngine.validate(request);
        if (!problems.isEmpty()) {
            JOptionPane.showMessageDialog(this, String.join("\n", problems),
                "Preflight failed", JOptionPane.ERROR_MESSAGE);
            for (String problem : problems) {
                log("preflight: " + problem);
            }
            return;
        }

        int selectedSource = sourceSelection.selected().size();
        if (selectedSource == 0 && destSelection.selected().isEmpty()) {
            JOptionPane.showMessageDialog(this,
                "Nothing is ticked in either list. An empty selection means no files, not all files.",
                "Empty Selection", JOptionPane.WARNING_MESSAGE);
            return;
        }

        savePreferences();
        log("Starting " + (currentJobType == FileSyncEngine.JobType.COPY_AND_VERIFY
            ? "Copy & Verify" : "Verify Only") + " job...");
        log("Source: " + sourcePath);
        log("Destination: " + destPath);
        log("Selected: " + selectedSource + " source file(s), "
            + destSelection.selected().size() + " destination file(s)");
        engine.setJobTimeoutMillis(jobTimeoutMillis());
        if (engine.jobTimeoutMillis() > 0) {
            log("Job timeout: " + engine.jobTimeoutMillis() + " ms");
        }

        progressBar.setValue(0);
        progressBar.setString("Running...");
        showResults(null);

        if (!engine.start(request, new FileSyncEngine.ProgressListener() {
            @Override
            public void onPhase(String phase) {
                SwingUtilities.invokeLater(() -> log(phase + "..."));
            }

            @Override
            public void onProgress(int percent, String statusText) {
                SwingUtilities.invokeLater(() -> {
                    statusLabel.setText(statusText);
                    progressBar.setValue(Math.max(0, Math.min(100, percent)));
                });
            }

            @Override
            public void onFileProcessed(String relPath, String kind, String srcHash, String dstHash) {
                SwingUtilities.invokeLater(() -> log(formatFileLine(kind, relPath, srcHash, dstHash)));
            }

            @Override
            public void onCompletion(FileSyncEngine.JobResult result) {
                SwingUtilities.invokeLater(() -> finishJob(result));
            }
        })) {
            JOptionPane.showMessageDialog(this, "A job is already running.",
                "Job Running", JOptionPane.WARNING_MESSAGE);
            return;
        }

        setJobControlsEnabled(false);
        livenessTimer.start();
    }

    private static String formatFileLine(String kind, String relPath, String srcHash, String dstHash) {
        String icon = switch (kind) {
            case "OK" -> "[OK]       ";
            case "MISMATCH" -> "[MISMATCH] ";
            case "MISSING" -> "[MISSING]  ";
            case "EXTRA" -> "[EXTRA]    ";
            case "COPIED" -> "[COPIED]   ";
            default -> "[INFO]     ";
        };
        StringBuilder line = new StringBuilder("  ").append(icon).append(relPath);
        if (srcHash != null && !srcHash.isEmpty()) {
            line.append("  src=").append(srcHash);
        }
        if (dstHash != null && !dstHash.isEmpty()) {
            line.append("  dst=").append(dstHash);
        }
        return line.toString();
    }

    private void finishJob(FileSyncEngine.JobResult result) {
        livenessTimer.stop();
        setJobControlsEnabled(true);
        lastJobResult = result;

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
        log(String.format("Totals: checked=%d copied=%d matched=%d mismatched=%d missing=%d extra=%d",
            result.filesChecked(), result.filesCopied(), result.filesMatched(),
            result.filesMismatched(), result.filesMissing(), result.filesExtra()));
        for (String warning : result.warnings()) {
            log("  warning: " + warning);
        }
        for (String error : result.errors()) {
            log("  error: " + error);
        }
        showResults(result);
        if (result.filesMismatched() > 0 || result.filesMissing() > 0 || result.filesExtra() > 0) {
            outputTabs.setSelectedIndex(1);
        }
    }

    // ------------------------------------------------------------------ results (3.3)

    private void showResults(FileSyncEngine.JobResult result) {
        DefaultTableModel table = (DefaultTableModel) resultsTable.getModel();
        table.setRowCount(0);
        if (result == null) {
            resultsTable.setEnabled(false);
            exportCsvButton.setEnabled(false);
            exportJsonButton.setEnabled(false);
            outputTabs.setTitleAt(1, "Results");
            return;
        }
        int shown = 0;
        for (FileSyncEngine.FileOutcome outcome : result.outcomes()) {
            boolean problem = !"OK".equals(outcome.kind());
            if (onlyProblemsCheck.isSelected() && !problem) {
                continue;
            }
            table.addRow(new Object[]{
                outcome.kind(),
                outcome.relPath(),
                FileHasher.shortHash(outcome.sourceHash()),
                FileHasher.shortHash(outcome.destHash())
            });
            shown++;
        }
        resultsTable.setEnabled(true);
        exportCsvButton.setEnabled(true);
        exportJsonButton.setEnabled(true);
        outputTabs.setTitleAt(1, "Results (" + shown + ")");
    }

    private void exportReport(String format) {
        if (lastJobResult == null) {
            return;
        }
        JFileChooser chooser = new JFileChooser();
        String base = "file-copy-report";
        chooser.setSelectedFile(new File(base + "." + format));
        chooser.setDialogTitle("Export job report");
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File chosen = chooser.getSelectedFile();
        if (chosen == null) {
            log("No file was chosen - nothing was exported.");
            return;
        }
        Path target = chosen.toPath();
        Path name = target.getFileName();
        if (name == null) {
            log("The chosen path has no file name - nothing was exported.");
            return;
        }
        if (!name.toString().contains(".")) {
            target = target.resolveSibling(name + "." + format);
        }
        try {
            String content = "json".equals(format)
                ? ReportExporter.toJson(lastJobResult)
                : ReportExporter.toCsv(lastJobResult);
            Files.writeString(target, content, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            log("Report written: " + target);
            statusLabel.setText("Report written: " + target);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "Could not write report: " + e.getMessage(),
                "Export Failed", JOptionPane.ERROR_MESSAGE);
        }
    }

    // ------------------------------------------------------------------ chooser (3.1)

    private void browseDirectory(boolean isSource) {
        JTextField field = isSource ? sourcePathField : destPathField;
        JFileChooser chooser = new JFileChooser(findValidStartDir(isSource));
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle(isSource ? "Select Source Directory" : "Select Destination Directory");

        if (chooser.showDialog(this, "Select Folder") != JFileChooser.APPROVE_OPTION) {
            return;
        }
        // getSelectedFile() is the directory the user actually chose; getCurrentDirectory() is only
        // the folder being displayed. Using the latter silently ignored the user's click.
        File chosen = chooser.getSelectedFile();
        if (chosen == null) {
            chosen = chooser.getCurrentDirectory();
        }
        if (chosen != null && !chosen.isDirectory()) {
            chosen = chosen.getParentFile();
        }
        if (chosen != null) {
            field.setText(chosen.getAbsolutePath());
            log((isSource ? "Source" : "Destination") + " directory: " + chosen.getAbsolutePath());
        }
    }

    /** Start the chooser in the directory already in that field, else the other one, else home. */
    private File findValidStartDir(boolean isSource) {
        String own = (isSource ? sourcePathField : destPathField).getText().trim();
        String other = (isSource ? destPathField : sourcePathField).getText().trim();
        for (String candidate : List.of(own, other)) {
            if (!candidate.isEmpty()) {
                File dir = new File(candidate);
                if (dir.isDirectory()) {
                    return dir;
                }
            }
        }
        return new File(System.getProperty("user.home", "."));
    }

    // ------------------------------------------------------------------ log (3.6)

    private void log(String message) {
        tailLog.append(message);
    }

    private void clearLog() {
        tailLog.clear();
    }

    // ------------------------------------------------------------------ selection helpers

    private static Set<String> untickedOf(FileListModel model) {
        Set<String> unticked = new LinkedHashSet<>();
        for (DirectoryEntry entry : model.all()) {
            if (!entry.selected()) {
                unticked.add(entry.relPath());
            }
        }
        return unticked;
    }

    private static Set<String> selectionOf(FileListModel model) {
        Set<String> selected = new LinkedHashSet<>();
        for (DirectoryEntry entry : model.all()) {
            if (entry.selected()) {
                selected.add(entry.relPath());
            }
        }
        return selected;
    }

    private static FileSyncEngine.Selection selectionModelOf(FileListModel model) {
        Set<String> selected = selectionOf(model);
        Set<String> known = new LinkedHashSet<>();
        for (DirectoryEntry entry : model.all()) {
            known.add(entry.relPath());
        }
        return new FileSyncEngine.Selection(selected, known);
    }

    // ------------------------------------------------------------------ list plumbing

    /** Canonical rows plus a text filter, shown through a DefaultListModel view. */
    private static final class FileListModel {
        private final List<DirectoryEntry> all = new ArrayList<>();
        private final DefaultListModel<DirectoryEntry> view = new DefaultListModel<>();
        private String filter = "";
        private JLabel countLabel;
        private Runnable onChange;
        /** Maintained incrementally: a 100000 row scan must not recount the list per row. */
        private int selectedCount;
        /** Rows in list order by relative path, so a preview can look one up instead of scanning. */
        private final Map<String, DirectoryEntry> byPath = new HashMap<>();
        private final Set<String> unticked = new HashSet<>();

        DefaultListModel<DirectoryEntry> view() {
            return view;
        }

        List<DirectoryEntry> all() {
            return all;
        }

        void setCountLabel(JLabel label) {
            this.countLabel = label;
        }

        /** Notified whenever the rows or their ticks change, so the work-set line can follow. */
        void setOnChange(Runnable onChange) {
            this.onChange = onChange;
        }

        int selectedCount() {
            return selectedCount;
        }

        /** Toggles a visible row and keeps the selection count exact. */
        void toggle(int viewIndex) {
            DirectoryEntry entry = view.getElementAt(viewIndex);
            entry.setSelected(!entry.selected());
            if (entry.selected()) {
                selectedCount++;
                unticked.remove(entry.relPath());
            } else {
                selectedCount--;
                unticked.add(entry.relPath());
            }
            fireSelectionChange();
        }

        /** The row for a relative path, or null if this side does not have it. */
        DirectoryEntry find(String relPath) {
            return byPath.get(relPath);
        }

        /**
         * Rows this side has and the user left unticked. Exposed as the live set rather than a copy:
         * a copy per click is exactly the cost this exists to avoid, and the only reader iterates it.
         */
        @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(value = "EI_EXPOSE_REP",
                justification = "Deliberate: the preview iterates it; copying a 100000 entry set per "
                        + "click would undo the point of maintaining it.")
        Set<String> untickedPaths() {
            return unticked;
        }

        int total() {
            return all.size();
        }

        DirectoryEntry get(int viewIndex) {
            return view.getElementAt(viewIndex);
        }

        void clear() {
            all.clear();
            byPath.clear();
            unticked.clear();
            selectedCount = 0;
            rebuild();
            fireSelectionChange();
        }

        /** Cheap on purpose: a scan adds one row at a time and refresh() reports the result once. */
        void add(DirectoryEntry entry) {
            all.add(entry);
            byPath.put(entry.relPath(), entry);
            if (entry.selected()) {
                selectedCount++;
            } else {
                unticked.add(entry.relPath());
            }
            if (matches(entry)) {
                view.addElement(entry);
            }
        }

        /** Call once after a bulk add so the labels and the work-set line see the finished list. */
        void refresh() {
            fireSelectionChange();
        }

        void setFilter(String text) {
            filter = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
            rebuild();
        }

        void selectAll(boolean selected) {
            for (DirectoryEntry entry : all) {
                entry.setSelected(selected);
            }
            selectedCount = selected ? all.size() : 0;
            unticked.clear();
            if (!selected) {
                for (DirectoryEntry entry : all) {
                    unticked.add(entry.relPath());
                }
            }
            fireSelectionChange();
        }

        private boolean matches(DirectoryEntry entry) {
            return filter.isEmpty() || entry.relPath().toLowerCase(Locale.ROOT).contains(filter);
        }

        private void rebuild() {
            view.clear();
            for (DirectoryEntry entry : all) {
                if (matches(entry)) {
                    view.addElement(entry);
                }
            }
            updateLabel();
        }

        private void updateLabel() {
            if (countLabel != null) {
                String shown = view.size() == all.size()
                    ? all.size() + " files"
                    : view.size() + " / " + all.size() + " files";
                countLabel.setText(shown + ", " + selectedCount + " selected");
            }
        }

        /** A selection or row-set change: refresh the count and the work-set line. */
        private void fireSelectionChange() {
            updateLabel();
            if (onChange != null) {
                onChange.run();
            }
        }
    }

    /** Rebuilds a filtered list when a filter field changes. */
    private static final class SimpleDocumentFilter implements javax.swing.event.DocumentListener {
        private final Runnable onChange;
        private final JList<?> list;

        SimpleDocumentFilter(Runnable onChange, JList<?> list) {
            this.onChange = onChange;
            this.list = list;
        }

        @Override
        public void insertUpdate(javax.swing.event.DocumentEvent e) {
            changed();
        }

        @Override
        public void removeUpdate(javax.swing.event.DocumentEvent e) {
            changed();
        }

        @Override
        public void changedUpdate(javax.swing.event.DocumentEvent e) {
            changed();
        }

        private void changed() {
            onChange.run();
            if (list != null) {
                SwingUtilities.invokeLater(list::repaint);
            }
        }
    }

    // ------------------------------------------------------------------ rendering

    private static final class DirectoryCellRenderer extends JCheckBox implements ListCellRenderer<DirectoryEntry> {
        private static final long serialVersionUID = 1L;

        DirectoryCellRenderer() {
            setOpaque(true);
            setFont(new Font("SansSerif", Font.PLAIN, 12));
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends DirectoryEntry> list,
                                                      DirectoryEntry value, int index,
                                                      boolean isSelected, boolean cellHasFocus) {
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
