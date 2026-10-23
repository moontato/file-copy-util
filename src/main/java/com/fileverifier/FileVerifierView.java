package com.fileverifier;

import javax.swing.*;
import javax.swing.border.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
 *
 * <p>It is a panel, not a window, on purpose. A JFrame cannot even be constructed on a machine with no
 * display, which meant the entire UI was untestable in CI. As a panel it can be built, exercised, and
 * painted to an image headlessly; {@link FileVerifierFrame} is a thin window that hosts it.</p>
 */
public final class FileVerifierView extends JPanel {

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
    private static final String PREF_VERIFY_AFTER_COPY = "verifyAfterCopy";
    private static final String PREF_THEME = "darkMode";

    private final Preferences prefs = Preferences.userRoot().node("com/fileverifier");

    private JLabel versionLabel;
    private boolean pathEditSuppressed;
    private boolean explanationShown;

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
    private JToggleButton copyButton;
    private JToggleButton verifyOnlyButton;
    private JCheckBox verifyCheck;
    private JButton runButton;
    private JButton cancelButton;
    private JButton exportCsvButton;
    private JButton exportJsonButton;
    private JTextField jobTimeoutField;
    private JCheckBox dryRunCheck;
    private Timer livenessTimer;
    private JCheckBox darkModeCheck;
    private JLabel phaseLabel;

    private boolean scanning = false;
    private boolean scanOptionsStale = false;
    /**
     * What is loaded, and on what authority: which scan produced it, which two directories were read, under
     * which options. Everything a Run needs is checked against this, so a job cannot be started from rows that
     * belong to another pair of directories or to options that are no longer set.
     */
    private volatile LoadedScan loadedScan;
    /** Bumped every time a scan is started; a completion carrying an older number is thrown away. */
    private long scanGeneration;
    /** How many scans have been thrown away for being out of date - observable, because it is a decision. */
    private final java.util.concurrent.atomic.AtomicInteger discardedScans =
        new java.util.concurrent.atomic.AtomicInteger();
    /**
     * Scans reading the directories right now. "Scanning" follows this count rather than the fate of one scan:
     * a scan discarded on the way home must not leave the window saying it is still scanning, and one that
     * finishes while a newer one is queued must not clear the flag either.
     */
    private final java.util.concurrent.atomic.AtomicInteger inFlightScans =
        new java.util.concurrent.atomic.AtomicInteger();
    /** The one immutable request the current job is running - and the one a collision answer belongs to. */
    private FileSyncEngine.JobRequest pendingRequest;
    private FileSyncEngine.JobType currentJobType = FileSyncEngine.JobType.COPY_AND_VERIFY;
    private JLabel workSetLabel;

    /** One engine per window: it owns at most one job at a time. */
    private final FileSyncEngine engine = new FileSyncEngine();

    /** Kept so the Results tab can be re-filtered or exported after the job ends. */
    /**
     * The finished job whose rows are on screen: its result, and the request it was run from.
     *
     * <p>A report belongs to the job it describes. Whether a report path is allowed - never over an existing
     * file, not among the files the job copied unless somebody decides otherwise - is a rule about the
     * directories <i>that job</i> worked in. By the time somebody presses Export, the path fields may name
     * somewhere quite different, and a later request that never started (preflight refused it) must not
     * re-point an older job's report. So the two are paired here, at the moment the job finishes, and
     * everything that reads this job's facts reads the pair.
     */
    private CompletedJob completedJob;

    /** A finished result and the one request that produced it. */
    record CompletedJob(FileSyncEngine.JobRequest request, FileSyncEngine.JobResult result) {
    }

    /** What the finished job actually was, so its results can be described correctly afterwards. */
    private FileSyncEngine.JobType launchedJobType;

    public FileVerifierView() {
        // Theme before any widget exists: every control picks up its colours at construction.
        UiTheme.install(prefs.getBoolean(PREF_THEME, false));
        initComponents();
        restorePreferences();
        livenessTimer = new Timer(1000, e -> updateLiveness());
        installShortcuts();
        // A look-and-feel overwrites component colours with its own defaults whenever it installs a UI,
        // so the palette is applied last rather than hoped for. It also makes a widget built before a
        // theme switch legible after it.
        UiTheme.restyle(this);
    }

    /**
     * Keys that work from anywhere in the window: Ctrl+S scans, Ctrl+R runs, Escape cancels. Enter is
     * deliberately left alone so it keeps activating whichever control has focus.
     */
    private void installShortcuts() {
        javax.swing.InputMap keys = getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        javax.swing.ActionMap actions = getActionMap();
        keys.put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_S, java.awt.event.InputEvent.CTRL_DOWN_MASK), "scan");
        keys.put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_R, java.awt.event.InputEvent.CTRL_DOWN_MASK), "run");
        keys.put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0), "cancel");
        actions.put("scan", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                scanDirectories();
            }
        });
        actions.put("run", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                runJob();
            }
        });
        actions.put("cancel", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                requestCancel();
            }
        });
        scanButton.setToolTipText("Scan both directories (Ctrl+S)");
        runButton.setToolTipText("Start the job (Ctrl+R)");
        cancelButton.setToolTipText("Stop the running job (Escape)");
    }

    /**
     * The window hosting this view is closing: stop watching, remember the settings, and release the
     * engine so a running job cannot outlive the UI that started it.
     */
    public void shutdown() {
        livenessTimer.stop();
        savePreferences();
        // closeOnce, not close: the engine may already have been stopped by a shutdown hook, and two
        // cleanups waiting on one process tree is how a program hangs on exit instead of exiting.
        engine.closeOnce();
    }

    // ------------------------------------------------------------------ layout

    private void initComponents() {
        setLayout(new BorderLayout(0, 12));
        // The root and the three regions must be part of the palette. AWT resolves a control with no
        // background of its own by walking up the parents, so an untracked region is how dark mode ends
        // up with light buttons carrying light text.
        UiTheme.surface(this);
        setBorder(new EmptyBorder(UiTheme.SPACING));
        add(createTopPanel(), BorderLayout.NORTH);
        add(createCenterPanel(), BorderLayout.CENTER);
        add(createBottomPanel(), BorderLayout.SOUTH);
    }

    /**
     * Paths and scan options. Fields are labelled from above and the scan options live on one line,
     * because four stacked "label: control" rows is what made this window read like a 2005 dialog.
     */
    private JPanel createTopPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 10));
        UiTheme.surface(panel);

        JPanel paths = UiTheme.card();
        paths.setLayout(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(2, 0, 2, 8);
        gbc.anchor = GridBagConstraints.WEST;

        gbc.gridx = 0;
        gbc.gridy = 0;
        paths.add(UiTheme.fieldLabel("Source directory"), gbc);

        gbc.gridx = 1;
        gbc.gridy = 0;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        sourcePathField = UiTheme.field(28);
        installPathField(sourcePathField);
        paths.add(sourcePathField, gbc);

        gbc.gridx = 2;
        gbc.gridy = 0;
        gbc.weightx = 0;
        gbc.fill = GridBagConstraints.NONE;
        sourceBrowseButton = UiTheme.button("Browse");
        sourceBrowseButton.addActionListener(e -> browseDirectory(true));
        paths.add(sourceBrowseButton, gbc);

        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.weightx = 0;
        paths.add(UiTheme.fieldLabel("Destination directory"), gbc);

        gbc.gridx = 1;
        gbc.gridy = 1;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        destPathField = UiTheme.field(28);
        installPathField(destPathField);
        paths.add(destPathField, gbc);

        gbc.gridx = 2;
        gbc.gridy = 1;
        gbc.weightx = 0;
        gbc.fill = GridBagConstraints.NONE;
        destBrowseButton = UiTheme.button("Browse");
        destBrowseButton.addActionListener(e -> browseDirectory(false));
        paths.add(destBrowseButton, gbc);

        JPanel options = UiTheme.card();
        options.setLayout(new BorderLayout(10, 0));

        JPanel optionsRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        optionsRow.setOpaque(false);
        JLabel ignoreLabel = UiTheme.fieldLabel("Ignore");
        ignoreField = UiTheme.field(22);
        ignoreField.setText(String.join(", ", ScanOptions.DEFAULT_IGNORED_DIRS));
        ignoreField.setToolTipText("Glob patterns matched against relative paths and names, e.g. "
            + "\"*.tmp\", \"node_modules\", \"sub/*.log\"");
        ignoreField.getDocument().addDocumentListener(new SimpleDocumentFilter(
            this::noteScanOptionsChanged, null));
        optionsRow.add(ignoreLabel);
        optionsRow.add(ignoreField);

        hiddenCheck = UiTheme.check("Hidden files");
        hiddenCheck.setSelected(true);
        // A ChangeListener, not an ActionListener: JCheckBox fires ActionEvent only for a click, so a
        // programmatically changed box (or a restored preference) would otherwise go unnoticed.
        hiddenCheck.addChangeListener(e -> noteScanOptionsChanged());
        optionsRow.add(hiddenCheck);

        symlinkCombo = UiTheme.comboBox(new ScanOptions.SymlinkPolicy[]{
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
        optionsRow.add(UiTheme.fieldLabel("Symbolic links"));
        optionsRow.add(symlinkCombo);

        extraCheck = UiTheme.check("Destination-only files");
        extraCheck.setSelected(true);
        extraCheck.setToolTipText("Verify Only lists files that exist at the destination but not in the "
            + "selected source as EXTRA.");
        optionsRow.add(extraCheck);
        options.add(optionsRow, BorderLayout.CENTER);

        scanButton = UiTheme.button("Scan files");
        scanButton.addActionListener(e -> scanDirectories());
        options.add(scanButton, BorderLayout.EAST);

        JPanel modeRow = new JPanel(new BorderLayout(12, 4));
        modeRow.setOpaque(false);

        JPanel segments = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        segments.setOpaque(false);
        segments.add(UiTheme.fieldLabel("Mode"));
        // Two segments, not three modes: whether the job copies, and separately whether it compares
        // afterwards. Verify only stays a mode because it is the only job that looks at a destination
        // without touching it - that cannot be a checkbox on a copy job.
        copyButton = UiTheme.segmentButton("Copy", "left");
        copyButton.setToolTipText("Run rsync and write the ticked source files into the destination.");
        copyButton.addActionListener(e -> selectCopyMode(true));
        verifyOnlyButton = UiTheme.segmentButton("Verify only", "right");
        verifyOnlyButton.setToolTipText("Compare source and destination by hash without copying anything. "
            + "rsync never runs, so nothing in the destination is written, changed or deleted.");
        verifyOnlyButton.addActionListener(e -> selectCopyMode(false));
        segments.add(copyButton);
        segments.add(verifyOnlyButton);

        verifyCheck = UiTheme.check("Verify after copying");
        verifyCheck.setSelected(true);
        verifyCheck.setToolTipText("Compare hashes of what was copied against what landed in the destination. "
            + "Turn it off for a plain copy: nothing is hashed, files that were written are listed as COPIED,"
            + " and the job says in its own summary that it compared nothing.");
        verifyCheck.addActionListener(e -> {
            currentJobType = resolvedJobType();
            updateRunButtonLabel();
            updateWorkSetSummary();
            savePreferences();
        });
        segments.add(verifyCheck);
        modeRow.add(segments, BorderLayout.WEST);

        // 5.6: what the two checkbox lists add up to, in the engine's own rule, shown before Run is
        // pressed. A second list of "effective" files would have been another thing to keep in sync
        // (and another thing to get wrong); a line that says which rule applied is enough.
        workSetLabel = UiTheme.caption("Work set: nothing scanned yet");
        workSetLabel.setToolTipText("How many files the selected operation would process, using the same "
            + "rule the job uses: a copy follows the source ticks; Verify only follows the source ticks minus "
            + "destination rows that were shown and left unticked.");
        modeRow.add(workSetLabel, BorderLayout.CENTER);

        panel.add(paths, BorderLayout.NORTH);
        panel.add(options, BorderLayout.CENTER);
        panel.add(modeRow, BorderLayout.SOUTH);
        selectCopyMode(true);
        return panel;
    }

    private JPanel createCenterPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        UiTheme.surface(panel);

        JPanel sourcePanel = createListPanel("Source files", sourceList = UiTheme.list(sourceModel.view()),
            sourceModel, sourceFilterField = UiTheme.field(12), true);
        JPanel destPanel = createListPanel("Destination files", destList = UiTheme.list(destModel.view()),
            destModel, destFilterField = UiTheme.field(12), false);

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, sourcePanel, destPanel);
        splitPane.setResizeWeight(0.5);
        splitPane.setDividerLocation(0.5);
        splitPane.setDividerSize(10);
        splitPane.setBorder(null);
        panel.add(splitPane, BorderLayout.CENTER);
        return panel;
    }

    /** A card holding a filter box, a live count, and click-to-toggle rows (3.8). */
    private JPanel createListPanel(String title, JList<DirectoryEntry> list, FileListModel model,
                                   JTextField filterField, boolean isSource) {
        JPanel panel = UiTheme.card();
        panel.setLayout(new BorderLayout(8, 6));

        JLabel heading = UiTheme.heading(title);
        JLabel countLabel = UiTheme.caption("0 files, 0 selected");
        JPanel head = new JPanel(new BorderLayout(8, 0));
        head.setOpaque(false);
        head.add(heading, BorderLayout.WEST);
        head.add(countLabel, BorderLayout.EAST);
        panel.add(head, BorderLayout.NORTH);

        model.setCountLabel(countLabel);
        model.setOnChange(this::updateWorkSetSummary);

        list.setVisibleRowCount(15);
        list.setCellRenderer(new DirectoryCellRenderer());

        JPanel filterRow = new JPanel(new BorderLayout(6, 0));
        filterRow.setOpaque(false);
        filterField.setToolTipText("Case-insensitive substring match on the relative path");
        filterField.getDocument().addDocumentListener(new SimpleDocumentFilter(
            () -> model.setFilter(filterField.getText()), list));
        filterRow.add(filterField, BorderLayout.CENTER);
        JButton clearFilter = UiTheme.button("Clear");
        clearFilter.addActionListener(e -> filterField.setText(""));
        filterRow.add(clearFilter, BorderLayout.EAST);

        JPanel headAndFilter = new JPanel(new BorderLayout(0, 6));
        headAndFilter.setOpaque(false);
        headAndFilter.add(head, BorderLayout.NORTH);
        headAndFilter.add(filterRow, BorderLayout.SOUTH);
        panel.add(headAndFilter, BorderLayout.NORTH);

        JScrollPane scroll = new JScrollPane(list);
        UiTheme.edge(scroll);
        panel.add(scroll, BorderLayout.CENTER);

        JPanel actions = new JPanel(new BorderLayout(8, 0));
        actions.setOpaque(false);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        buttons.setOpaque(false);
        JButton selectAllBtn = UiTheme.button("Select all");
        selectAllBtn.addActionListener(e -> {
            model.selectAll(true);
            list.repaint();
        });
        JButton deselectAllBtn = UiTheme.button("Deselect all");
        deselectAllBtn.addActionListener(e -> {
            model.selectAll(false);
            list.repaint();
        });
        buttons.add(selectAllBtn);
        buttons.add(deselectAllBtn);
        actions.add(buttons, BorderLayout.WEST);
        JLabel hint = UiTheme.caption(isSource ? "unticked is never copied or verified"
            : "unticked is excluded from Verify only");
        hint.setHorizontalAlignment(SwingConstants.LEFT);
        hint.setBorder(new EmptyBorder(0, 8, 0, 8));
        actions.add(hint, BorderLayout.CENTER);
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

    /**
     * Status line, output tabs, and the actions. One filled button in the whole window: Run. The mode
     * choice sits at the top with the work-set line that follows from it, not buried in the footer.
     */
    private JPanel createBottomPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 10));
        UiTheme.surface(panel);

        JPanel statusRow = new JPanel(new BorderLayout(12, 0));
        statusRow.setOpaque(false);
        progressBar = UiTheme.progressBar();
        statusRow.add(progressBar, BorderLayout.WEST);
        statusLabel = UiTheme.bodyLabel("Ready. Pick both directories, then Scan files.");
        statusRow.add(statusLabel, BorderLayout.CENTER);
        phaseLabel = UiTheme.caption("");
        statusRow.add(phaseLabel, BorderLayout.EAST);
        panel.add(statusRow, BorderLayout.NORTH);

        logArea = tailLog.component();
        logArea.setEditable(false);
        UiTheme.asLog(logArea);
        logScrollPane = new JScrollPane(logArea);
        UiTheme.edge(logScrollPane);

        resultsTable = UiTheme.table(new DefaultTableModel(
            new Object[]{"Status", "Path", "Source SHA-256", "Destination SHA-256", "Transfer"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        });
        resultsTable.setAutoCreateRowSorter(true);
        resultsTable.setDefaultRenderer(Object.class, new OutcomeCellRenderer());
        resultsTable.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
        // A table that lets every column grow to fit its content makes the one column that matters -
        // the path - the narrowest thing on screen.
        resultsTable.getColumnModel().getColumn(0).setPreferredWidth(120);
        resultsTable.getColumnModel().getColumn(1).setPreferredWidth(520);
        resultsTable.getColumnModel().getColumn(2).setPreferredWidth(160);
        resultsTable.getColumnModel().getColumn(3).setPreferredWidth(160);

        JPanel resultsToolbar = new JPanel(new BorderLayout(8, 0));
        resultsToolbar.setOpaque(false);
        JPanel toolbarLeft = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        toolbarLeft.setOpaque(false);
        onlyProblemsCheck = UiTheme.check("Only problems");
        // ChangeListener rather than ActionListener: a JCheckBox fires ActionEvent only on a click, so a
        // programmatically changed box would leave the table showing the unfiltered rows.
        onlyProblemsCheck.addChangeListener(e -> showResults(
            completedJob == null ? null : completedJob.result()));
        toolbarLeft.add(onlyProblemsCheck);
        exportCsvButton = UiTheme.button("Export CSV");
        exportCsvButton.addActionListener(e -> exportReport("csv"));
        toolbarLeft.add(exportCsvButton);
        exportJsonButton = UiTheme.button("Export JSON");
        exportJsonButton.addActionListener(e -> exportReport("json"));
        toolbarLeft.add(exportJsonButton);
        resultsToolbar.add(toolbarLeft, BorderLayout.WEST);
        resultsToolbar.add(UiTheme.caption("click a column header to sort - hashes are truncated here, full in exports"),
            BorderLayout.EAST);

        JPanel resultsPanel = new JPanel(new BorderLayout());
        resultsPanel.setOpaque(false);
        resultsPanel.add(resultsToolbar, BorderLayout.NORTH);
        // JScrollPane(JTable) does not attach the header. Without this the column titles - and the
        // click-to-sort affordance the toolbar advertises - are simply missing from the window.
        JScrollPane resultsScroll = new JScrollPane(resultsTable);
        resultsScroll.setRowHeaderView(resultsTable.getTableHeader());
        resultsPanel.add(resultsScroll, BorderLayout.CENTER);

        outputTabs = new JTabbedPane();
        outputTabs.addTab("Log", logScrollPane);
        resultsTabTitle = new JLabel();
        outputTabs.addTab("Results", resultsPanel);
        outputTabs.setPreferredSize(new Dimension(0, 220));
        panel.add(outputTabs, BorderLayout.CENTER);

        JPanel controls = new JPanel(new BorderLayout(10, 0));
        controls.setOpaque(false);
        JPanel controlsLeft = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        controlsLeft.setOpaque(false);
        darkModeCheck = UiTheme.check("Dark mode");
        darkModeCheck.addActionListener(e -> applyTheme(darkModeCheck.isSelected()));
        controlsLeft.add(darkModeCheck);
        dryRunCheck = UiTheme.check("Dry run (write nothing)");
        dryRunCheck.setToolTipText("Run rsync with --dry-run and still compare hashes: shows what the job "
            + "would do without writing or changing anything.");
        controlsLeft.add(dryRunCheck);
        JLabel timeoutLabel = UiTheme.fieldLabel("Timeout s (0 = none)");
        jobTimeoutField = UiTheme.field(4);
        jobTimeoutField.setText("0");
        jobTimeoutField.setHorizontalAlignment(JTextField.RIGHT);
        jobTimeoutField.setToolTipText("Kill rsync if the whole job takes longer than this.");
        controlsLeft.add(timeoutLabel);
        controlsLeft.add(jobTimeoutField);
        controls.add(controlsLeft, BorderLayout.WEST);

        JPanel controlsRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        controlsRight.setOpaque(false);
        // The version the build stamped into this artifact, in the quiet corner: which build is this
        // window, without putting a number in the way of anything you have to press.
        versionLabel = UiTheme.caption(AppVersion.display());
        versionLabel.setToolTipText("File Copy Util " + AppVersion.display()
            + " - the version this window was built from.");
        versionLabel.setBorder(javax.swing.BorderFactory.createEmptyBorder(0, 0, 0, 14));
        controlsRight.add(versionLabel);
        JButton clearLogBtn = UiTheme.button("Clear log");
        clearLogBtn.addActionListener(e -> clearLog());
        controlsRight.add(clearLogBtn);
        cancelButton = UiTheme.dangerButton("Cancel");
        cancelButton.setEnabled(false);
        cancelButton.addActionListener(e -> requestCancel());
        controlsRight.add(cancelButton);
        runButton = UiTheme.primaryButton("Run job");
        runButton.addActionListener(e -> runJob());
        controlsRight.add(runButton);
        controls.add(controlsRight, BorderLayout.EAST);

        panel.add(controls, BorderLayout.SOUTH);
        return panel;
    }

    /** Re-installs the look-and-feel and re-themes the window that is already on screen. */
    private void applyTheme(boolean dark) {
        UiTheme.install(dark);
        com.formdev.flatlaf.FlatLaf.updateUI();
        UiTheme.restyle(this);
        sourceList.repaint();
        destList.repaint();
        resultsTable.repaint();
        prefs.putBoolean(PREF_THEME, dark);
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
        // The mode is stored as what the last job actually was; the verification switch is stored
        // separately so it survives switching between Copy and Verify only. An older setting names a mode
        // that no longer exists (COPY_AND_VERIFY was the only copy mode), which maps straight onto it.
        String storedType = prefs.get(PREF_JOB_TYPE, FileSyncEngine.JobType.COPY_AND_VERIFY.name());
        boolean wasVerifyOnly = FileSyncEngine.JobType.VERIFY_ONLY.name().equals(storedType);
        boolean storedVerify = prefs.getBoolean(PREF_VERIFY_AFTER_COPY,
            !FileSyncEngine.JobType.COPY_ONLY.name().equals(storedType));
        verifyCheck.setSelected(storedVerify);
        selectCopyMode(!wasVerifyOnly);
        darkModeCheck.setSelected(prefs.getBoolean(PREF_THEME, false));
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
        currentJobType = resolvedJobType();
        prefs.put(PREF_JOB_TYPE, currentJobType.name());
        prefs.putBoolean(PREF_VERIFY_AFTER_COPY, verifyCheck.isSelected());
        prefs.putBoolean(PREF_THEME, darkModeCheck.isSelected());
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
    /** Scan options changed: the same invalidation, because the rows depend on them. */
    private void noteScanOptionsChanged() {
        if (scanOptionsStale || sourceModel.total() == 0) {
            return;
        }
        scanOptionsStale = true;
        log("Scan options changed - press Scan Files to apply them.");
    }

    /** Reading a directory is a step a test may want to slow down, so it is behind a seam. */
    interface Scanner {
        DirectoryScanner.ScanResult scan(Path root, ScanOptions options);
    }

    private Scanner scanner = DirectoryScanner::scanFiles;

    void setScannerForTesting(Scanner replacement) {
        this.scanner = replacement;
    }

    /** The button a user would press; package-private so a test can press it too. */
    void scanRequested() {
        scanDirectories();
    }

    /** One scan, identified. Nothing about a job may be decided from rows that are not backed by this. */
    record LoadedScan(long generation, Path sourceRoot, Path destRoot, ScanOptions options,
                      int sourceRows, int destinationRows) {
    }

    /** The scan currently on screen, or null when nothing on screen can be trusted for a job. */
    LoadedScan scanInUse() {
        return loadedScan;
    }

    /** The request the current job was started from, exactly as it was captured at Run. */
    FileSyncEngine.JobRequest requestInUse() {
        return pendingRequest;
    }

    /** Why Run is not allowed right now, or null when it is. Kept separate from the dialog so it is testable. */
    String runRefusal() {
        if (scanning) {
            return "A scan is still running. Wait for it to finish - the lists on screen are the ones the job"
                + " would use, and they are not complete yet.";
        }
        LoadedScan scan = loadedScan;
        if (scan == null) {
            return "Nothing has been scanned yet. Scan the two directories first: an empty selection means no"
                + " files, not all files, and this program will not guess what you meant.";
        }
        Path source = userPath(sourcePathField.getText().trim());
        Path dest = userPath(destPathField.getText().trim());
        if (!java.util.Objects.equals(source, scan.sourceRoot())
                || !java.util.Objects.equals(dest, scan.destRoot())) {
            return "The directories changed since the scan (" + scan.sourceRoot() + " and " + scan.destRoot()
                + " were read). The rows on screen belong to those directories. Scan again before running.";
        }
        ScanOptions options = currentScanOptions();
        if (!java.util.Objects.equals(options, scan.options())) {
            return "The scan options changed since the scan (" + scan.sourceRoot() + " and " + scan.destRoot()
                + " were read with " + scan.options() + "). Files that the old options hid may be missing from"
                + " the lists, and files they showed may not belong here. Scan again before running.";
        }
        return null;
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

        Path source = userPath(sourcePath);
        Path dest = userPath(destPath);
        if (source == null || dest == null) {
            JOptionPane.showMessageDialog(this,
                "That path cannot be read with this system's file-name encoding. Run under a UTF-8 locale "
                    + "(for example LC_ALL=C.UTF-8) to use directory names this one cannot represent.",
                "Unusable Path", JOptionPane.WARNING_MESSAGE);
            return;
        }
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

        // Show the directory the scan is about to read, with '~' and relative paths resolved the way the
        // scan itself resolves them. Written under a guard, because this is the program writing the field,
        // not the user changing it, and it must not be read as a path change.
        Path sourceShown = source;
        Path destShown = dest;
        withProgrammaticPathEdit(() -> {
            sourcePathField.setText(sourceShown.toString());
            destPathField.setText(destShown.toString());
        });

        final long generation = ++scanGeneration;
        inFlightScans.incrementAndGet();
        scanning = true;
        statusLabel.setText("Scanning files...");
        savePreferences();

        SwingWorker<ScanPair, Void> worker = new SwingWorker<>() {
            @Override
            protected ScanPair doInBackground() {
                return new ScanPair(scanner.scan(source, options), scanner.scan(dest, options));
            }

            @Override
            protected void done() {
                ScanPair result = null;
                Exception failure = null;
                try {
                    result = get();
                } catch (Exception e) {
                    failure = e;
                }
                try {
                    if (failure != null) {
                        throw failure;
                    }
                    finishScan(generation, source, dest, options, result, untickedSource, untickedDest);
                } catch (Exception e) {
                    String message = rootCauseMessage(e);
                    statusLabel.setText("Scan failed: " + message);
                    log("Scan failed: " + message);
                    loadedScan = null;
                } finally {
                    // "Scanning" means a scan is reading the directories, so it follows the count of scans in
                    // flight, not the fate of one of them: a discarded scan must not leave the window stuck
                    // saying it is scanning, and a scan that finishes while a newer one is still queued must
                    // not clear the flag either.
                    scanning = inFlightScans.decrementAndGet() > 0;
                }
            }
        };
        worker.execute();
    }

    /** Rows on screen, or null when the scan they came from is no longer the one that counts. */
    int loadedSourceRows() {
        return loadedScan == null ? 0 : loadedScan.sourceRows();
    }

    /**
     * Put a finished scan's rows on screen - or throw them away.
     *
     * <p>A scan belongs to the window state it was started from. If the directories have been edited since, or
     * another scan has been started, these rows belong to something else, and putting them on screen would list
     * one directory's files under another directory's name. They are discarded, and the log says so: a silently
     * dropped result is indistinguishable from a scan that found nothing.
     *
     * @param generation the scan number this result came from
     */
    void finishScan(long generation, Path source, Path dest, ScanOptions options, ScanPair result,
            Set<String> untickedSource, Set<String> untickedDest) {
        if (generation != scanGeneration) {
            discardedScans.incrementAndGet();
            log("A scan of " + source + " and " + dest + " finished after a newer scan had started; its"
                + " results were discarded.");
            return;
        }
        // A re-scan keeps the ticks the user actually removed; every other row - including one that
        // disappeared under a filter and has now come back - is ticked.
        applyScanResult(sourceModel, result.source(), untickedSource);
        applyScanResult(destModel, result.dest(), untickedDest);
        loadedScan = new LoadedScan(generation, source, dest, options,
            result.source().files().size(), result.dest().files().size());
        statusLabel.setText(String.format("Scanned: %d source files, %d destination files",
            result.source().files().size(), result.dest().files().size()));
        log(String.format("Scanned %d source files and %d destination files.",
            result.source().files().size(), result.dest().files().size()));
        logScanDetails(result.source(), "source");
        logScanDetails(result.dest(), "destination");
        scanOptionsStale = false;
    }

    /** The number a finished scan has to carry for its rows to be allowed on screen. */
    long currentScanGeneration() {
        return scanGeneration;
    }

    /** Scan the two directories a caller names, bypassing the fields - the seam the tests use. */
    void applyFinishedScan(long generation, Path source, Path dest, ScanOptions options) {
        finishScan(generation, source, dest, options,
            new ScanPair(DirectoryScanner.scanFiles(source, options),
                DirectoryScanner.scanFiles(dest, options)),
            Set.of(), Set.of());
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

    record ScanPair(DirectoryScanner.ScanResult source, DirectoryScanner.ScanResult dest) {
    }

    private static String rootCauseMessage(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
    }

    // ------------------------------------------------------------------ job control

    /** The mode half of the choice: does this job copy, or only compare. */
    private void selectCopyMode(boolean copy) {
        if (engine.isRunning()) {
            return;
        }
        // The two mode buttons are one segmented control, so exactly one of them is selected.
        // setSelected, not doClick: a click would re-enter this method through the action listener.
        copyButton.setSelected(copy);
        verifyOnlyButton.setSelected(!copy);
        UiTheme.paintSegment(copyButton, copy);
        UiTheme.paintSegment(verifyOnlyButton, !copy);
        currentJobType = resolvedJobType();
        updateRunButtonLabel();
        verifyCheck.setEnabled(copy);
        updateWorkSetSummary();
    }

    /**
     * The job this combination means. Verification only means anything to a job that copies, so the switch
     * is read only in Copy mode - a Verify only job compares, whatever the switch says.
     */
    private FileSyncEngine.JobType resolvedJobType() {
        if (!copyButton.isSelected()) {
            return FileSyncEngine.JobType.VERIFY_ONLY;
        }
        return verifyCheck.isSelected()
            ? FileSyncEngine.JobType.COPY_AND_VERIFY : FileSyncEngine.JobType.COPY_ONLY;
    }

    /** A button that says "job" while quietly not verifying is the kind of vagueness this tool avoids. */
    private void updateRunButtonLabel() {
        if (runButton == null) {
            return;
        }
        currentJobType = resolvedJobType();
        runButton.setText(switch (currentJobType) {
            case COPY_AND_VERIFY -> "Run job";
            case COPY_ONLY -> "Run copy";
            case VERIFY_ONLY -> "Run verify";
        });
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
        Iterable<String> untickedAtDestination = currentJobType.copies()
            ? List.of()
            : destModel.untickedPaths();
        FileSyncEngine.WorkSetPlan plan = FileSyncEngine.planWorkSet(sourceModel.selectedCount(),
            untickedAtDestination, rel -> {
                DirectoryEntry row = sourceModel.find(rel);
                return row != null && row.selected();
            });
        StringBuilder text = new StringBuilder("Work set: ").append(plan.willProcess()).append(" file(s)");
        if (currentJobType.copies()) {
            text.append(" - source ticks only; a copy ignores the destination list");
            if (!currentJobType.verifies()) {
                text.append(", and nothing is compared afterwards");
            }
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
        sourcePathField.setEditable(idle);
        destPathField.setEditable(idle);
        copyButton.setEnabled(idle);
        verifyOnlyButton.setEnabled(idle);
        verifyCheck.setEnabled(idle && copyButton.isSelected());
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
        runRequested();
    }

    /**
     * Press Run.
     *
     * <p>The refusal check and the capture live here rather than in the button handler, so the button, the
     * keyboard shortcut and the tests all go through the same rule.
     */
    void runRequested() {
        String refusal = runRefusal();
        if (refusal != null) {
            reportRefusal(refusal);
            return;
        }

        // One request, captured once. Everything after this point - the engine, the collision answer, the
        // re-run after that answer - works from this object, so ticking a row while the job is running cannot
        // change what the running job does, and a re-run is provably the same job.
        FileSyncEngine.Selection sourceSelection = selectionModelOf(sourceModel);
        FileSyncEngine.Selection destSelection = selectionModelOf(destModel);
        FileSyncEngine.JobRequest request = new FileSyncEngine.JobRequest(
            loadedScan.sourceRoot(), loadedScan.destRoot(), resolvedJobType(),
            sourceSelection, destSelection, loadedScan.options(), extraCheck.isSelected(),
            dryRunCheck.isSelected());
        pendingRequest = request;

        startRequest(request);
    }

    /** Re-start the exact request that is already captured; this is what a collision answer belongs to. */
    private void runCapturedRequest() {
        if (engine.isRunning() || pendingRequest == null) {
            return;
        }
        startRequest(pendingRequest);
    }

    /**
     * Say, in the window and in the log, why a job was not started. The log is the part that does not need a
     * display, and it is written first: a refusal that only existed in a dialog would be forgotten the moment
     * it was closed.
     */
    /**
     * Show a message, but never fail over showing it. A headless JVM has nowhere to put a dialog; the words
     * have already gone to the log, which is the channel that survives without a display.
     */
    private void inform(String message, String title, int kind) {
        try {
            JOptionPane.showMessageDialog(this, message, title, kind);
        } catch (java.awt.HeadlessException noDisplay) {
            // Logged by the caller. Crashing the window to report a refusal would be its own bug.
        }
    }

    private void reportRefusal(String refusal) {
        log("Refused to start: " + refusal);
        statusLabel.setText("Run refused - nothing scanned, or the scan no longer matches the window");
        try {
            JOptionPane.showMessageDialog(this, refusal, "Nothing Scanned", JOptionPane.WARNING_MESSAGE);
        } catch (java.awt.HeadlessException noDisplay) {
            // The words are already in the log; there is nowhere else to put them.
        }
    }

    /** How many finished scans were thrown away rather than applied. */
    int discardedScanCount() {
        return discardedScans.get();
    }

    /** Whether a scan is in flight right now. */
    boolean scanInProgress() {
        return scanning;
    }

    /** Start the job described by {@code request} - which is the request that was captured when Run was
     * pressed, never a newer one built from what the lists look like now.
     */
    private void startRequest(FileSyncEngine.JobRequest request) {
        // Preflight before touching anything, so the user gets one clear dialog instead of a
        // failed job in the log. The engine repeats every check itself.
        List<String> problems = engine.preflight(request);
        if (!problems.isEmpty()) {
            inform(String.join("\n", problems), "Preflight failed", JOptionPane.ERROR_MESSAGE);
            for (String problem : problems) {
                log("preflight: " + problem);
            }
            return;
        }

        int selectedSource = request.sourceSelection().selected().size();
        if (selectedSource == 0 && request.destinationSelection().selected().isEmpty()) {
            inform("Nothing is ticked in either list. An empty selection means no files, not all files.",
                "Empty Selection", JOptionPane.WARNING_MESSAGE);
            return;
        }

        savePreferences();
        currentJobType = request.jobType();
        launchedJobType = request.jobType();
        log("Starting " + currentJobType.describe() + "...");
        if (!currentJobType.verifies()) {
            log("Verification is OFF: files will be copied but not compared.");
        }
        log("Source: " + request.sourceDir());
        log("Destination: " + request.destDir());
        log("Selected: " + selectedSource + " source file(s), "
            + request.destinationSelection().selected().size() + " destination file(s)");
        engine.setJobTimeoutMillis(jobTimeoutMillis());
        if (engine.jobTimeoutMillis() > 0) {
            log("Job timeout: " + engine.jobTimeoutMillis() + " ms");
        }

        progressBar.setValue(0);
        progressBar.setString("Running...");
        showResults(null);

        // Only a request the engine accepted gets this far. A request refused by preflight, or by an empty
        // selection, returned earlier, so the finished job already on screen keeps its own pairing. The
        // completion callback below names the request it belongs to; there is no "current request" field for a
        // later job to overwrite.
        if (!engine.start(request, new FileSyncEngine.ProgressListener() {
            @Override
            public void onPhase(String phase) {
                SwingUtilities.invokeLater(() -> {
                    phaseLabel.setText("Phase: " + phase);
                    log(phase + "...");
                    if ("copying".equals(phase) && !explanationShown) {
                        // Measured behaviour of rsync, and the single most confusing thing about a large
                        // copy: the file you are waiting for is in the destination the whole time, under a
                        // hidden name, and only gets its real name at the end of its transfer.
                        explanationShown = true;
                        log("  rsync writes each file as a hidden temporary file in its destination folder"
                            + " (for example .big.iso.qjX7kX) and renames it to its real name only when that"
                            + " file's transfer is complete. A large file therefore appears in the"
                            + " destination at the end of its copy, not the start - show hidden files in"
                            + " your file manager to watch it grow.");
                    }
                });
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
                // The request is captured in this closure: it is the one this callback describes, not whatever
                // the window is thinking about now.
                SwingUtilities.invokeLater(() -> finishJob(request, result));
            }
        })) {
            inform("A job is already running.", "Job Running", JOptionPane.WARNING_MESSAGE);
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
            case "TRANSFERRING" -> "[> copying]";
            case "WOULD_COPY" -> "[WOULD COPY]";
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

    /**
     * The window's answer to a collision question (&#167;2.11). A seam rather than a direct dialog call so the
     * <i>lifetime</i> of an answer is testable without a display: the rule being enforced is that an answer
     * belongs to one collision set, not to the window.
     */
    interface CollisionConfirmation {
        boolean accept(String question);
    }

    /**
     * The question the window asks before copying colliding names.
     *
     * <p>It names the files at risk, because that is what the answer is about, and it does not claim they are
     * the same file: whether they collide is a property of the destination volume, which this program cannot
     * see. Saying "these are identical and one will survive" would be a guess dressed up as a fact.
     */
    static String collisionCopyQuestion(List<String> collisions) {
        List<String> shown = new ArrayList<>();
        for (String pair : collisions) {
            shown.add(RsyncNameCodec.forDisplay(pair));
        }
        return "Colliding names: " + String.join(", ", shown)
            + ". They differ only by case or by how an accent is written, and the destination would hold one"
            + " file where the list has more than one. Copy them anyway?";
    }

    private CollisionConfirmation collisionConfirmation = question ->
        JOptionPane.showConfirmDialog(this, question + "\n\nCopy them anyway?", "Colliding Names",
            JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION;

    void setCollisionConfirmation(CollisionConfirmation confirmation) {
        this.collisionConfirmation = confirmation;
    }

    /**
     * Ask about one specific collision set, and accept exactly that set.
     *
     * <p>Nothing here is remembered as "the user is fine with collisions". The acceptance is handed to the
     * engine as the names it was granted for; a different pair in a different directory is a different
     * question and gets asked again.
     */
    boolean askAboutCollisions(List<String> collisions, String question) {
        if (!collisionConfirmation.accept(question)) {
            return false;
        }
        // The engine refused a specific job; the answer belongs to that job and nothing else. Accepting here
        // means the same request - same directories, same kind of job, same files - may go ahead. It does not
        // make this window permissive about names in general.
        engine.acceptDetectedCollisions();
        return true;
    }

    /** Test window into the engine this panel owns. */
    FileSyncEngine engineInUse() {
        return engine;
    }

    private void finishJob(FileSyncEngine.JobRequest request, FileSyncEngine.JobResult result) {
        livenessTimer.stop();
        setJobControlsEnabled(true);
        completedJob = new CompletedJob(request, result);

        // §2.11: the engine refused rather than overwrite one selected file with another. That refusal is
        // the user's decision to make, once: accepting re-runs the same job with the risk acknowledged.
        if (result.status() == FileSyncEngine.JobStatus.FAILED
                && result.message().contains("colliding name(s)")) {
            List<String> detected = engine.collisionsDetectedByLastJob();
            if (!detected.isEmpty() && askAboutCollisions(detected, collisionCopyQuestion(detected))) {
                log("Collision risk accepted for these names - starting that job again.");
                // The request that was refused is the one that runs. Capturing a fresh one from the lists on
                // screen would put files under an answer the user never saw.
                runCapturedRequest();
                return;
            }
        }

        if (result.isSuccess()) {
            progressBar.setString("Complete");
            progressBar.setValue(100);
            // A copy that compared nothing has to read as that on the status line too, not only in the log.
            statusLabel.setText(launchedJobType != null && !launchedJobType.verifies()
                ? "Copy complete - nothing was compared." : "Job completed.");
            log("");
            log("=== JOB COMPLETED ===");
            log(result.message());
        } else {
            // A stopped job is not the same report as a failed one. A user who pressed Cancel, and a job that
            // ran into a time limit that was too short, both need to be able to read that back out of the
            // window hours later; "Job failed." on its own invites the wrong conclusion about the files.
            boolean stopped = result.wasAborted();
            progressBar.setString(stopped ? "Stopped" : "Failed");
            statusLabel.setText(switch (result.abortCause()) {
                case TIMED_OUT -> "Job stopped: it passed the time limit.";
                case USER_CANCELLED -> "Job cancelled.";
                case ENGINE_CLOSED -> "Job stopped: the program was closing.";
                case NONE -> "Job failed.";
            });
            log("");
            log(stopped ? "=== JOB STOPPED ===" : "=== JOB FAILED ===");
            log("Reason: " + result.message());
            if (result.rsyncExitCode() != 0) {
                log("rsync exit code: " + result.rsyncExitCode());
            }
        }
        log(String.format("Totals: checked=%d copied=%d matched=%d mismatched=%d missing=%d extra=%d",
            result.filesChecked(), result.filesCopied(), result.filesMatched(),
            result.filesMismatched(), result.filesMissing(), result.filesExtra()));
        if (result.transfersAnnounced() > 0 || result.filesCopied() > 0 || result.filesWouldCopy() > 0
                || result.filesUncertain() > 0) {
            // Checked counts verdicts, so an extra file - reported without being compared - is not in it. The
            // transfer figures answer a different question, and rsync's own counts stay beside them rather
            // than being merged into the figure this program can support.
            log(String.format("  transfers: copied=%d would-copy=%d uncertain=%d not-transferred=%d,"
                            + " rsync announced=%d, rsync said it completed=%d; rows reported=%d",
                    result.filesCopied(), result.filesWouldCopy(), result.filesUncertain(),
                    result.filesNotTransferred(), result.transfersAnnounced(),
                    result.transfersCompletedByRsync(), result.filesProcessed()));
        }
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
            // A file that was written, or would have been, is not a problem: it is the job's own record of
            // what it did. Only a comparison verdict that failed, or a file that is not where it should be,
            // counts as one.
            boolean problem = switch (outcome.kind()) {
                case "MISMATCH", "MISSING", "EXTRA" -> true;
                default -> false;
            };
            if (onlyProblemsCheck.isSelected() && !problem) {
                continue;
            }
            table.addRow(new Object[]{
                outcome.kind(),
                // Escaped for display only: a name with a newline in it would otherwise break the row
                // apart, and the filter box would offer a match on text that is not part of the name.
                RsyncNameCodec.forDisplay(outcome.relPath()),
                FileHasher.shortHash(outcome.sourceHash()),
                FileHasher.shortHash(outcome.destHash()),
                // Appended last, like the CSV column: what the job did to the bytes is a separate answer
                // from the verdict, and a stopped job has to be able to show COPIED next to UNCERTAIN.
                outcome.transfer() == null ? "" : outcome.transfer().name()
            });
            shown++;
        }
        resultsTable.setEnabled(true);
        exportCsvButton.setEnabled(true);
        exportJsonButton.setEnabled(true);
        if (result.truncated()) {
            // The tab count says how many rows are on show, and that they are part of a bigger job: a capped
            // table with a plain count reads as a complete record. The log says the same in words, next to the
            // totals the reader is adding up.
            outputTabs.setTitleAt(1, "Results (" + shown + " of "
                    + (result.rowsListed() + result.rowsOmitted()) + ")");
            log(String.format("  %d row(s) are not listed here, because of the results limit: this report holds"
                    + " %d of the job's %d row(s). The totals above count the whole job.",
                    result.rowsOmitted(), result.rowsListed(),
                    result.rowsListed() + result.rowsOmitted()));
        } else {
            outputTabs.setTitleAt(1, "Results (" + shown + ")");
        }
    }

    /**
     * Decide whether a report path is allowed for the job that finished, and write it.
     *
     * <p>The file chooser only chooses a path. Everything about whether that path may be written is
     * {@link ReportExporter}'s rule, applied to the directories of the request the job was run from - not to
     * whatever the two fields say now. Kept separate from the chooser so the rule is testable without a
     * display.
     */
    Path writeCompletedReport(Path target, String format, boolean allowInsideDataTrees) throws IOException {
        CompletedJob done = completedJob;
        if (done == null) {
            throw new ReportExporter.ReportRefusal("There is no finished job to export.");
        }
        return ReportExporter.writeReport(target, done.result(),
            "json".equals(format) ? ReportExporter.Format.JSON : ReportExporter.Format.CSV,
            done.request().sourceDir(), done.request().destDir(), allowInsideDataTrees);
    }

    /** The finished job whose rows are on screen, together with the request it came from. */
    CompletedJob completedJobInUse() {
        return completedJob;
    }

    private void exportReport(String format) {
        if (completedJob == null) {
            return;
        }
        JFileChooser chooser = new JFileChooser();
        String base = "file-copy-report";
        chooser.setSelectedFile(new File(base + "." + format));
        chooser.setDialogTitle("Export job report");
        int choice;
        try {
            choice = chooser.showSaveDialog(this);
        } catch (java.awt.HeadlessException noDisplay) {
            log("No display is available, so the file chooser could not be opened - use the command line"
                + " front end (--csv/--json) to export here.");
            statusLabel.setText("Export needs a display here - use --csv or --json on the command line.");
            return;
        }
        if (choice != JFileChooser.APPROVE_OPTION) {
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
        // The engine's rules, not a separate GUI policy: a report never replaces a file, and a report
        // placed among the files the job copies is the user's decision to make - knowingly.
        boolean allowInsideDataTrees = false;
        while (true) {
            try {
                Path written = writeCompletedReport(target, format, allowInsideDataTrees);
                log("Report written: " + written);
                statusLabel.setText("Report written: " + written);
                return;
            } catch (ReportExporter.ReportRefusal refusal) {
                String why = refusal.getMessage();
                if (!allowInsideDataTrees && why.startsWith("The report would be written inside")) {
                    int answer = JOptionPane.showConfirmDialog(this,
                        why + "\n\nThe report would sit among the files this job copies and verifies, so the"
                            + " next scan would see it as an extra. Nothing that already exists would be"
                            + " overwritten.\n\nWrite it there anyway?",
                        "Report Inside the Copied Files", JOptionPane.YES_NO_OPTION,
                        JOptionPane.WARNING_MESSAGE);
                    if (answer == JOptionPane.YES_OPTION) {
                        allowInsideDataTrees = true;
                        continue;
                    }
                }
                log("Export refused: " + why);
                JOptionPane.showMessageDialog(this, why, "Export Refused", JOptionPane.WARNING_MESSAGE);
                return;
            } catch (IOException e) {
                log("Export failed: " + e.getMessage());
                JOptionPane.showMessageDialog(this, "Could not write report: " + e.getMessage(),
                    "Export Failed", JOptionPane.ERROR_MESSAGE);
                return;
            }
        }
    }

    // ------------------------------------------------------------------ chooser (3.1)

    /**
     * Both path fields are editable: typing a directory is faster than opening a chooser for one you
     * already know. Enter loads it. What must not be possible is keeping the file lists that were read
     * from the directory that field used to name.
     */
    private void installPathField(JTextField field) {
        field.setToolTipText("Type a directory, or press Browse. Enter scans both directories.");
        field.addActionListener(e -> scanDirectories());
        field.getDocument().addDocumentListener(new SimpleDocumentFilter(this::pathFieldChanged, null));
    }

    /**
     * The rows on screen are ticks against paths in the directory that was scanned. Once the field names a
     * different directory, those ticks mean nothing, so they are dropped and said out loud - the alternative
     * is a job whose selection came from somewhere else.
     */
    private void pathFieldChanged() {
        if (pathEditSuppressed) {
            return;
        }
        // A scan that is reading the directory this field used to name is now reading the wrong place, so its
        // results are no longer allowed on screen - whether or not there were rows here to clear.
        scanGeneration++;
        if (!hasLoadedLists()) {
            return;
        }
        sourceModel.clear();
        destModel.clear();
        loadedScan = null;
        updateWorkSetSummary();
        // The two lists go, because they belong to the directory that used to be named here. The finished job's
        // rows stay: they describe a job that happened, and changing a path field does not un-happen it. Its
        // report is still exported against the directories that job worked in.
        log("Path changed - the loaded lists were cleared, because they belong to the directory this field "
            + "used to name. Press Scan Files (Ctrl+S) to load the new one. The results of the job that"
            + " already ran are kept.");
    }

    /** Write a path field without the watcher treating it as the user changing directories. */
    private void withProgrammaticPathEdit(Runnable edit) {
        pathEditSuppressed = true;
        try {
            edit.run();
        } finally {
            pathEditSuppressed = false;
        }
    }

    private boolean hasLoadedLists() {
        return sourceModel.total() > 0 || destModel.total() > 0;
    }

    /**
     * A path as the user wrote it. '~' means the home directory and a bare relative path is taken from
     * where the program was started, which is how a shell would read the same text. Returns null when the
     * text cannot be a path on this system at all.
     */
    static Path userPath(String text) {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        String home = System.getProperty("user.home", "");
        if (trimmed.equals("~") && !home.isEmpty()) {
            trimmed = home;
        } else if (trimmed.startsWith("~/") && !home.isEmpty()) {
            trimmed = home + trimmed.substring(1);
        }
        try {
            return Path.of(trimmed).toAbsolutePath().normalize();
        } catch (java.nio.file.InvalidPathException e) {
            // A name this system's file-name encoding cannot represent. Under LANG=C that includes every
            // non-ASCII character; the same hazard the scanner reports.
            return null;
        }
    }

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
            setFont(UiTheme.baseFont());
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends DirectoryEntry> list,
                                                      DirectoryEntry value, int index,
                                                      boolean isSelected, boolean cellHasFocus) {
            UiTheme.Palette palette = UiTheme.palette();
            setSelected(value.selected());
            setText(value.relPath());
            if (isSelected) {
                setBackground(palette.selection());
                setForeground(palette.text());
            } else {
                // Alternating rows do the work a grid used to do: the eye can follow a long list.
                setBackground(index % 2 == 0 ? palette.window() : palette.alternateRow());
                setForeground(palette.text());
            }
            return this;
        }
    }

    /**
     * Results cell: a coloured dot in front of the word. A colour alone would fail for a colour-blind
     * reader and for anyone printing the export; the word is always there, the dot is only the
     * fast part of reading a 2000 row table.
     */
    private static final class OutcomeCellRenderer extends DefaultTableCellRenderer {
        private static final long serialVersionUID = 1L;

        OutcomeCellRenderer() {
            setHorizontalAlignment(LEFT);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column) {
            Component base = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
            UiTheme.Palette palette = UiTheme.palette();
            String text = value == null ? "" : value.toString();
            setFont(column == 0 ? UiTheme.baseFont() : UiTheme.monoFont());
            if (!isSelected) {
                setBackground(row % 2 == 0 ? palette.window() : palette.alternateRow());
                setForeground(column == 0 ? UiTheme.outcomeColor(text) : palette.text());
            }
            if (column == 0) {
                setIcon(new DotIcon(UiTheme.outcomeColor(text)));
                setBorder(new EmptyBorder(0, 2, 0, 8));
            } else {
                setIcon(null);
                setBorder(new EmptyBorder(0, 6, 0, 6));
            }
            return base;
        }
    }

    /** A small filled circle: the status colour at a glance, never the status alone. */
    private static final class DotIcon implements javax.swing.Icon {
        private static final int SIZE = 10;
        private final Color color;

        DotIcon(Color color) {
            this.color = color;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(color);
                g2.fillOval(x, y + (SIZE - 10) / 2, 10, 10);
            } finally {
                g2.dispose();
            }
        }

        @Override
        public int getIconWidth() {
            return SIZE;
        }

        @Override
        public int getIconHeight() {
            return SIZE;
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
