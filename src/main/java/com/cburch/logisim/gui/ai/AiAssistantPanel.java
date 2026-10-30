/*
 * Logisim-evolution - digital logic design tool and simulator
 * Copyright by the Logisim-evolution developers
 *
 * https://github.com/logisim-evolution/
 *
 * This is free software released under GNU GPLv3 license
 */

package com.cburch.logisim.gui.ai;

import static com.cburch.logisim.ai.i18n.AiStrings.S;

import com.cburch.logisim.ai.agent.AgentController;
import com.cburch.logisim.ai.agent.AgentPermissionManager;
import com.cburch.logisim.ai.agent.AgentState;
import com.cburch.logisim.ai.agent.AgentTaskListener;
import com.cburch.logisim.ai.i18n.AiStrings;
import com.cburch.logisim.ai.model.ModelConfig;
import com.cburch.logisim.ai.validation.TaskSpec;
import com.cburch.logisim.ai.validation.ValidationStatus;
import com.cburch.logisim.ai.validation.VerificationEvidence;
import com.cburch.logisim.ai.vision.client.VisionServiceException;
import com.cburch.logisim.ai.vision.ui.VisionImportController;
import com.cburch.logisim.ai.vision.ui.VisionStrings;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.util.LocaleListener;
import com.cburch.logisim.util.LocaleManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import javax.swing.JFileChooser;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;

/** Right-side Codex-style task panel for the optional AI Circuit Agent. */
public final class AiAssistantPanel extends JPanel implements AutoCloseable, LocaleListener {
  private static final long serialVersionUID = 1L;
  private final Project project;
  private final AgentPermissionManager permissions;
  private final AgentController controller;
  private final VisionImportController visionController;
  private final ExecutorService worker;

  private final JTextField endpointField = new JTextField(26);
  private final JTextField modelField = new JTextField(18);
  private final JPasswordField apiKeyField = new JPasswordField(18);
  private final JCheckBox allowEdits = new JCheckBox();
  private final JLabel endpointLabel = new JLabel();
  private final JLabel modelLabel = new JLabel();
  private final JLabel apiKeyLabel = new JLabel();
  private final ChatTranscriptView transcript = new ChatTranscriptView();
  private final PromptArea prompt = new PromptArea();
  private final JLabel title = new JLabel();
  private final JLabel permissionState = new JLabel();
  private final JLabel status = new JLabel();
  private final JTextArea statusDetail = new JTextArea();
  private final JButton settings = new JButton();
  private final JButton permissionButton = new JButton();
  private final JButton saveSettings = new JButton();
  private final JButton cancelSettings = new JButton();
  private final JButton closePermissions = new JButton();
  private final JTextArea settingsHint = descriptionArea();
  private final JTextArea settingsError = descriptionArea();
  private final JTextArea permissionDescription = descriptionArea();
  private final JTextArea apiKeyHint = descriptionArea();
  private final JPanel settingsPanel;
  private final JPanel permissionPanel;
  private final JPanel taskActions = new JPanel(new GridBagLayout());
  private final JPanel specificationActions = new JPanel(new GridBagLayout());
  private final JButton reviseSpec = new JButton();
  private final JLabel composeContext = new JLabel();
  private SpecificationCard activeSpecification;
  private JDialog settingsDialog;
  private JDialog permissionsDialog;
  private JDialog reviewDialog;
  private ReviewDetailsView reviewView;
  private final JButton closeReview = new JButton();
  private SettingsSnapshot settingsSnapshot;
  private final JButton send = new JButton();
  private final JButton visionImport = new JButton();
  private final JButton cancel = new JButton();
  private final JButton apply = new JButton();
  private final JButton discard = new JButton();
  private final JButton cleanup = new JButton();
  private final JButton confirmSpec = new JButton();
  private final JButton reviewDetails = new JButton();
  private final List<TranscriptEntry> transcriptEntries = new ArrayList<>();
  private String displayedDetail = "";
  private volatile boolean closed;
  private Map<String, Object> fullReview = Map.of();
  private boolean reviewApplyAllowed;
  private boolean retainedCopy;
  private AgentState displayedState = AgentState.READY;
  private volatile boolean visionBusy;
  private volatile boolean visionCancellable;
  private volatile Future<?> visionTask;
  private long visionOperation;

  public AiAssistantPanel(Project project) {
    this(project, new AgentPermissionManager(), null);
  }

  AiAssistantPanel(
      Project project, AgentPermissionManager permissions, AgentController taskController) {
    super(new BorderLayout());
    this.project = project;
    this.permissions = permissions;
    this.controller = taskController == null ? new AgentController(permissions) : taskController;
    this.visionController = new VisionImportController();
    this.worker = Executors.newSingleThreadExecutor(new AgentThreadFactory());
    allowEdits.setSelected(permissions.isEditsAllowed());
    endpointField.setName("endpoint");
    modelField.setName("model");
    apiKeyField.setName("apiKey");
    allowEdits.setName("allowEdits");
    transcript.setName("transcript");
    prompt.setName("prompt");
    status.setName("status");
    send.setName("send");
    visionImport.setName("visionImport");
    cancel.setName("cancel");
    apply.setName("apply");
    discard.setName("discard");
    cleanup.setName("cleanup");
    confirmSpec.setName("confirmSpec");
    reviewDetails.setName("reviewDetails");
    reviseSpec.setName("reviseSpec");
    composeContext.setName("composeContext");
    endpointLabel.setName("endpointLabel");
    modelLabel.setName("modelLabel");
    apiKeyLabel.setName("apiKeyLabel");
    settings.setName("settings");
    permissionButton.setName("permissions");
    permissionState.setName("permissionState");
    statusDetail.setName("statusDetail");
    saveSettings.setName("saveSettings");
    cancelSettings.setName("cancelSettings");
    closePermissions.setName("closePermissions");
    closeReview.setName("closeReview");
    setMinimumSize(new Dimension(340, 240));
    setPreferredSize(new Dimension(380, 600));
    setBackground(AiTheme.background());
    setForeground(AiTheme.foreground());
    setFont(AiTheme.font(14));

    endpointField.setText(
        setting(
            "logisim.ai.endpoint",
            "LOGISIM_AI_ENDPOINT",
            "https://api.openai.com/v1/chat/completions"));
    modelField.setText(setting("logisim.ai.model", "LOGISIM_AI_MODEL", ""));
    final var envKey = System.getenv("LOGISIM_AI_API_KEY");
    if (envKey != null) apiKeyField.setText(envKey);
    settingsPanel = buildSettingsPanel();
    permissionPanel = buildPermissionPanel();
    prompt.setLineWrap(true);
    prompt.setWrapStyleWord(true);
    prompt.setRows(2);
    prompt.setColumns(20);
    prompt.setFont(AiTheme.font(14));
    prompt.setBackground(AiTheme.surface());
    prompt.setForeground(AiTheme.foreground());
    prompt.setMargin(new Insets(8, 3, 8, 3));
    title.setFont(AiTheme.font(14).deriveFont(Font.BOLD));
    title.setForeground(AiTheme.foreground());
    title.setIcon(AiTheme.icon("circuit"));
    title.setIconTextGap(8);
    permissionState.setFont(AiTheme.font(12));
    permissionState.setForeground(AiTheme.muted());
    status.setFont(AiTheme.font(12));
    status.setForeground(AiTheme.muted());
    statusDetail.setEditable(false);
    statusDetail.setLineWrap(true);
    statusDetail.setWrapStyleWord(true);
    statusDetail.setOpaque(false);
    statusDetail.setFocusable(false);
    statusDetail.setColumns(20);
    statusDetail.setFont(AiTheme.font(12));
    statusDetail.setForeground(AiTheme.muted());
    statusDetail.setBorder(null);

    for (final var button :
        List.of(send, apply, confirmSpec, saveSettings, closePermissions, closeReview)) {
      AiTheme.styleButton(button, true);
    }
    for (final var button :
        List.of(
            visionImport,
            cancel,
            discard,
            cleanup,
            reviewDetails,
            cancelSettings,
            reviseSpec)) {
      AiTheme.styleButton(button, false);
    }
    AiTheme.styleQuietButton(settings);
    AiTheme.styleQuietButton(permissionButton);
    settings.setIcon(AiTheme.icon("settings"));
    send.setIcon(AiTheme.icon("send"));
    send.setHorizontalTextPosition(javax.swing.SwingConstants.LEFT);
    final var headerActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
    headerActions.setOpaque(false);
    headerActions.add(settings);
    headerActions.add(permissionButton);
    final var heading = new JPanel(new BorderLayout(0, 5));
    heading.setOpaque(false);
    heading.add(title, BorderLayout.NORTH);
    final var subtitle = new JPanel(new BorderLayout(8, 0));
    subtitle.setOpaque(false);
    subtitle.add(status, BorderLayout.CENTER);
    subtitle.add(permissionState, BorderLayout.EAST);
    heading.add(subtitle, BorderLayout.SOUTH);
    final var header = new ResponsiveHeader(heading, headerActions);
    header.setName("assistantHeader");
    header.setBackground(AiTheme.surface());
    header.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, AiTheme.border()),
            BorderFactory.createEmptyBorder(14, 18, 14, 18)));

    taskActions.add(confirmSpec);
    taskActions.add(apply);
    taskActions.add(discard);
    taskActions.add(reviewDetails);
    taskActions.add(cleanup);
    taskActions.setOpaque(false);
    taskActions.setName("taskActions");
    specificationActions.setOpaque(false);
    specificationActions.setName("specificationActions");
    cancel.setEnabled(false);
    apply.setEnabled(false);
    discard.setEnabled(false);
    cleanup.setEnabled(false);
    confirmSpec.setEnabled(false);
    reviewDetails.setEnabled(false);

    final var inputScroll = new JScrollPane(prompt);
    inputScroll.setBorder(BorderFactory.createEmptyBorder());
    inputScroll.getViewport().setBackground(AiTheme.surface());
    final var inputActions = new JPanel(new BorderLayout(8, 0));
    inputActions.setOpaque(false);
    final var inputLeft = new JPanel(new BorderLayout());
    inputLeft.setOpaque(false);
    final var inputButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
    inputButtons.setOpaque(false);
    inputButtons.add(visionImport);
    inputButtons.add(cancel);
    inputLeft.add(inputButtons, BorderLayout.WEST);
    composeContext.setFont(AiTheme.font(12));
    composeContext.setForeground(AiTheme.muted());
    inputLeft.add(composeContext, BorderLayout.CENTER);
    inputActions.add(inputLeft, BorderLayout.CENTER);
    inputActions.add(send, BorderLayout.EAST);
    final var composer =
        AiTheme.roundedPanel(AiTheme.surface(), AiTheme.border(), 12, new Insets(8, 12, 10, 12));
    composer.setName("composer");
    composer.setLayout(new BorderLayout(0, 8));
    composer.add(inputScroll, BorderLayout.CENTER);
    composer.add(inputActions, BorderLayout.SOUTH);
    final var composeWithHelp = new JPanel(new BorderLayout(0, 8));
    composeWithHelp.setOpaque(false);
    composeWithHelp.add(composer, BorderLayout.CENTER);
    composeWithHelp.add(statusDetail, BorderLayout.SOUTH);
    final var south = new JPanel(new BorderLayout(0, 10));
    south.setBackground(AiTheme.surface());
    south.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, AiTheme.border()),
            BorderFactory.createEmptyBorder(12, 18, 14, 18)));
    south.add(taskActions, BorderLayout.NORTH);
    south.add(composeWithHelp, BorderLayout.CENTER);
    final var conversation = new JScrollPane(transcript);
    conversation.setBorder(BorderFactory.createEmptyBorder());
    conversation.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
    conversation.getVerticalScrollBar().setUnitIncrement(18);
    conversation.getViewport().setBackground(AiTheme.background());
    conversation.setBackground(AiTheme.background());

    add(header, BorderLayout.NORTH);
    add(conversation, BorderLayout.CENTER);
    add(south, BorderLayout.SOUTH);

    settings.addActionListener(e -> showSettingsDialog());
    permissionButton.addActionListener(e -> showPermissionsDialog());
    saveSettings.addActionListener(e -> saveModelSettings());
    cancelSettings.addActionListener(e -> cancelModelSettings());
    closePermissions.addActionListener(
        e -> {
          if (permissionsDialog != null) permissionsDialog.dispose();
        });
    allowEdits.addActionListener(
        e -> {
          permissions.setEditsAllowed(allowEdits.isSelected());
          renderPermission();
          if (allowEdits.isSelected() && controller.isWaitingForEditPermission()) setBusy(true);
          else updateActionButtons(displayedState);
          runWorker(
              () -> {
                controller.editPermissionChanged();
                ui(() -> updateActionButtons(controller.getState()));
              });
        });
    send.addActionListener(e -> submit());
    visionImport.addActionListener(e -> chooseVisionImage());
    cancel.addActionListener(
        e -> {
          if (visionBusy) {
            cancelVisionImport();
          } else {
            controller.cancel();
            runWorker(controller::finishCancellation);
          }
        });
    apply.addActionListener(
        e -> {
          setBusy(true);
          runWorker(this::applyReviewed);
        });
    discard.addActionListener(e -> runWorker(this::discardReviewed));
    cleanup.addActionListener(e -> runWorker(this::cleanupArtifacts));
    confirmSpec.addActionListener(
        e -> {
          final var displayedSpecification =
              activeSpecification == null ? null : activeSpecification.specification();
          if (displayedSpecification != null
              && !displayedSpecification.equals(controller.pendingSpecification())) return;
          setBusy(true);
          runWorker(
              () -> {
                if (displayedSpecification == null) controller.confirmSpecification();
                else controller.confirmDisplayedSpecification(displayedSpecification);
              });
        });
    reviseSpec.addActionListener(e -> prompt.requestFocusInWindow());
    reviewDetails.addActionListener(e -> showReviewDialog());
    closeReview.addActionListener(e -> closeReviewDialog());
    LocaleManager.addLocaleListener(this);
    refreshLanguage();
    updateActionButtons(controller.getState());
  }

  private JPanel buildSettingsPanel() {
    final var form = new JPanel(new GridBagLayout());
    form.setOpaque(false);
    final var c = new GridBagConstraints();
    c.insets = new Insets(4, 0, 4, 0);
    c.anchor = GridBagConstraints.WEST;
    c.fill = GridBagConstraints.HORIZONTAL;
    c.weightx = 1;
    c.gridx = 0;
    c.gridy = 0;
    form.add(endpointLabel, c);
    c.gridy++;
    form.add(endpointField, c);
    c.gridy++;
    form.add(modelLabel, c);
    c.gridy++;
    form.add(modelField, c);
    c.gridy++;
    form.add(apiKeyLabel, c);
    c.gridy++;
    form.add(apiKeyField, c);
    c.gridy++;
    form.add(apiKeyHint, c);
    c.gridy++;
    form.add(settingsError, c);
    final var buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
    buttons.setOpaque(false);
    buttons.add(cancelSettings);
    buttons.add(saveSettings);
    final var panel = new JPanel(new BorderLayout(0, 12));
    panel.setName("modelSettings");
    panel.setBackground(AiTheme.surface());
    panel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
    panel.add(settingsHint, BorderLayout.NORTH);
    panel.add(form, BorderLayout.CENTER);
    panel.add(buttons, BorderLayout.SOUTH);
    panel.setPreferredSize(new Dimension(420, 430));
    for (final var label : List.of(endpointLabel, modelLabel, apiKeyLabel)) {
      label.setFont(AiTheme.font(12));
      label.setForeground(AiTheme.muted());
    }
    for (final var field : List.of(endpointField, modelField, apiKeyField)) {
      field.setFont(AiTheme.font(14));
      field.setForeground(AiTheme.foreground());
      field.setBackground(AiTheme.background());
      field.setBorder(
          BorderFactory.createCompoundBorder(
              BorderFactory.createLineBorder(AiTheme.border(), 1, true),
              BorderFactory.createEmptyBorder(8, 10, 8, 10)));
    }
    settingsError.setForeground(new Color(180, 55, 55));
    settingsError.setVisible(false);
    return panel;
  }

  private JPanel buildPermissionPanel() {
    final var options = new JPanel(new BorderLayout(0, 10));
    options.setOpaque(false);
    allowEdits.setOpaque(false);
    allowEdits.setFont(AiTheme.font(14));
    allowEdits.setForeground(AiTheme.foreground());
    options.add(allowEdits, BorderLayout.NORTH);
    options.add(permissionDescription, BorderLayout.CENTER);
    final var buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
    buttons.setOpaque(false);
    buttons.add(closePermissions);
    final var panel = new JPanel(new BorderLayout(0, 16));
    panel.setName("permissionSettings");
    panel.setBackground(AiTheme.surface());
    panel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
    panel.add(options, BorderLayout.CENTER);
    panel.add(buttons, BorderLayout.SOUTH);
    panel.setPreferredSize(new Dimension(400, 200));
    return panel;
  }

  // Detached dialog contents can also be rendered without a native window.
  JPanel modelSettingsPanel() {
    return settingsPanel;
  }

  JPanel permissionSettingsPanel() {
    return permissionPanel;
  }

  // Use the same contents for the native dialog and offscreen UI verification.
  ReviewDetailsView reviewDetailsPanel() {
    if (reviewView == null) reviewView = new ReviewDetailsView(fullReview, apply.isEnabled());
    return reviewView;
  }

  private void showReviewDialog() {
    if (closed || displayedState != AgentState.REVIEW || fullReview.isEmpty()) return;
    if (reviewDialog != null && reviewDialog.isDisplayable()) {
      reviewDialog.toFront();
      return;
    }
    final var content = new JPanel(new BorderLayout(0, 12));
    content.setBackground(AiTheme.background());
    content.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));
    content.add(reviewDetailsPanel(), BorderLayout.CENTER);
    final var footer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
    footer.setOpaque(false);
    footer.add(closeReview);
    content.add(footer, BorderLayout.SOUTH);
    content.setPreferredSize(new Dimension(720, 700));
    final var dialog =
        new JDialog(
            SwingUtilities.getWindowAncestor(this),
            S.get("aiReviewDialogTitle"),
            java.awt.Dialog.ModalityType.MODELESS);
    reviewDialog = dialog;
    dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
    dialog.setContentPane(content);
    dialog.getRootPane().setDefaultButton(closeReview);
    dialog.addWindowListener(
        new WindowAdapter() {
          @Override
          public void windowClosed(WindowEvent event) {
            if (reviewDialog == dialog) reviewDialog = null;
          }
        });
    dialog.pack();
    dialog.setMinimumSize(new Dimension(520, 480));
    dialog.setLocationRelativeTo(this);
    dialog.setVisible(true);
  }

  private void closeReviewDialog() {
    if (reviewDialog != null) reviewDialog.dispose();
  }

  void beginModelSettingsEdit() {
    if (settingsSnapshot == null) {
      settingsSnapshot =
          new SettingsSnapshot(
              endpointField.getText(), modelField.getText(), apiKeyField.getPassword());
    }
    settingsError.setText("");
    settingsError.setVisible(false);
  }

  boolean saveModelSettings() {
    try {
      readModelConfig();
    } catch (RuntimeException e) {
      settingsError.setText(AiStrings.displayMessage(e.getMessage()));
      settingsError.setVisible(true);
      settingsPanel.revalidate();
      return false;
    }
    if (settingsSnapshot != null) settingsSnapshot.clearKey();
    settingsSnapshot = null;
    if (settingsDialog != null) settingsDialog.dispose();
    return true;
  }

  void cancelModelSettings() {
    if (settingsSnapshot != null) {
      endpointField.setText(settingsSnapshot.endpoint());
      modelField.setText(settingsSnapshot.model());
      apiKeyField.setText(new String(settingsSnapshot.apiKey()));
      settingsSnapshot.clearKey();
      settingsSnapshot = null;
    }
    settingsError.setText("");
    settingsError.setVisible(false);
    if (settingsDialog != null) settingsDialog.dispose();
  }

  private void showSettingsDialog() {
    if (closed) return;
    if (settingsDialog != null && settingsDialog.isDisplayable()) {
      settingsDialog.toFront();
      return;
    }
    beginModelSettingsEdit();
    final var dialog =
        new JDialog(
            SwingUtilities.getWindowAncestor(this),
            S.get("aiSettingsTitle"),
            java.awt.Dialog.ModalityType.APPLICATION_MODAL);
    settingsDialog = dialog;
    dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
    dialog.setContentPane(settingsPanel);
    dialog.getRootPane().setDefaultButton(saveSettings);
    dialog.addWindowListener(
        new WindowAdapter() {
          @Override
          public void windowClosing(WindowEvent event) {
            cancelModelSettings();
          }

          @Override
          public void windowClosed(WindowEvent event) {
            if (settingsDialog == dialog) settingsDialog = null;
          }
        });
    dialog.pack();
    dialog.setMinimumSize(new Dimension(360, 420));
    dialog.setLocationRelativeTo(this);
    dialog.setVisible(true);
  }

  private void showPermissionsDialog() {
    if (closed) return;
    if (permissionsDialog != null && permissionsDialog.isDisplayable()) {
      permissionsDialog.toFront();
      return;
    }
    allowEdits.setSelected(permissions.isEditsAllowed());
    renderPermission();
    final var dialog =
        new JDialog(
            SwingUtilities.getWindowAncestor(this),
            S.get("aiPermissionsTitle"),
            java.awt.Dialog.ModalityType.MODELESS);
    permissionsDialog = dialog;
    dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
    dialog.setContentPane(permissionPanel);
    dialog.getRootPane().setDefaultButton(closePermissions);
    dialog.addWindowListener(
        new WindowAdapter() {
          @Override
          public void windowClosed(WindowEvent event) {
            if (permissionsDialog == dialog) permissionsDialog = null;
          }
        });
    dialog.pack();
    dialog.setMinimumSize(new Dimension(380, 180));
    dialog.setLocationRelativeTo(this);
    dialog.setVisible(true);
  }

  private ModelConfig readModelConfig() {
    return new ModelConfig(
        URI.create(endpointField.getText().trim()),
        modelField.getText().trim(),
        new String(apiKeyField.getPassword()),
        Duration.ofSeconds(60));
  }

  private static JTextArea descriptionArea() {
    final var text = new JTextArea();
    text.setEditable(false);
    text.setLineWrap(true);
    text.setWrapStyleWord(true);
    text.setOpaque(false);
    text.setColumns(28);
    text.setBorder(null);
    text.setFont(AiTheme.font(13));
    text.setForeground(AiTheme.muted());
    return text;
  }

  private void submit() {
    final var text = prompt.getText().trim();
    if (text.isEmpty()) return;
    final var state = controller.getState();
    if (state == AgentState.WAITING_FOR_USER) {
      prompt.setText("");
      append("aiRoleYou", text);
      setBusy(true);
      runWorker(() -> controller.continueTask(text));
      return;
    }
    final ModelConfig config;
    try {
      config = readModelConfig();
    } catch (RuntimeException e) {
      showSettingsDialog();
      return;
    }
    prompt.setText("");
    append("aiRoleYou", text);
    setBusy(true);
    runWorker(() -> controller.startTask(project, text, config, new UiListener()));
  }

  private void chooseVisionImage() {
    final var chooser = project.createChooser();
    chooser.setDialogTitle(VisionStrings.get("chooserTitle"));
    chooser.setAcceptAllFileFilterUsed(false);
    chooser.setFileFilter(new FileNameExtensionFilter(VisionStrings.get("pngFilter"), "png"));
    if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
    final var selected = chooser.getSelectedFile().toPath();
    append("aiRoleYou", VisionStrings.get("selected", selected.getFileName()));
    visionBusy = true;
    visionCancellable = true;
    final var operation = ++visionOperation;
    displayedDetail = VisionStrings.get("recognizing");
    updateActionButtons(AgentState.EXECUTING);
    visionTask =
        worker.submit(
            () -> {
              try {
                final var prepared = visionController.prepare(project, selected);
                ui(
                    () -> {
                      if (!closed && visionBusy && operation == visionOperation) {
                        showVisionConfirmation(prepared);
                      }
                    });
              } catch (VisionServiceException exception) {
                if (exception.reason() != VisionServiceException.Reason.CANCELLED) {
                  ui(
                      () ->
                          finishVisionFailure(operation, visionFailureMessage(exception)));
                }
              } catch (RuntimeException exception) {
                if (!Thread.currentThread().isInterrupted()) {
                  ui(
                      () ->
                          finishVisionFailure(
                              operation,
                              VisionStrings.get(
                                  "failed",
                                  exception.getMessage() == null
                                      ? exception.getClass().getSimpleName()
                                      : exception.getMessage())));
                }
              }
            });
  }

  private void showVisionConfirmation(VisionImportController.PreparedImport prepared) {
    finishVisionActivity();
    final var reviewed =
        VisionRecognitionReviewDialog.show(
            this,
            prepared,
            corrections -> visionController.revise(project, prepared, corrections));
    if (reviewed == null) return;
    if (!permissions.isEditsAllowed()) {
      showVisionError(VisionStrings.get("permissionRequired"));
      return;
    }
    visionBusy = true;
    visionCancellable = false;
    final var operation = ++visionOperation;
    displayedDetail = VisionStrings.get("importing");
    updateActionButtons(AgentState.EXECUTING);
    visionTask =
        worker.submit(
            () -> {
              try {
                final var result = visionController.apply(project, reviewed);
                ui(
                    () -> {
                      if (closed || operation != visionOperation) return;
                      project.setCurrentCircuit(result.circuit());
                      append(
                          "aiRoleAgent",
                          VisionStrings.get(
                              "imported",
                              reviewed.plan().targetCircuitName(),
                              reviewed.componentCount(),
                              reviewed.netCount()));
                      finishVisionActivity();
                    });
              } catch (RuntimeException exception) {
                ui(
                    () ->
                        finishVisionFailure(
                            operation,
                            VisionStrings.get(
                                "failed",
                                exception.getMessage() == null
                                    ? exception.getClass().getSimpleName()
                                    : exception.getMessage())));
              }
            });
  }

  private void cancelVisionImport() {
    visionOperation++;
    final var task = visionTask;
    if (task != null) task.cancel(true);
    append("aiRoleAgent", VisionStrings.get("cancelled"));
    finishVisionActivity();
  }

  private void finishVisionFailure(String message) {
    finishVisionActivity();
    showVisionError(message);
  }

  private void finishVisionFailure(long operation, String message) {
    if (closed || operation != visionOperation) return;
    finishVisionFailure(message);
  }

  private void finishVisionActivity() {
    visionBusy = false;
    visionCancellable = false;
    visionTask = null;
    displayedDetail = "";
    updateActionButtons(controller.getState());
  }

  private static String visionFailureMessage(VisionServiceException exception) {
    return switch (exception.reason()) {
      case SERVICE_UNAVAILABLE -> VisionStrings.get("serviceUnavailable");
      case TIMEOUT -> VisionStrings.get("timeout");
      case CANCELLED -> VisionStrings.get("cancelled");
      case REJECTED ->
          VisionStrings.get(
              "rejected",
              exception.serviceCode().isBlank() ? "REJECTED" : exception.serviceCode(),
              exception.getMessage());
      case INVALID_RESPONSE, IO_ERROR -> VisionStrings.get("failed", exception.getMessage());
    };
  }

  private void applyReviewed() {
    try {
      final var result = controller.applyReviewedTask();
      ui(
          () -> {
            append("aiRoleAgent", result.message());
            updateActionButtons(controller.getState());
          });
    } catch (RuntimeException e) {
      ui(
          () -> {
            updateActionButtons(controller.getState());
            showError("aiApplyFailed", e.getMessage());
          });
    }
  }

  private void cleanupArtifacts() {
    try {
      final var message = controller.cleanupRetainedTaskArtifacts();
      ui(
          () -> {
            append("aiRoleAgent", message);
            retainedCopy = false;
            updateActionButtons(controller.getState());
          });
    } catch (RuntimeException e) {
      ui(() -> showError("aiCleanupFailed", e.getMessage()));
    }
  }

  private void discardReviewed() {
    try {
      controller.discardReviewedTask();
      ui(() -> updateActionButtons(controller.getState()));
    } catch (RuntimeException e) {
      ui(() -> showError("aiDiscardFailed", e.getMessage()));
    }
  }

  private void runWorker(Runnable action) {
    worker.submit(
        () -> {
          try {
            action.run();
          } catch (Throwable t) {
            ui(
                () -> {
                  setBusy(false);
                  showError(
                      "aiErrorTitle",
                      t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
                });
          }
        });
  }

  private void setBusy(boolean busy) {
    updateActionButtons(busy ? AgentState.EXECUTING : controller.getState());
  }

  private void updateActionButtons(AgentState state) {
    displayedState = state;
    final var waiting = state == AgentState.WAITING_FOR_USER;
    final var reviewing = state == AgentState.REVIEW;
    final var terminal =
        state == AgentState.COMPLETED
            || state == AgentState.DISCARDED
            || state == AgentState.CANCELLED
            || state.name().startsWith("FAILED");
    final var busy = !waiting && !reviewing && !terminal && state != AgentState.READY;
    send.setEnabled(!visionBusy && (waiting || terminal || state == AgentState.READY));
    visionImport.setEnabled(
        !visionBusy && !waiting && !reviewing && (terminal || state == AgentState.READY));
    final var canCancel =
        (visionBusy && visionCancellable) || (!visionBusy && (waiting || busy));
    cancel.setEnabled(canCancel);
    apply.setEnabled(reviewing && reviewApplyAllowed && permissions.isEditsAllowed());
    discard.setEnabled(waiting || reviewing);
    cleanup.setEnabled(terminal && retainedCopy);
    final var pendingSpecification = controller.pendingSpecification();
    final var matchesDisplayedSpecification =
        activeSpecification == null
            ? pendingSpecification == null
            : activeSpecification.specification().equals(pendingSpecification);
    confirmSpec.setEnabled(
        waiting && controller.hasPendingSpecification() && matchesDisplayedSpecification);
    reviewDetails.setEnabled(reviewing && !fullReview.isEmpty());
    confirmSpec.setVisible(
        waiting && controller.hasPendingSpecification() && matchesDisplayedSpecification);
    reviewDetails.setVisible(reviewing && !fullReview.isEmpty());
    if (reviewView != null) reviewView.setApplyAvailable(apply.isEnabled());
    if (!reviewing) closeReviewDialog();
    apply.setVisible(reviewing);
    discard.setVisible(waiting || reviewing);
    cleanup.setVisible(terminal && retainedCopy);
    cancel.setVisible(canCancel);
    composeContext.setVisible(!cancel.isVisible());
    final var inlineConfirmation =
        waiting
            && controller.hasPendingSpecification()
            && matchesDisplayedSpecification
            && activeSpecification != null;
    if (inlineConfirmation) {
      if (confirmSpec.getParent() != specificationActions) {
        specificationActions.add(confirmSpec);
        specificationActions.add(reviseSpec);
      }
      layoutActions(specificationActions);
      activeSpecification.setStatus(S.get("aiSpecCardPending"));
      activeSpecification.setActionComponent(specificationActions);
    } else {
      if (activeSpecification != null) {
        activeSpecification.setActionComponent(null);
        activeSpecification.setStatus(S.get("aiSpecCardRecorded"));
      }
      if (confirmSpec.getParent() != taskActions) taskActions.add(confirmSpec);
    }
    // Abandon/cancel remains available in the composer while a specification is pending.
    discard.setVisible(reviewing || (waiting && !inlineConfirmation));
    layoutActions(taskActions);
    taskActions.setVisible(
        Arrays.stream(taskActions.getComponents()).anyMatch(java.awt.Component::isVisible));
    transcript.refreshContentLayout();
    renderStatus();
    revalidate();
  }

  private static void layoutActions(JPanel panel) {
    final var visibleActions =
        Arrays.stream(panel.getComponents()).filter(java.awt.Component::isVisible).toList();
    for (int i = 0; i < visibleActions.size(); i++) {
      final var constraints = new GridBagConstraints();
      constraints.gridx = i % 2;
      constraints.gridy = i / 2;
      constraints.weightx = 1;
      constraints.fill = GridBagConstraints.HORIZONTAL;
      constraints.insets = new Insets(3, 0, 3, i % 2 == 0 ? 8 : 0);
      if (i == visibleActions.size() - 1 && i % 2 == 0) constraints.gridwidth = 2;
      ((GridBagLayout) panel.getLayout()).setConstraints(visibleActions.get(i), constraints);
    }
    panel.revalidate();
  }

  private void append(String roleKey, String text) {
    if (closed || text == null || text.isBlank()) return;
    transcriptEntries.add(new TranscriptEntry(roleKey, null, text, null));
    renderTranscript();
  }

  private void renderTranscript() {
    final var messages = new ArrayList<ChatTranscriptView.Message>();
    var messageIndex = 0;
    for (var entry : transcriptEntries) {
      final var conversational =
          entry.roleKey().equals("aiRoleYou") || entry.roleKey().equals("aiRoleAgent");
      final var heading =
          conversational
              ? ""
              : entry.qualifier() == null
                  ? S.get(entry.roleKey())
                  : S.get(entry.roleKey(), entry.qualifier());
      final String text;
      if (entry.validationStatus() != null) {
        text =
            AiStrings.validationStatus(entry.validationStatus())
                + " — "
                + AiStrings.displayMessage(entry.text());
      } else if (entry.roleKey().equals("aiRoleAgent") || entry.roleKey().equals("aiRoleError")) {
        text = AiStrings.displayMessage(entry.text());
      } else {
        text = entry.text();
      }
      final var kind =
          switch (entry.roleKey()) {
            case "aiRoleYou" -> ChatTranscriptView.Kind.USER;
            case "aiRoleAgent", "aiRoleVerification" -> ChatTranscriptView.Kind.ASSISTANT;
            case "aiRoleError" -> ChatTranscriptView.Kind.ERROR;
            default -> ChatTranscriptView.Kind.DEBUG;
          };
      final var body = entry.validationStatus() == null ? text : heading + ":\n" + text;
      messages.add(
          new ChatTranscriptView.Message(
              kind, heading, body, "transcript-" + messageIndex++, entry.specificationCard()));
      if (entry.specificationCard() != null) entry.specificationCard().refreshLanguage();
    }
    transcript.setMessages(messages);
  }

  private void showError(String titleKey, String message) {
    JOptionPane.showMessageDialog(
        this, AiStrings.displayMessage(message), S.get(titleKey), JOptionPane.ERROR_MESSAGE);
  }

  private void showVisionError(String message) {
    JOptionPane.showMessageDialog(
        this,
        message,
        VisionStrings.get("errorTitle"),
        JOptionPane.ERROR_MESSAGE);
  }

  @Override
  public void localeChanged() {
    ui(
        () -> {
          if (!closed) refreshLanguage();
        });
  }

  private void refreshLanguage() {
    title.setText(S.get("aiAssistantName"));
    title.setToolTipText(S.get("aiPanelTitle"));
    settings.setText(S.get("aiSettings"));
    settings.setToolTipText(S.get("aiModelSettingsHint"));
    permissionButton.setText(S.get("aiPermissions"));
    permissionButton.setToolTipText(S.get("aiPermissionsHint"));
    settingsHint.setText(S.get("aiModelSettingsHint"));
    permissionDescription.setText(S.get("aiPermissionDescription"));
    apiKeyHint.setText(S.get("aiApiKeyTip"));
    saveSettings.setText(S.get("aiSaveSettings"));
    cancelSettings.setText(S.get("aiCancel"));
    closePermissions.setText(S.get("aiClose"));
    closeReview.setText(S.get("aiClose"));
    prompt.setPlaceholder(S.get("aiPromptHint"));
    prompt.setToolTipText(S.get("aiPromptHint"));
    transcript.setEmptyText(S.get("aiConversationEmpty"));
    if (settingsDialog != null) settingsDialog.setTitle(S.get("aiSettingsTitle"));
    if (permissionsDialog != null) permissionsDialog.setTitle(S.get("aiPermissionsTitle"));
    if (reviewDialog != null) reviewDialog.setTitle(S.get("aiReviewDialogTitle"));
    if (reviewView != null) reviewView.refreshLanguage();
    endpointLabel.setText(S.get("aiEndpoint"));
    modelLabel.setText(S.get("aiModel"));
    apiKeyLabel.setText(S.get("aiApiKey"));
    apiKeyField.setToolTipText(S.get("aiApiKeyTip"));
    allowEdits.setText(S.get("aiAllowEdits"));
    send.setText(S.get("aiSend"));
    visionImport.setText(VisionStrings.get("choose"));
    visionImport.setToolTipText(VisionStrings.get("chooseHint"));
    cancel.setText(S.get("aiCancel"));
    apply.setText(S.get("aiApply"));
    discard.setText(S.get("aiDiscard"));
    cleanup.setText(S.get("aiDeleteCopy"));
    confirmSpec.setText(S.get("aiConfirmSpec"));
    reviewDetails.setText(S.get("aiReviewDetails"));
    reviseSpec.setText(S.get("aiReviseSpec"));
    composeContext.setText(S.get("aiComposeContext"));
    composeContext.setToolTipText(S.get("aiComposeContext"));
    renderPermission();
    renderStatus();
    renderTranscript();
    updateActionButtons(displayedState);
  }

  private void renderStatus() {
    status.setText(AiStrings.state(displayedState));
    final var detail = displayedDetail == null ? "" : AiStrings.displayMessage(displayedDetail);
    status.setToolTipText(detail);
    final var footerText =
        displayedState == AgentState.READY
                || (displayedState == AgentState.WAITING_FOR_USER
                    && controller.hasPendingSpecification())
            ? S.get("aiComposeHelp")
            : detail;
    statusDetail.setText(
        footerText.length() <= 240 ? footerText : footerText.substring(0, 240) + "…");
    statusDetail.setToolTipText(detail);
    statusDetail.setVisible(!footerText.isBlank());
  }

  private void renderPermission() {
    permissionState.setText(
        S.get(permissions.isEditsAllowed() ? "aiPermissionEditing" : "aiPermissionReadOnly"));
    permissionState.setToolTipText(S.get("aiPermissionDescription"));
    permissionButton.putClientProperty("ai.permissionAllowed", permissions.isEditsAllowed());
    permissionButton.setIcon(AiTheme.icon("permission-dot"));
    permissionButton.setToolTipText(permissionState.getText() + " — " + S.get("aiPermissionsHint"));
  }

  private void ui(Runnable action) {
    final Runnable guarded =
        () -> {
          if (!closed) action.run();
        };
    if (SwingUtilities.isEventDispatchThread()) guarded.run();
    else SwingUtilities.invokeLater(guarded);
  }

  private static String setting(String property, String environment, String fallback) {
    final var fromProperty = System.getProperty(property);
    if (fromProperty != null && !fromProperty.isBlank()) return fromProperty;
    final var fromEnvironment = System.getenv(environment);
    if (fromEnvironment != null && !fromEnvironment.isBlank()) return fromEnvironment;
    return fallback;
  }

  @Override
  public void close() {
    if (closed) return;
    closed = true;
    LocaleManager.removeLocaleListener(this);
    cancelModelSettings();
    if (permissionsDialog != null) permissionsDialog.dispose();
    closeReviewDialog();
    visionOperation++;
    final var currentVisionTask = visionTask;
    if (currentVisionTask != null) currentVisionTask.cancel(true);
    controller.cancel();
    worker.submit(
        () -> {
          controller.close();
          visionController.close();
        });
    worker.shutdown();
  }

  private final class UiListener implements AgentTaskListener {
    @Override
    public void stateChanged(AgentState state, String detail) {
      ui(
          () -> {
            if (closed) return;
            displayedDetail = detail;
            if (state == AgentState.CHECKING_PERMISSION
                || state == AgentState.PREPARING_WORKSPACE) {
              if (activeSpecification != null) {
                activeSpecification.setActionComponent(null);
                activeSpecification.setStatus(S.get("aiSpecCardRecorded"));
                activeSpecification = null;
              }
              retainedCopy = false;
              reviewApplyAllowed = false;
              fullReview = Map.of();
              reviewView = null;
              closeReviewDialog();
            } else if (state == AgentState.PLANNING
                || state == AgentState.WAITING_FOR_USER
                || state == AgentState.REVIEW) {
              retainedCopy = true;
            }
            updateActionButtons(state);
            renderStatus();
          });
    }

    @Override
    public void assistantMessage(String message) {
      ui(() -> append("aiRoleAgent", message));
    }

    @Override
    public void specificationProposed(TaskSpec specification, String presentation) {
      ui(
          () -> {
            if (activeSpecification == null
                || !activeSpecification.specification().equals(specification)) {
              if (activeSpecification != null) {
                activeSpecification.setActionComponent(null);
                activeSpecification.setStatus(S.get("aiSpecCardRecorded"));
              }
              activeSpecification = new SpecificationCard(specification);
              transcriptEntries.add(
                  new TranscriptEntry(
                      "aiRoleAgent", null, presentation, null, activeSpecification));
            }
            renderTranscript();
            updateActionButtons(controller.getState());
          });
    }

    @Override
    public void toolActivity(String toolName, Map<String, Object> result) {
      ui(
          () -> {
            if (closed) return;
            transcriptEntries.add(
                new TranscriptEntry("aiRoleTool", toolName, compact(result), null));
            renderTranscript();
          });
    }

    @Override
    public void reviewReady(
        String taskId,
        String finalMessage,
        VerificationEvidence evidence,
        Map<String, Object> diff,
        boolean applyAllowed,
        java.nio.file.Path taskDirectory) {
      final var reviewSnapshot = controller.reviewDetails();
      ui(
          () -> {
            if (closed) return;
            transcriptEntries.add(
                new TranscriptEntry(
                    "aiRoleVerification", null, evidence.summary(), evidence.status()));
            append("aiRoleDiff", compact(diff));
            append("aiRoleArtifacts", taskDirectory.toString());
            fullReview = reviewSnapshot;
            closeReviewDialog();
            reviewView = null;
            reviewApplyAllowed = applyAllowed || evidence.isTrustedPass();
            updateActionButtons(controller.getState());
          });
    }

    @Override
    public void failed(String message, Throwable error) {
      ui(() -> append("aiRoleError", message));
    }
  }

  private record TranscriptEntry(
      String roleKey,
      String qualifier,
      String text,
      ValidationStatus validationStatus,
      SpecificationCard specificationCard) {
    TranscriptEntry(
        String roleKey, String qualifier, String text, ValidationStatus validationStatus) {
      this(roleKey, qualifier, text, validationStatus, null);
    }
  }

  /** Keep the compact toolbar on one row when it fits, wrap it for longer UI languages. */
  private static final class ResponsiveHeader extends JPanel {
    private static final long serialVersionUID = 1L;
    private final JComponent heading;
    private final JComponent actions;

    ResponsiveHeader(JComponent heading, JComponent actions) {
      super(null);
      this.heading = heading;
      this.actions = actions;
      add(heading);
      add(actions);
    }

    private int availableWidth() {
      final var insets = getInsets();
      final int parentWidth = getParent() == null ? 0 : getParent().getWidth();
      return Math.max(1, (parentWidth > 0 ? parentWidth : 380) - insets.left - insets.right);
    }

    @Override
    public Dimension getPreferredSize() {
      final var insets = getInsets();
      final var headingSize = heading.getPreferredSize();
      final var actionsSize = actions.getPreferredSize();
      final boolean wrap = headingSize.width + actionsSize.width + 12 > availableWidth();
      final int height =
          wrap
              ? headingSize.height + actionsSize.height + 8
              : Math.max(headingSize.height, actionsSize.height);
      return new Dimension(380, height + insets.top + insets.bottom);
    }

    @Override
    public void doLayout() {
      final var insets = getInsets();
      final int width = Math.max(1, getWidth() - insets.left - insets.right);
      final var headingSize = heading.getPreferredSize();
      final var actionsSize = actions.getPreferredSize();
      if (headingSize.width + actionsSize.width + 12 > width) {
        heading.setBounds(insets.left, insets.top, width, headingSize.height);
        actions.setBounds(
            insets.left, insets.top + headingSize.height + 8, width, actionsSize.height);
      } else {
        heading.setBounds(
            insets.left,
            insets.top,
            width - actionsSize.width - 12,
            Math.max(headingSize.height, actionsSize.height));
        actions.setBounds(
            insets.left + width - actionsSize.width,
            insets.top,
            actionsSize.width,
            Math.max(headingSize.height, actionsSize.height));
      }
    }
  }

  private record SettingsSnapshot(String endpoint, String model, char[] apiKey) {
    void clearKey() {
      Arrays.fill(apiKey, '\0');
    }
  }

  private static final class PromptArea extends JTextArea {
    private static final long serialVersionUID = 1L;
    private String placeholder = "";

    void setPlaceholder(String text) {
      placeholder = text;
      repaint();
    }

    @Override
    protected void paintComponent(java.awt.Graphics graphics) {
      super.paintComponent(graphics);
      if (!getText().isEmpty()) return;
      final var copy = graphics.create();
      try {
        copy.setColor(AiTheme.muted());
        copy.setFont(getFont());
        final var metrics = copy.getFontMetrics();
        final var insets = getInsets();
        final var width = getWidth() - insets.left - insets.right;
        var text = placeholder;
        while (!text.isEmpty() && metrics.stringWidth(text + "…") > width) {
          text = text.substring(0, text.length() - 1);
        }
        if (text.length() < placeholder.length()) text += "…";
        copy.drawString(text, insets.left, insets.top + metrics.getAscent());
      } finally {
        copy.dispose();
      }
    }
  }

  private static String compact(Map<String, Object> value) {
    final var text = String.valueOf(value);
    return text.length() <= 1400 ? text : text.substring(0, 1400) + "…";
  }

  private static final class AgentThreadFactory implements ThreadFactory {
    @Override
    public Thread newThread(Runnable r) {
      final var thread = new Thread(r, "Logisim-AI-Agent");
      thread.setDaemon(true);
      return thread;
    }
  }
}
