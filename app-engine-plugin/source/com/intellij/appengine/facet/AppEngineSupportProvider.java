package com.intellij.appengine.facet;

import com.intellij.appengine.sdk.AppEngineSdk;
import com.intellij.appengine.sdk.impl.AppEngineSdkUtil;
import com.intellij.appengine.server.instance.AppEngineServerModel;
import com.intellij.appengine.server.run.AppEngineServerConfigurationType;
import com.intellij.appengine.util.AppEngineUtil;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.configurations.ConfigurationFactory;
import com.intellij.facet.ui.FacetBasedFrameworkSupportProvider;
import com.intellij.facet.ui.ValidationResult;
import com.intellij.icons.AllIcons;
import com.intellij.ide.fileTemplates.FileTemplate;
import com.intellij.ide.fileTemplates.FileTemplateManager;
import com.intellij.ide.util.frameworkSupport.*;
import com.intellij.javaee.appServerIntegrations.ApplicationServer;
import com.intellij.javaee.run.configuration.CommonModel;
import com.intellij.javaee.run.configuration.J2EEConfigurationFactory;
import com.intellij.javaee.web.artifact.WebArtifactUtil;
import com.intellij.openapi.application.Result;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.DependencyScope;
import com.intellij.openapi.roots.ModifiableRootModel;
import com.intellij.openapi.roots.OrderRootType;
import com.intellij.openapi.roots.libraries.Library;
import com.intellij.openapi.roots.libraries.LibraryTable;
import com.intellij.openapi.roots.libraries.LibraryTablesRegistrar;
import com.intellij.openapi.ui.LabeledComponent;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.packaging.artifacts.Artifact;
import com.intellij.packaging.impl.artifacts.ArtifactUtil;
import com.intellij.packaging.impl.run.BuildArtifactsBeforeRunTaskProvider;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.HyperlinkLabel;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.jps.appengine.model.PersistenceApi;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.io.IOException;
import java.util.Collection;

/**
 * @author nik
 */
public class AppEngineSupportProvider extends FacetBasedFrameworkSupportProvider<AppEngineFacet> {
  private static final Logger LOG = Logger.getInstance("#com.intellij.appengine.facet.AppEngineSupportProvider");
  private static final String JPA_PROVIDER_ID = "facet:jpa";
  private static final String WEB_PROVIDER_ID = "facet:web";

  public AppEngineSupportProvider() {
    super(AppEngineFacet.getFacetType());
  }

  @Override
  public String[] getPrecedingFrameworkProviderIds() {
    return new String[]{JPA_PROVIDER_ID, WEB_PROVIDER_ID};
  }

  protected void setupConfiguration(AppEngineFacet facet, ModifiableRootModel rootModel, FrameworkVersion version) {
  }

  @Nullable
  private VirtualFile createFileFromTemplate(final String templateName, final VirtualFile parent, final String fileName) {
    parent.refresh(false, false);
    final FileTemplate template = FileTemplateManager.getInstance().getJ2eeTemplate(templateName);
    try {
      final String text = template.getText(FileTemplateManager.getInstance().getDefaultProperties());
      VirtualFile file = parent.findChild(fileName);
      if (file == null) {
        file = parent.createChildData(this, fileName);
      }
      VfsUtil.saveText(file, text);
      return file;
    }
    catch (IOException e) {
      LOG.error(e);
      return null;
    }
  }

  private void addSupport(final Module module, final ModifiableRootModel rootModel, String sdkPath, @Nullable PersistenceApi persistenceApi) {
    super.addSupport(module, rootModel, null, null);
    final VirtualFile descriptorDir = AppEngineWebIntegration.getInstance().suggestParentDirectoryForAppEngineWebXml(module, rootModel);
    if (descriptorDir != null) {
      createFileFromTemplate(AppEngineTemplateGroupDescriptorFactory.APP_ENGINE_WEB_XML_TEMPLATE, descriptorDir,
                             AppEngineUtil.APP_ENGINE_WEB_XML_NAME);
    }


    final AppEngineFacet appEngineFacet = AppEngineFacet.getAppEngineFacetByModule(module);
    LOG.assertTrue(appEngineFacet != null);
    final AppEngineFacetConfiguration facetConfiguration = appEngineFacet.getConfiguration();
    facetConfiguration.setSdkHomePath(sdkPath);
    final AppEngineSdk sdk = appEngineFacet.getSdk();
    final Artifact artifact = findContainingArtifact(appEngineFacet);

    final ApplicationServer appServer = sdk.getOrCreateAppServer();
    final Project project = module.getProject();
    if (appServer != null) {
      final ConfigurationFactory type = AppEngineServerConfigurationType.getInstance().getConfigurationFactories()[0];
      final RunnerAndConfigurationSettings settings = J2EEConfigurationFactory.getInstance().addAppServerConfiguration(project, type, appServer);
      if (artifact != null) {
        final CommonModel configuration = (CommonModel)settings.getConfiguration();
        ((AppEngineServerModel)configuration.getServerModel()).setArtifact(artifact);
        BuildArtifactsBeforeRunTaskProvider.setBuildArtifactBeforeRun(project, configuration, artifact);
      }
      rootModel.addLibraryEntry(appServer.getLibrary()).setScope(DependencyScope.PROVIDED);
    }

    final Library apiJar = addProjectLibrary(module, "AppEngine API", sdk.getLibUserDirectoryPath(), VirtualFile.EMPTY_ARRAY);
    rootModel.addLibraryEntry(apiJar);
    if (artifact != null) {
      WebArtifactUtil.getInstance().addLibrary(apiJar, artifact, project);
    }

    if (persistenceApi != null) {
      facetConfiguration.setRunEnhancerOnMake(true);
      facetConfiguration.setPersistenceApi(persistenceApi);
      facetConfiguration.getFilesToEnhance().addAll(AppEngineUtil.getDefaultSourceRootsToEnhance(rootModel));
      try {
        final VirtualFile[] sourceRoots = rootModel.getSourceRoots();
        final VirtualFile sourceRoot;
        if (sourceRoots.length > 0) {
          sourceRoot = sourceRoots[0];
        }
        else {
          sourceRoot = findOrCreateChildDirectory(rootModel.getContentRoots()[0], "src");
        }
        VirtualFile metaInf = findOrCreateChildDirectory(sourceRoot, "META-INF");
        if (persistenceApi == PersistenceApi.JDO || persistenceApi == PersistenceApi.JDO3) {
          createFileFromTemplate(AppEngineTemplateGroupDescriptorFactory.APP_ENGINE_JDO_CONFIG_TEMPLATE, metaInf, AppEngineUtil.JDO_CONFIG_XML_NAME);
        }
        else {
          final VirtualFile file = createFileFromTemplate(AppEngineTemplateGroupDescriptorFactory.APP_ENGINE_JPA_CONFIG_TEMPLATE, metaInf, AppEngineUtil.JPA_CONFIG_XML_NAME);
          if (file != null) {
            AppEngineWebIntegration.getInstance().setupJpaSupport(module, file);
          }
        }
      }
      catch (IOException e) {
        LOG.error(e);
      }
      final Library library = addProjectLibrary(module, "AppEngine ORM", sdk.getOrmLibDirectoryPath(), sdk.getOrmLibSources());
      rootModel.addLibraryEntry(library);
      if (artifact != null) {
        WebArtifactUtil.getInstance().addLibrary(library, artifact, project);
      }
    }
  }

  @Nullable
  private static Artifact findContainingArtifact(AppEngineFacet appEngineFacet) {
    final Collection<Artifact> artifacts = ArtifactUtil.getArtifactsContainingModuleOutput(appEngineFacet.getModule());
    for (Artifact artifact : artifacts) {
      if (AppEngineWebIntegration.getInstance().getAppEngineTargetArtifactType().equals(artifact.getArtifactType())) {
        return artifact;
      }
    }
    return null;
  }

  private static Library addProjectLibrary(final Module module, final String name, final String path, final VirtualFile[] sources) {
    return new WriteAction<Library>() {
      protected void run(final Result<Library> result) {
        final LibraryTable libraryTable = LibraryTablesRegistrar.getInstance().getLibraryTable(module.getProject());
        Library library = libraryTable.getLibraryByName(name);
        if (library == null) {
          library = libraryTable.createLibrary(name);
          final Library.ModifiableModel model = library.getModifiableModel();
          model.addJarDirectory(VfsUtil.pathToUrl(path), false);
          for (VirtualFile sourceRoot : sources) {
            model.addRoot(sourceRoot, OrderRootType.SOURCES);
          }
          model.commit();
        }
        result.setResult(library);
      }
    }.execute().getResultObject();
  }

  private VirtualFile findOrCreateChildDirectory(VirtualFile parent, final String name) throws IOException {
    VirtualFile child = parent.findChild(name);
    if (child != null) {
      return child;
    }
    return parent.createChildDirectory(this, name);
  }

  @NotNull
  @Override
  public FrameworkSupportConfigurableBase createConfigurable(@NotNull FrameworkSupportModel model) {
    return new AppEngineSupportConfigurable(model);
  }

  private class AppEngineSupportConfigurable extends FrameworkSupportConfigurableBase implements FrameworkSupportModelListener {
    private JPanel myMainPanel;
    private AppEngineSdkEditor mySdkEditor;
    private JComboBox myPersistenceApiComboBox;
    private JPanel mySdkPanel;
    private HyperlinkLabel myErrorLabel;
    private JPanel myErrorPanel;

    private AppEngineSupportConfigurable(FrameworkSupportModel model) {
      super(AppEngineSupportProvider.this, model);
      mySdkEditor = new AppEngineSdkEditor(model.getProject());
      mySdkPanel.add(LabeledComponent.create(mySdkEditor.getMainComponent(), "Google App Engine SDK:"), BorderLayout.CENTER);
      PersistenceApiComboboxUtil.setComboboxModel(myPersistenceApiComboBox, true);
      if (model.isFrameworkSelected(JPA_PROVIDER_ID)) {
        myPersistenceApiComboBox.setSelectedItem(PersistenceApi.JPA.getDisplayName());
      }
      model.addFrameworkListener(this);

      myErrorLabel = new HyperlinkLabel();
      myErrorLabel.setIcon(AllIcons.RunConfigurations.ConfigurationWarning);
      myErrorLabel.setVisible(false);
      myErrorLabel.setHyperlinkTarget(AppEngineSdkUtil.APP_ENGINE_DOWNLOAD_URL);
      myErrorPanel.add(BorderLayout.CENTER, myErrorLabel);

      final Component component = mySdkEditor.getComboBox().getEditor().getEditorComponent();
      if (component instanceof JTextComponent) {
        ((JTextComponent)component).getDocument().addDocumentListener(new DocumentAdapter() {
          @Override
          protected void textChanged(DocumentEvent e) {
            checkSdk();
          }
        });
      }
      checkSdk();
    }

    private void checkSdk() {
      final String path = mySdkEditor.getPath();
      if (StringUtil.isEmptyOrSpaces(path)) {
        myErrorLabel.setVisible(true);
        myErrorLabel.setHyperlinkText("App Engine SDK path not specified. ", "Download", "");
        myMainPanel.repaint();
        return;
      }

      final ValidationResult result = AppEngineSdkUtil.checkPath(path);
      myErrorLabel.setVisible(!result.isOk());
      if (!result.isOk()) {
        myErrorLabel.setText("App Engine SDK path is not correct");
      }
      myMainPanel.repaint();
    }

    public void frameworkSelected(@NotNull FrameworkSupportProvider provider) {
      if (provider.getId().equals(JPA_PROVIDER_ID)) {
        myPersistenceApiComboBox.setSelectedItem(PersistenceApi.JPA.getDisplayName());
      }
    }

    public void frameworkUnselected(@NotNull FrameworkSupportProvider provider) {
      if (provider.getId().equals(JPA_PROVIDER_ID)) {
        myPersistenceApiComboBox.setSelectedItem(PersistenceApiComboboxUtil.NONE_ITEM);
      }
    }

    @Override
    public void wizardStepUpdated() {
    }

    @Override
    public void addSupport(@NotNull Module module, @NotNull ModifiableRootModel rootModel, @Nullable Library library) {
      AppEngineSupportProvider.this.addSupport(module, rootModel, mySdkEditor.getPath(), PersistenceApiComboboxUtil.getSelectedApi(myPersistenceApiComboBox));
    }


    @Override
    public JComponent getComponent() {
      return myMainPanel;
    }
  }
}
